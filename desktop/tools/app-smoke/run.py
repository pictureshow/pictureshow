#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Finite desktop E2E against an owned, disposable Jellyfin and original CC0 media.

This starts its own server; there is deliberately no --server option. Server data,
credentials and raw logs stay outside the uploaded evidence directory. The Java app
must independently finish its UI checks; API provisioning is not a UI smoke pass.
"""
from __future__ import annotations

import argparse
import contextlib
import ctypes
import functools
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import socket
import subprocess
import tarfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

VERSION = "10.11.11"
IMAGE = "jellyfin/jellyfin@sha256:aefb67e6a7ff1debdd154a78a7bbb780fd0c873d8639210a7f6a2016ad2b35db"
ARCHIVES = {
    ("Darwin", "arm64"): ("macos", "arm64", "tar.xz", "27c0bcbeac7a698cf0fd034e347f124f69083bc4463816099b52bebead614a47"),
    ("Darwin", "x86_64"): ("macos", "amd64", "tar.xz", "4289c359560d563a5725ed10ee68bd601ff2abe47861380f438bce3bd3272e43"),
    ("Windows", "amd64"): ("windows", "amd64", "zip", "3b5d3e904415ac6bd2c0ee9fe93235b4145d41c4c85ae782d244ac9767f77374"),
}
REQUIRED_CHECKS = frozenset({
    "server", "passwordSignIn", "home", "library", "sort", "detail",
    "seasons", "episodes", "resume", "playback", "progress", "audio",
    "subtitles", "nextEpisode", "settings", "quickConnect", "conversion", "autoplayNext",
})


def write_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


@contextlib.contextmanager
def gui_lease(path: Path | None, wait_seconds: float = 0):
    """Optionally share an advisory POSIX GUI lock with other visible test runs."""
    if path is None:
        yield
        return
    if os.name != "posix":
        raise ValueError("The optional GUI lock requires POSIX flock")
    if not math.isfinite(wait_seconds) or wait_seconds < 0:
        raise ValueError("GUI lock wait must be a finite nonnegative number of seconds")
    import fcntl
    deadline = time.monotonic() + wait_seconds
    with path.open("a+b") as lock:
        while True:
            try:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise RuntimeError(f"GUI lock stayed busy for {wait_seconds:g} seconds; desktop app was not launched") from None
                time.sleep(min(.1, remaining))
        try:
            yield
        finally:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def sanitized(text: str, *private_values: str) -> str:
    for value in private_values:
        if value:
            text = text.replace(value, "<redacted>")
    text = re.sub(r'https?://[^\s\]"<>]+', "<server-url>", text)
    text = re.sub(r'(?i)(accessToken|api_key|token|password|secret)(["\s]*[=:]["\s]*)[^\s,;"}]+', r'\1\2<redacted>', text)
    text = re.sub(r'[A-Za-z]:[\\/][^\s"<>]+', "<server-path>", text)
    text = re.sub(r'/(?:Users|home|private|tmp|var|media|a)/[^\s"<>]+', "<server-path>", text)
    return text


def selected_app_output(text: str, *private_values: str) -> str:
    prefixes = ("SMOKE ", "Exception in thread", "Caused by:", "java.lang.", "kotlin.")
    lines = []
    for line in text.splitlines():
        # These smoke diagnostics contain only stage/class/app-frame identity.
        # Reject malformed additions instead of admitting arbitrary messages.
        native_type = re.fullmatch(r"DESKTOP_PLAYER_FAILURE stage=(?:factory|profile|open|window-chrome) type=[A-Za-z0-9_.$]+", line)
        native_frame = re.fullmatch(r"DESKTOP_PLAYER_FRAME app\.pictureshow\.desktop\.[A-Za-z0-9_.$]+(?:<init>|<clinit>)?:-?[0-9]+", line)
        if line.startswith(prefixes) or native_type or native_frame:
            lines.append(sanitized(line, *private_values))
    return "\n".join(lines) + "\n"


def uncaught_app_exceptions(text: str) -> list:
    """Retain JVM exception identity and code locations, never raw messages."""
    result, current = [], None
    for line in text.splitlines():
        header = re.match(r'^Exception in thread "[^"\r\n]*" ([A-Za-z0-9_.$]+)(?::|$)', line)
        if header:
            current = {"type": header[1], "frames": []}
            result.append(current)
            continue
        if current is None:
            continue
        frame = re.fullmatch(r'\s+at (?:[A-Za-z0-9_.@+-]+/)?([A-Za-z0-9_.$<>]+)\((?:[A-Za-z0-9_.$-]+\.(?:java|kt):([0-9]+)|Native Method|Unknown Source)\)', line)
        if frame:
            current["frames"].append({"method": frame[1], "line": int(frame[2]) if frame[2] else None})
        elif not line.startswith(("\tat ", "\t... ", "Caused by:", "\tSuppressed:")):
            current = None
    return result


def require_no_uncaught_exceptions(exceptions: list) -> None:
    if exceptions:
        raise RuntimeError("Desktop app emitted an uncaught exception; inspect uncaught-app-exceptions.json")


def fixture_diagnostics(api: "API", evidence: Path, error: Exception) -> None:
    """Publish enough to diagnose a probe failure, without raw logs or tokens."""
    result = {"errorType": type(error).__name__, "error": sanitized(str(error), api.token)}
    try:
        folders = api.request("GET", "/Library/VirtualFolders")
        result["libraries"] = [{"name": folder.get("Name"),
                                 "locationCount": len(folder.get("Locations") or []),
                                 "hasItemId": bool(folder.get("ItemId"))}
                                for folder in folders]
        result["libraryProvisionReceipts"] = getattr(api, "fixture_library_receipts", [])
        result["probeCompletion"] = getattr(api, "fixture_probe_completion", None)
        catalog = api.request("GET", "/Items", query={"Recursive": "true", "Fields": "MediaSources,MediaStreams"})
        items = catalog.get("Items", [])
        result["itemCount"] = len(items)
        result["itemTypeCounts"] = {kind: sum(item.get("Type") == kind for item in items)
                                    for kind in sorted({item.get("Type", "Unknown") for item in items})}
        result["items"] = [{"name": item.get("Name"), "type": item.get("Type"),
                            "mediaSourceCount": len(item.get("MediaSources") or []),
                            "streamTypes": [stream.get("Type") for stream in item.get("MediaStreams") or []],
                            "videoCodecs": [stream.get("Codec") for stream in item.get("MediaStreams") or []
                                            if stream.get("Type") == "Video"]} for item in items]
        tasks = api.request("GET", "/ScheduledTasks")
        result["libraryTasks"] = [{"name": task.get("Name"), "state": task.get("State"),
                                    "lastStatus": (task.get("LastExecutionResult") or {}).get("Status")}
                                   for task in tasks if "library" in task.get("Key", "").lower()]
    except Exception as diagnostic_error:
        result["apiDiagnosticError"] = sanitized(str(diagnostic_error), api.token)
    executable = getattr(api, "fixture_ffmpeg", None)
    if executable:
        result["serverFfmpegBasename"] = Path(executable).name
        result["serverFfprobeSiblingExists"] = Path(executable).with_name("ffprobe.exe" if platform.system() == "Windows" else "ffprobe").exists()
        try:
            version = subprocess.check_output([executable, "-version"], text=True, stderr=subprocess.STDOUT, timeout=10)
            result["serverFfmpegVersion"] = sanitized(version.splitlines()[0])
        except Exception as diagnostic_error:
            result["ffmpegDiagnosticError"] = sanitized(str(diagnostic_error), api.token)
    private_root = getattr(api, "fixture_root", None)
    if private_root:
        error_lines = []
        for path in [private_root / "server.log", *list((private_root / "logs").glob("*.log"))]:
            if path.is_file():
                lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
                error_lines += [sanitized(line[:1500], api.token, str(private_root)) for line in lines
                                if re.search(r'error|exception|failed|ffmpeg|ffprobe', line, re.IGNORECASE)]
        result["sanitizedServerErrors"] = error_lines[-100:]
    write_json(evidence / "fixture-failure-diagnostics.json", result)


def validate_receipt(receipt: dict, samples: list[dict], quick_connect_approved: bool,
                     fixture: dict, encoder_samples: list[dict], cleanup: dict) -> dict:
    """A partial/old UI receipt or one initial Start report cannot pass E2E."""
    if receipt.get("pass") is not True:
        raise RuntimeError("Desktop UI smoke receipt did not pass")
    checks = receipt.get("checks")
    if not isinstance(checks, dict):
        raise RuntimeError("Desktop UI smoke receipt has no named checks")
    missing = sorted(name for name in REQUIRED_CHECKS if checks.get(name) is not True)
    if missing:
        raise RuntimeError("Desktop UI smoke missing checks: " + ", ".join(missing))
    if not quick_connect_approved:
        raise RuntimeError("No real Quick Connect attempt was authorized by the fixture viewer")
    positions: dict[str, list[int]] = {}
    for sample in samples:
        if isinstance(sample.get("positionTicks"), int):
            positions.setdefault(sample["itemId"], []).append(sample["positionTicks"])
    if not positions:
        raise RuntimeError("Server independently observed no active app playback session")
    progressed = any(max(ticks) - min(ticks) >= 10_000_000 for ticks in positions.values())
    if not progressed:
        raise RuntimeError("Server independently observed no advancing app playback progress")
    converted = any(sample["itemId"] == fixture["archiveMovieId"] and
                    (sample.get("playMethod") == "Transcode" or sample.get("videoDirect") is False)
                    for sample in samples)
    if not converted:
        raise RuntimeError("Server independently observed no Archive Signal transcoding session")
    if not any(sample["encoderCount"] > 0 for sample in encoder_samples):
        raise RuntimeError("Owned fixture independently observed no FFmpeg encoding process")
    if cleanup.get("activePlayingItems") != 0 or cleanup.get("encoderCount") != 0:
        raise RuntimeError("App left an active playing item or owned encoding after Stop")
    episodes = fixture["episodeIds"]
    near_end_index = next((index for index, sample in enumerate(samples)
                           if sample["itemId"] == episodes[0] and
                           int(sample.get("positionTicks") or 0) >= 1_700_000_000), None)
    if near_end_index is None or not any(sample["itemId"] == episodes[1]
                                         for sample in samples[near_end_index + 1:]):
        raise RuntimeError("Server did not observe near-end episode one followed by episode two")
    return {"pass": True, "serverObservedPlayback": True, "serverObservedProgress": True,
            "sessionSampleCount": len(samples), "quickConnectApproved": True,
            "serverObservedConversion": True, "ownedEncoderObserved": True,
            "ownedEncoderStopped": True, "serverObservedEpisodeContinuation": True,
            "appReceipt": receipt}


class API:
    def __init__(self, url: str, device: str = "pictureshow-desktop-fixture"):
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme != "http" or parsed.hostname != "127.0.0.1":
            raise ValueError("Smoke fixture API accepts its owned loopback server only")
        self.url, self.device, self.token = url, device, ""

    def request(self, method: str, path: str, body=None, query=None):
        url = self.url + path
        if query:
            url += "?" + urllib.parse.urlencode(query, doseq=True)
        auth = f'MediaBrowser Client="Pictureshow fixture", Device="Disposable CI", DeviceId="{self.device}", Version="1"'
        if self.token:
            auth += f', Token="{self.token}"'
        headers = {"Authorization": auth, "Accept": 'application/json; profile="PascalCase"'}
        data = json.dumps(body).encode() if body is not None else None
        if data is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                payload = response.read()
                return json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            # Request and response bodies may contain credentials; never print them.
            raise RuntimeError(f"Fixture {method} {path} HTTP {error.code}") from None

    def login(self, username: str, password: str):
        response = self.request("POST", "/Users/AuthenticateByName", {"Username": username, "Pw": password})
        self.token = response["AccessToken"]
        return response["User"]["Id"]


def generate_media(root: Path, ffmpeg: str) -> dict:
    """All pixels, audio samples, captions and posters originate in this script."""
    root.mkdir(parents=True, exist_ok=True)
    subtitle = root / "fixture.srt"
    subtitle.write_text("1\n00:00:00,000 --> 00:03:00,000\nPictureshow original CC0 subtitle\n", encoding="utf-8")
    source = root / "source.mkv"
    subprocess.run([
        ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24:duration=180",
        "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
        "-i", str(subtitle), "-map", "0:v", "-map", "1:a", "-map", "1:a", "-map", "2:s",
        "-t", "180", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "30", "-threads", "2",
        "-pix_fmt", "yuv420p", "-c:a", "aac", "-c:s", "srt",
        "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=spa",
        "-metadata:s:s:0", "language=eng", "-metadata:s:s:0", "title=English CC0",
        str(source),
    ], check=True)
    movie = root / "movies" / "Signal One (2026)"
    season = root / "shows" / "Signal Stories" / "Season 01"
    movie.mkdir(parents=True)
    season.mkdir(parents=True)
    shutil.copyfile(source, movie / "Signal One (2026).mkv")
    (movie / "movie.nfo").write_text("<movie><title>Signal One</title><year>2026</year><plot>An original CC0 signal for the desktop acceptance test.</plot><genre>Test signals</genre></movie>", encoding="utf-8")
    archive = root / "movies" / "Archive Signal (2026)"
    archive.mkdir(parents=True)
    # Mac/Linux's isolated smoke dependency closure omits FFV1. Windows keeps its
    # measured decoder set and uses a real viewer bitrate cap for conversion.
    # Neither case fabricates a limitation in the ordinary device profile.
    subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                    "-i", str(source), "-t", "20", "-map", "0:v:0", "-map", "0:a:0",
                    "-c:v", "ffv1", "-level", "3", "-threads", "2", "-c:a", "copy",
                    str(archive / "Archive Signal (2026).mkv")], check=True)
    (archive / "movie.nfo").write_text("<movie><title>Archive Signal</title><year>2026</year><plot>An original CC0 FFV1 signal that requires server conversion.</plot><genre>Test signals</genre></movie>", encoding="utf-8")
    for episode, title in [(1, "First Light"), (2, "Second Light")]:
        name = f"Signal Stories - S01E0{episode} - {title}"
        shutil.copyfile(source, season / f"{name}.mkv")
        (season / f"{name}.nfo").write_text(f"<episodedetails><title>{title}</title><season>1</season><episode>{episode}</episode><plot>Original CC0 episode {episode}.</plot></episodedetails>", encoding="utf-8")
    show = season.parent
    (show / "tvshow.nfo").write_text("<tvshow><title>Signal Stories</title><year>2026</year><plot>Two original CC0 episodes.</plot></tvshow>", encoding="utf-8")
    # Generate a geometric poster, with no downloaded art or font dependencies.
    ppm = root / "poster.ppm"
    pixels = bytearray()
    for y in range(600):
        for x in range(400):
            stripe = (x // 55 + y // 80) % 3
            pixels.extend([(21, 35, 61), (38, 92, 123), (228, 174, 72)][stripe])
    ppm.write_bytes(b"P6\n400 600\n255\n" + pixels)
    for directory in [movie, archive, show, season]:
        subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", str(ppm), "-frames:v", "1", str(directory / "poster.jpg")], check=True)
    manifest = {"license": "CC0-1.0", "creator": "Legitimate LLC", "durationSeconds": 180, "conversionFixture": {"name": "Archive Signal", "videoCodec": "ffv1", "durationSeconds": 20}, "audioLanguages": ["eng", "spa"], "subtitleLanguages": ["eng"], "sha256": {str(p.relative_to(root)).replace(os.sep, "/"): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(root.rglob("*")) if p.is_file() and p.suffix in (".mkv", ".jpg", ".nfo")}}
    write_json(root / "LICENSE.json", manifest)
    return manifest


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def native_server(root: Path) -> Path:
    system = platform.system()
    machine = platform.machine().lower()
    key = (system, machine)
    if key not in ARCHIVES:
        raise RuntimeError(f"No pinned native Jellyfin fixture for {key}")
    os_name, arch, extension, expected = ARCHIVES[key]
    archive = root / f"jellyfin-{os_name}-{arch}.{extension}"
    url = f"https://repo.jellyfin.org/files/server/{os_name}/stable/v{VERSION}/{arch}/jellyfin_{VERSION}-{arch}.{extension}"
    urllib.request.urlretrieve(url, archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
        raise RuntimeError("Pinned Jellyfin server archive digest mismatch")
    destination = root / "server"
    destination.mkdir()
    if extension == "zip":
        with zipfile.ZipFile(archive) as package:
            package.extractall(destination)
    else:
        with tarfile.open(archive) as package:
            package.extractall(destination, filter="data")
    exe = destination / "jellyfin" / ("jellyfin.exe" if system == "Windows" else "jellyfin")
    if system == "Darwin":
        # Official portable-install documentation requires ad-hoc signing.
        subprocess.run(["codesign", "--force", "--sign", "-", "--deep", str(exe)], check=True, stdout=subprocess.DEVNULL)
    return exe


def write_native_network_configuration(path: Path, port: int):
    path.write_text(f"""<?xml version="1.0"?>
<NetworkConfiguration>
  <BaseUrl/>
  <EnableHttps>false</EnableHttps>
  <InternalHttpPort>{port}</InternalHttpPort>
  <PublicHttpPort>{port}</PublicHttpPort>
  <EnableUPnP>false</EnableUPnP>
  <EnableRemoteAccess>false</EnableRemoteAccess>
  <AutoDiscovery>false</AutoDiscovery>
  <EnableIPv4>true</EnableIPv4>
  <EnableIPv6>false</EnableIPv6>
  <LocalNetworkAddresses><string>127.0.0.1</string></LocalNetworkAddresses>
</NetworkConfiguration>
""", encoding="utf-8")


@contextlib.contextmanager
def owned_server(root: Path, media: Path, ffmpeg: str):
    # Jellyfin can change its working directory internally. Native data and
    # encoder paths must remain valid independently of the launcher's cwd.
    root = root.resolve()
    media = media.resolve()
    ffmpeg = str(Path(shutil.which(ffmpeg) or ffmpeg).resolve())
    root.mkdir(parents=True)
    port = free_port()
    url = f"http://127.0.0.1:{port}"
    process = None
    container = None
    server_log = None
    try:
        if platform.system() == "Linux":
            container = "pictureshow-desktop-" + secrets.token_hex(6)
            subprocess.run(["docker", "run", "--detach", "--name", container, "--cpus=2", "--memory=2g", "--security-opt=no-new-privileges:true", "--publish", f"127.0.0.1:{port}:8096", "--mount", f"type=bind,src={media.resolve()},dst=/media,readonly", IMAGE], check=True, stdout=subprocess.DEVNULL)
            media_base = "/media"
            provenance = {"kind": "docker", "image": IMAGE}
        else:
            exe = native_server(root)
            for folder in ["data", "cache", "config", "logs"]:
                (root / folder).mkdir()
            # Discovery otherwise binds UDP 7359 on IPAddress.Any independently
            # of HTTP's LocalNetworkAddresses, including on loopback-only runs.
            write_native_network_configuration(root / "config" / "network.xml", port)
            # Fatal avoids request/query tokens in retained logs.
            write_json(root / "config" / "logging.json", {"Serilog": {"MinimumLevel": {"Default": "Warning", "Override": {"Microsoft": "Warning", "System": "Warning"}}}})
            server_log = (root / "server.log").open("w", encoding="utf-8")
            # Windows' official archive includes a matched real FFmpeg/FFprobe
            # pair. A Chocolatey shim can obscure the sibling FFprobe location.
            server_ffmpeg = str(exe.with_name("ffmpeg.exe")) if platform.system() == "Windows" else ffmpeg
            command = [str(exe), "--nowebclient", "--nonetchange", "--datadir", str(root / "data"), "--cachedir", str(root / "cache"), "--configdir", str(root / "config"), "--logdir", str(root / "logs"), "--ffmpeg", server_ffmpeg]
            process = subprocess.Popen(command, stdout=server_log, stderr=subprocess.STDOUT, cwd=exe.parent)
            media_base = str(media.resolve())
            provenance = {"kind": "native-portable", "version": VERSION, "sha256": ARCHIVES[(platform.system(), platform.machine().lower())][3]}
        api = API(url)
        api.fixture_process = process
        api.fixture_container = container
        api.fixture_root = root
        api.fixture_ffmpeg = server_ffmpeg if process is not None else None
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if process is not None and process.poll() is not None:
                raise RuntimeError(f"Owned Jellyfin exited before readiness ({process.returncode}); raw local logs withheld")
            try:
                info = api.request("GET", "/System/Info/Public")
                if info.get("Version") == VERSION:
                    break
            except (OSError, RuntimeError, ValueError):
                pass
            time.sleep(.5)
        else:
            raise RuntimeError("Owned disposable Jellyfin readiness timed out")
        yield api, media_base, provenance
    finally:
        if container is not None:
            subprocess.run(["docker", "rm", "--force", container], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        if server_log is not None:
            server_log.close()


def add_fixture_library(api: API, name: str, kind: str, path: str):
    # Validate through Jellyfin itself, then supply the documented explicit paths
    # query. An empty virtual folder can otherwise report a completed scan while
    # never seeing media, which obscures fixture-path errors as probe timeouts.
    entries = api.request("GET", "/Environment/DirectoryContents",
                          query={"path": path, "includeFiles": "true", "includeDirectories": "true"})
    if not entries:
        raise RuntimeError(f"Fixture {name} directory is empty from the server's perspective")
    api.request("POST", "/Library/VirtualFolders", {"LibraryOptions": {
        "EnableRealtimeMonitor": False, "EnableInternetProviders": False,
        "PathInfos": [{"Path": path}], "MetadataSavers": [],
        "TypeOptions": [{"Type": item_type, "MetadataFetchers": [], "ImageFetchers": []}
                        for item_type in ["Movie", "Series", "Season", "Episode"]]}},
        {"name": name, "collectionType": kind, "paths": path, "refreshLibrary": "false"})
    folder = next((folder for folder in api.request("GET", "/Library/VirtualFolders")
                   if folder.get("Name") == name), None)
    if folder is None or os.path.normcase(os.path.normpath(path)) not in {
            os.path.normcase(os.path.normpath(location)) for location in folder.get("Locations") or []}:
        raise RuntimeError(f"Fixture {name} virtual folder did not retain its requested media location")
    receipt = {"name": name, "serverDirectoryEntryCount": len(entries), "registeredLocation": True}
    if not hasattr(api, "fixture_library_receipts"):
        api.fixture_library_receipts = []
    api.fixture_library_receipts.append(receipt)


def fixture_video_codecs(item: dict) -> list:
    return [stream["Codec"] for source in item.get("MediaSources") or []
            for stream in source.get("MediaStreams") or []
            if stream.get("Type") == "Video" and stream.get("Codec")]


def fixture_episode_order(episodes: list):
    expected = {1: "First Light", 2: "Second Light"}
    if (len(episodes) != 2 or {item.get("IndexNumber") for item in episodes} != {1, 2}
            or not all(item.get("ParentIndexNumber") == 1
                       and item.get("Name") == expected.get(item.get("IndexNumber")) for item in episodes)):
        return None
    return sorted(episodes, key=lambda item: item["IndexNumber"])


def wait_for_fixture_media(api: API, user_id: str, timeout: float = 120, retry_after: float = 5):
    """Wait for actual stream probes, with one incomplete-item refresh after an idle scan."""
    deadline = time.monotonic() + timeout
    incomplete_since, retried = {}, set()
    api.fixture_probe_completion = {"complete": False, "refreshAttempts": []}
    while time.monotonic() < deadline:
        catalog = api.request("GET", "/Items", query={"Recursive": "true", "IncludeItemTypes": "Movie,Episode", "Fields": "MediaSources,MediaStreams", "UserId": user_id})
        items = catalog["Items"]
        movies = [item for item in items if item["Type"] == "Movie"]
        episodes = [item for item in items if item["Type"] == "Episode"]
        leaves = movies + episodes
        ordered_episodes = fixture_episode_order(episodes)
        if len(movies) == 2 and len(episodes) == 2 and all(fixture_video_codecs(item) for item in leaves):
            archive = next(item for item in movies if item["Name"] == "Archive Signal")
            if "ffv1" not in fixture_video_codecs(archive):
                raise RuntimeError("Archive Signal was not probed as the intended FFV1 conversion fixture")
            if ordered_episodes is not None:
                api.fixture_probe_completion.update({"complete": True, "videoCodecs": {
                    item["Name"]: fixture_video_codecs(item) for item in leaves},
                    "episodes": [{"itemId": item["Id"], "name": item["Name"],
                                  "season": item["ParentIndexNumber"], "episode": item["IndexNumber"]}
                                 for item in ordered_episodes]})
                return movies, ordered_episodes
        now = time.monotonic()
        incomplete = [item for item in leaves if not fixture_video_codecs(item)
                      or (item["Type"] == "Episode" and ordered_episodes is None)]
        for item in incomplete:
            incomplete_since.setdefault(item["Id"], now)
        candidates = [item for item in incomplete if item["Id"] not in retried and now - incomplete_since[item["Id"]] >= retry_after]
        if candidates:
            scans = [task for task in api.request("GET", "/ScheduledTasks") if "library" in task.get("Key", "").lower()]
            if scans and all(task.get("State") == "Idle" and (task.get("LastExecutionResult") or {}).get("Status") == "Completed" for task in scans):
                for item in candidates:
                    retried.add(item["Id"])
                    reason = "missing-video-stream-after-completed-scan" if not fixture_video_codecs(item) else "incomplete-episode-metadata-after-completed-scan"
                    attempt = {"itemId": item["Id"], "name": item["Name"], "reason": reason, "queued": False}
                    api.fixture_probe_completion["refreshAttempts"].append(attempt)
                    api.request("POST", f"/Items/{item['Id']}/Refresh", query={
                        "metadataRefreshMode": "FullRefresh", "imageRefreshMode": "None",
                        "replaceAllMetadata": "false", "replaceAllImages": "false"})
                    attempt["queued"] = True
        time.sleep(1)
    raise RuntimeError("Generated two movies and two episodes did not complete video stream probing and episode metadata")


def provision(api: API, media_base: str):
    admin_password, password = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
    api.request("GET", "/Startup/Configuration")
    api.request("POST", "/Startup/Configuration", {"UICulture": "en-US", "MetadataCountryCode": "US", "PreferredMetadataLanguage": "en"})
    api.request("GET", "/Startup/User")
    api.request("POST", "/Startup/User", {"Name": "fixture-admin", "Password": admin_password})
    api.request("POST", "/Startup/RemoteAccess", {"EnableRemoteAccess": False, "EnableAutomaticPortMapping": False})
    api.request("POST", "/Startup/Complete")
    api.login("fixture-admin", admin_password)
    configuration = api.request("GET", "/System/Configuration")
    configuration["ServerName"] = "Pictureshow test server"
    api.request("POST", "/System/Configuration", configuration)
    encoding = api.request("GET", "/System/Configuration/encoding")
    # Keep a short conversion observable while the player consumes it, rather
    # than letting FFmpeg finish the entire signal before the next process poll.
    encoding.update({"EnableThrottling": True, "ThrottleDelaySeconds": 5})
    api.request("POST", "/System/Configuration/encoding", encoding)
    viewer = api.request("POST", "/Users/New", {"Name": "desktop-viewer", "Password": password})
    for name, kind, path in [("Movies", "movies", "movies"), ("Shows", "tvshows", "shows")]:
        add_fixture_library(api, name, kind, os.path.join(media_base, path))
    api.request("POST", "/Library/Refresh")
    movies, episodes = wait_for_fixture_media(api, viewer["Id"])
    movie = next(item for item in movies if item["Name"] == "Signal One")
    archive = next(item for item in movies if item["Name"] == "Archive Signal")
    user_id = viewer["Id"]
    api.request("POST", f"/UserItems/{movie['Id']}/UserData", {"PlaybackPositionTicks": 120_000_000, "Played": False}, {"userId": user_id})
    viewer_api = API(api.url, "pictureshow-desktop-fixture-viewer")
    viewer_api.login("desktop-viewer", password)
    viewer_api.fixture_process = api.fixture_process
    viewer_api.fixture_container = api.fixture_container
    return viewer_api, password, {"movieId": movie["Id"], "archiveMovieId": archive["Id"], "episodeIds": [item["Id"] for item in episodes], "userId": user_id}


class MacProcessInfo(ctypes.Structure):
    # Public proc_bsdshortinfo from the macOS SDK sys/proc_info.h. No argv/path.
    _fields_ = [(name, ctypes.c_uint32) for name in ("pid", "ppid", "pgid", "status")]
    _fields_ += [("comm", ctypes.c_char * 16)]
    _fields_ += [(name, ctypes.c_uint32) for name in ("flags", "uid", "gid", "ruid", "rgid", "svuid", "svgid", "reserved")]


@functools.lru_cache(maxsize=1)
def mac_process_library():
    library = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True)
    library.proc_listpids.argtypes = [ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p, ctypes.c_int]
    library.proc_listpids.restype = ctypes.c_int
    library.proc_pidinfo.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_uint64, ctypes.c_void_p, ctypes.c_int]
    library.proc_pidinfo.restype = ctypes.c_int
    return library


def mac_encoder_process_count(server_pid: int) -> int:
    """Walk only the owned PID tree through libproc; never launch ps or read argv."""
    library = mac_process_library()
    pending, seen, count = [server_pid], set(), 0
    while pending:
        parent = pending.pop()
        if parent in seen:
            continue
        seen.add(parent)
        if len(seen) > 4096:
            raise RuntimeError("Owned fixture process tree exceeded observation bound")
        required = library.proc_listpids(6, parent, None, 0)  # PROC_PPID_ONLY; byte count.
        if required < 0:
            raise RuntimeError("Owned fixture child PID enumeration failed")
        for attempt in range(3):
            size = max(32, required // ctypes.sizeof(ctypes.c_int) + 32) * (2 ** attempt)
            children = (ctypes.c_int * size)()
            used = library.proc_listpids(6, parent, children, ctypes.sizeof(children))
            if used < 0:
                raise RuntimeError("Owned fixture child PID enumeration failed")
            if used < ctypes.sizeof(children):
                break
        else:
            raise RuntimeError("Owned fixture child PID enumeration was truncated")
        for pid in children[:used // ctypes.sizeof(ctypes.c_int)]:
            info = MacProcessInfo()
            ctypes.set_errno(0)
            received = library.proc_pidinfo(pid, 13, 0, ctypes.byref(info), ctypes.sizeof(info))
            if received != ctypes.sizeof(info):
                if received == 0 and ctypes.get_errno() in (2, 3):
                    continue  # A short-lived child exited between the two snapshots.
                raise RuntimeError("Owned fixture process identity observation failed")
            if info.pid != pid or info.ppid != parent:
                continue  # PID reuse/reparenting must not attribute another tree.
            pending.append(pid)
            if info.comm.lower() == b"ffmpeg":
                count += 1
    return count


def encoding_process_count(api: API) -> int:
    """Inspect only the owned server tree/container, never command arguments."""
    if api.fixture_container is not None:
        # Docker needs PID in ps output to map/filter the container's processes;
        # comm alone fails in the daemon before returning any rows.
        rows = subprocess.check_output(["docker", "top", api.fixture_container, "-eo", "pid,comm"], text=True, timeout=5)
        return sum(Path(fields[1]).name.lower() in ("ffmpeg", "ffmpeg.exe")
                   for line in rows.splitlines() if len(fields := line.split(None, 1)) == 2)
    server_pid = api.fixture_process.pid
    if platform.system() == "Darwin":
        return mac_encoder_process_count(server_pid)
    if platform.system() == "Windows":
        command = "Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name | ConvertTo-Json -Compress"
        records = json.loads(subprocess.check_output(["pwsh", "-NoProfile", "-Command", command], text=True, timeout=10))
        processes = [(record["ProcessId"], record["ParentProcessId"], record["Name"])
                     for record in records]
    else:
        lines = subprocess.check_output(["ps", "-axo", "pid=,ppid=,comm="], text=True, timeout=5)
        processes = [(int(fields[0]), int(fields[1]), fields[2])
                     for line in lines.splitlines() if len(fields := line.split(None, 2)) == 3]
    descendants = {server_pid}
    while True:
        found = {pid for pid, parent, _ in processes if parent in descendants}
        if found <= descendants:
            break
        descendants |= found
    return sum(pid in descendants and Path(name).name.lower() in ("ffmpeg", "ffmpeg.exe")
               for pid, _, name in processes)


class EncoderObserver:
    """Observe short-lived encoders concurrently with HTTP and app automation.

    A single PowerShell process keeps Windows CIM warm; launching PowerShell for
    each sample can take longer than an entire short encoding. No process command
    arguments are read, and only counts from the owned server tree are retained.
    """
    def __init__(self, api: API):
        self.api = api
        self.samples = []
        self.error = None
        self.stopping = threading.Event()
        self.process = None
        self.thread = None
        self.snapshot_stats = {"backend": "libproc" if platform.system() == "Darwin" and api.fixture_container is None else "process-list", "count": 0, "maxDurationMs": 0.0}

    def start(self):
        if platform.system() == "Windows" and self.api.fixture_container is None:
            server_pid = int(self.api.fixture_process.pid)
            command = f"""
$ErrorActionPreference = 'Stop'
$serverProcessId = {server_pid}
while ($true) {{
  $rows = @(Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name)
  $owned = [System.Collections.Generic.HashSet[uint32]]::new()
  [void]$owned.Add([uint32]$serverProcessId)
  do {{
    $changed = $false
    foreach ($row in $rows) {{
      if ($owned.Contains([uint32]$row.ParentProcessId)) {{
        if ($owned.Add([uint32]$row.ProcessId)) {{ $changed = $true }}
      }}
    }}
  }} while ($changed)
  $count = @($rows | Where-Object {{ $owned.Contains([uint32]$_.ProcessId) -and $_.Name -ieq 'ffmpeg.exe' }}).Count
  [Console]::Out.WriteLine($count)
  [Console]::Out.Flush()
  Start-Sleep -Milliseconds 100
}}
"""
            self.process = subprocess.Popen(["pwsh", "-NoProfile", "-Command", command],
                                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
            target = self._windows
        else:
            target = self._portable
        self.thread = threading.Thread(target=target, name="owned-fixture-encoder-observer")
        self.thread.start()

    def _record(self, count):
        self.samples.append({"observedMonotonic": round(time.monotonic(), 3), "encoderCount": count})

    def _portable(self):
        try:
            while not self.stopping.is_set():
                started = time.monotonic()
                count = encoding_process_count(self.api)
                self.snapshot_stats["count"] += 1
                self.snapshot_stats["maxDurationMs"] = round(max(self.snapshot_stats["maxDurationMs"], (time.monotonic() - started) * 1000), 3)
                self._record(count)
                self.stopping.wait(.1)
        except Exception as error:
            self.error = type(error).__name__

    def _windows(self):
        try:
            for line in self.process.stdout:
                if self.stopping.is_set():
                    break
                self._record(int(line.strip()))
            if not self.stopping.is_set():
                self.error = "Windows process observer ended unexpectedly"
        except Exception as error:
            self.error = type(error).__name__

    def stop(self):
        self.stopping.set()
        if self.process is not None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        if self.thread is not None:
            self.thread.join(timeout=7)
            if self.thread.is_alive():
                self.error = "Owned encoder observer did not stop"


class OwnedDiscoveryResponder:
    """Real Jellyfin UDP protocol, advertising only this fixture over loopback."""
    def __init__(self, address: str, public_info: dict, evidence: Path):
        parsed = urllib.parse.urlparse(address)
        if parsed.scheme != "http" or parsed.hostname != "127.0.0.1":
            raise ValueError("Discovery responder requires the owned loopback fixture")
        if not all(isinstance(public_info.get(k), str) and public_info[k] for k in ("Id", "ServerName")):
            raise ValueError("Discovery responder requires actual public server identity")
        self.reply = {"Address": address, "Id": public_info["Id"], "Name": public_info["ServerName"]}
        self.evidence = evidence
        self.requests = self.replies = self.unexpected = 0
        self.error = None
        self.stopping = threading.Event()
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.bind(("127.0.0.1", 0))
        self.socket.settimeout(.1)
        self.port = self.socket.getsockname()[1]
        self.thread = threading.Thread(target=self._serve, name="owned-loopback-discovery", daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def _serve(self):
        try:
            while not self.stopping.is_set():
                try:
                    payload, peer = self.socket.recvfrom(256)
                except socket.timeout:
                    continue
                if peer[0] != "127.0.0.1" or payload != b"Who is JellyfinServer?":
                    self.unexpected += 1
                    continue
                self.requests += 1
                self.socket.sendto(json.dumps(self.reply).encode("utf-8"), peer)
                self.replies += 1
        except OSError as error:
            if not self.stopping.is_set():
                self.error = type(error).__name__

    def __exit__(self, *_):
        self.stopping.set()
        self.socket.close()
        self.thread.join(timeout=2)
        if self.thread.is_alive():
            self.error = "Discovery responder did not stop"
        write_json(self.evidence / "discovery-responder.json", {
            "requests": self.requests, "replies": self.replies, "unexpectedRequests": self.unexpected,
            "error": self.error, "stopped": not self.thread.is_alive(), "boundAddress": "127.0.0.1",
            "port": self.port, "server": self.reply, "lanBroadcastTested": False})


def validate_discovery(evidence: Path) -> dict:
    try:
        responder = json.loads((evidence / "discovery-responder.json").read_text(encoding="utf-8"))
        app = json.loads((evidence / "discovery-result.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raise RuntimeError("Real UDP discovery evidence missing or malformed") from None
    if not isinstance(responder, dict) or responder.get("error") is not None or responder.get("stopped") is not True:
        raise RuntimeError("Owned UDP discovery responder failed to stop cleanly")
    if not all(type(responder.get(k)) is int and responder[k] > 0 for k in ("requests", "replies")):
        raise RuntimeError("Owned UDP discovery received no actual protocol exchange")
    if not isinstance(app, dict) or app.get("found") is not True:
        raise RuntimeError("Desktop app did not discover the owned server over UDP")
    return {"serverDiscoveredOverLoopbackUdp": True, "lanBroadcastTested": False}


def record_voiceover_checkpoint(evidence: Path, checkpoint: str) -> None:
    if platform.system() != "Darwin":
        return
    path = evidence / "screen-reader-checkpoints.json"
    receipt = json.loads(path.read_text()) if path.exists() else {
        "readerProcessName": "VoiceOver", "claim": "Exact-name process absence at before/after checkpoints only", "checkpoints": {}}
    try:
        query = subprocess.run(["pgrep", "-x", "VoiceOver"], stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, timeout=5)
        observed = {"processAbsent": query.returncode == 1, "queryExitCode": query.returncode}
    except (OSError, subprocess.TimeoutExpired) as error:
        observed = {"processAbsent": False, "queryError": type(error).__name__}
    receipt["checkpoints"][checkpoint] = observed
    write_json(path, receipt)


def validate_voiceover_checkpoints(evidence: Path, checkpoints=("before", "after")) -> dict:
    if platform.system() != "Darwin":
        return {}
    try:
        receipt = json.loads((evidence / "screen-reader-checkpoints.json").read_text())
        checks = receipt["checkpoints"]
        valid = all(checks[name].get("processAbsent") is True and checks[name].get("queryExitCode") == 1 for name in checkpoints)
    except (OSError, json.JSONDecodeError, KeyError, TypeError, AttributeError):
        valid = False
    if not valid:
        raise RuntimeError("Mac screenshot qualification requires VoiceOver process absence at the recorded checkpoints")
    return {"voiceOverProcessAbsentAtCheckpoints": True}


def validate_native_close(evidence: Path, enabled: bool) -> dict:
    if not enabled:
        return {}
    try:
        request = json.loads((evidence / "native-close-request.json").read_text(encoding="utf-8"))
        lifecycle = json.loads((evidence / "close-lifecycle.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raise RuntimeError("Native window close evidence missing or malformed") from None
    if not isinstance(request, dict) or request.get("ready") is not True:
        raise RuntimeError("Native window close did not start with ready playback")
    if not isinstance(lifecycle, dict) or lifecycle.get("source") != "window":
        raise RuntimeError("Native window close was not requested through the window")
    if lifecycle.get("shutdownComplete") is not True:
        raise RuntimeError("Native window close did not complete controller shutdown")
    return {"nativeCloseVerified": True}


def copy_decoder_envelope(playback: Path, evidence: Path):
    source = playback / "decoder-envelope.json"
    if not source.exists():
        return
    with source.open("rb") as stream:
        data = stream.read(256 * 1024 + 1)
    if len(data) > 256 * 1024:
        raise RuntimeError("Native decoder envelope exceeds its evidence bound")
    receipt = json.loads(data)
    codecs = receipt.get("codecs") if isinstance(receipt, dict) else None
    if (not isinstance(receipt, dict) or receipt.get("source") != "actual loaded libmpv decoder-list"
            or not isinstance(codecs, list) or not 1 <= len(codecs) <= 2048
            or not all(isinstance(codec, str) and re.fullmatch(r"[A-Za-z0-9_.+-]{1,80}", codec) for codec in codecs)):
        raise RuntimeError("Native decoder envelope contains unexpected evidence fields")
    # Reconstruct only known metadata; never forward arbitrary additional fields.
    write_json(evidence / "playback" / source.name,
               {"source": "actual loaded libmpv decoder-list", "codecs": sorted(set(codecs))})


def validate_decoder_envelope(evidence: Path) -> dict:
    """Require the native decoder receipt for a successful fixture smoke only."""
    receipt_path = evidence / "playback" / "decoder-envelope.json"
    try:
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raise RuntimeError("Successful native playback is missing its decoder envelope") from None
    codecs = receipt.get("codecs") if isinstance(receipt, dict) else None
    if (not isinstance(receipt, dict)
            or receipt.get("source") != "actual loaded libmpv decoder-list"
            or not isinstance(codecs, list)
            or not all(isinstance(codec, str) for codec in codecs)):
        raise RuntimeError("Successful native playback has an invalid decoder envelope")
    subtitle_decoder = next((codec for codec in codecs if codec.lower() in {"subrip", "srt"}), None)
    if subtitle_decoder is None:
        raise RuntimeError("Successful native playback is missing the fixture subtitle decoder")
    return {"nativeSubtitleDecoderVerified": True, "nativeSubtitleDecoder": subtitle_decoder}


def copy_subtitle_failure_categories(playback: Path, evidence: Path):
    source = playback / "mpv.log"
    limit = 2 * 1024 * 1024
    data = b""
    size = 0
    if source.exists():
        with source.open("rb") as stream:
            size = stream.seek(0, 2)
            stream.seek(max(0, size - limit))
            data = stream.read(limit)
    # Fixed mpv subtitle diagnostics only. No log lines, codec argument values,
    # authenticated URLs or paths enter this receipt, even on a matching line.
    signatures = {
        "decoderNotFound": b"Could not find subtitle decoder for format",
        "converterOpenFailed": b"Could not open libavcodec subtitle converter",
        "parseFailed": b"Error parsing subtitle",
        "decodeFailed": b"Error decoding subtitle",
        "codecChangeFailed": b"Can't change to new codec.",
        "bitmapIgnored": b"Ignoring bitmap subtitle.",
    }
    write_json(evidence / "playback" / "subtitle-failure-categories.json",
               {"sourcePresent": source.exists(), "scannedBytes": len(data),
                "tailTruncated": size > limit,
                "categories": {name: signature in data for name, signature in signatures.items()}})


def run_app(args, viewer: API, password: str, ids: dict, evidence: Path):
    command = [args.java, "--enable-native-access=ALL-UNNAMED"]
    if platform.system() == "Darwin":
        command += ["--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED"]
    command += ["-Dcompose.interop.blending=true", "-Dcompose.accessibility.enable=true", "-cp", str(args.runtime.resolve() / "lib" / "*"), "app.pictureshow.desktop.MainKt", "app-smoke", viewer.url, "desktop-viewer", password, str(evidence.resolve())]
    environment = dict(os.environ)
    environment["PICTURESHOW_DATA_DIR"] = str(args.work.resolve() / "viewer-data")
    environment["SPIKE_NULL_AUDIO"] = "1"
    environment["PICTURESHOW_SMOKE"] = "1"
    raw_log = args.work / "app.log"
    samples, qc_codes = [], set()
    uncaught = []
    observer = EncoderObserver(viewer)
    record_voiceover_checkpoint(evidence, "before")
    validate_voiceover_checkpoints(evidence, ("before",))
    public_info = viewer.request("GET", "/System/Info/Public")
    with OwnedDiscoveryResponder(viewer.url, public_info, evidence) as discovery, raw_log.open("w", encoding="utf-8") as log:
        environment["PICTURESHOW_SMOKE_DISCOVERY_PORT"] = str(discovery.port)
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=environment)
        try:
            observer.start()
            deadline = time.monotonic() + args.timeout
            while process.poll() is None and time.monotonic() < deadline:
                qc_path = evidence / "quick-connect-request.json"
                if qc_path.exists():
                    try:
                        code = str(json.loads(qc_path.read_text(encoding="utf-8"))["code"])
                        if code not in qc_codes:
                            accepted = viewer.request("POST", "/QuickConnect/Authorize", query={"code": code})
                            if accepted is not True:
                                raise RuntimeError("Fixture viewer did not authorize Quick Connect")
                            qc_codes.add(code)
                            write_json(evidence / "quick-connect-approved.json", {"approved": True})
                    except (json.JSONDecodeError, KeyError):
                        pass  # Atomic replacement is preferable; tolerate a writer opening the file.
                try:
                    sessions = viewer.request("GET", "/Sessions")
                    for session in sessions:
                        if session.get("NowPlayingItem") and session.get("UserId") == ids["userId"]:
                            samples.append({"itemId": session["NowPlayingItem"]["Id"], "positionTicks": session.get("PlayState", {}).get("PositionTicks", 0), "isPaused": session.get("PlayState", {}).get("IsPaused"), "playMethod": session.get("PlayState", {}).get("PlayMethod"), "videoDirect": (session.get("TranscodingInfo") or {}).get("IsVideoDirect"), "observedMonotonic": round(time.monotonic(), 3)})
                except RuntimeError:
                    pass
                time.sleep(.25)
            if process.poll() is None:
                raise RuntimeError("Desktop app E2E exceeded its finite smoke timeout")
            if process.returncode != 0:
                raise RuntimeError(f"Desktop app E2E exited {process.returncode}; inspect sanitized app-output.txt")
        finally:
            observer.stop()
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
            # Logs may carry app URL tokens, passwords or native server paths. Keep
            # raw logs local; publish only expected progress and failure messages.
            raw_output = raw_log.read_text(encoding="utf-8", errors="replace")
            (evidence / "app-output.txt").write_text(selected_app_output(
                raw_output, password, viewer.token), encoding="utf-8")
            uncaught = uncaught_app_exceptions(raw_output)
            write_json(evidence / "uncaught-app-exceptions.json", {"exceptions": uncaught})
            record_voiceover_checkpoint(evidence, "after")
            native_receipt = Path(environment["PICTURESHOW_DATA_DIR"]) / "playback" / "native-surface.json"
            if native_receipt.exists():
                # Native metadata only: renderer/format/geometry/counters, no media URL.
                write_json(evidence / "playback" / "native-surface.json", json.loads(native_receipt.read_text(encoding="utf-8")))
            playback = Path(environment["PICTURESHOW_DATA_DIR"]) / "playback"
            copy_decoder_envelope(playback, evidence)
            copy_subtitle_failure_categories(playback, evidence)
            # Keep the independent observations even when the app fails before
            # its receipt. A failure must not discard the only server evidence.
            write_json(evidence / "server-session-observations.json", {"samples": samples,
                                                                      "encoderSamples": observer.samples,
                                                                      "encoderObserverError": observer.error,
                                                                      "encoderSnapshotStats": observer.snapshot_stats,
                                                                      "cleanup": None,
                                                                      "quickConnectApproved": bool(qc_codes)})
    cleanup_deadline = time.monotonic() + 20
    cleanup = {"activePlayingItems": None, "encoderCount": None}
    while time.monotonic() < cleanup_deadline:
        sessions = viewer.request("GET", "/Sessions")
        cleanup = {"activePlayingItems": sum(bool(session.get("NowPlayingItem"))
                                             for session in sessions if session.get("UserId") == ids["userId"]),
                   "encoderCount": encoding_process_count(viewer)}
        if cleanup == {"activePlayingItems": 0, "encoderCount": 0}:
            break
        time.sleep(.5)
    write_json(evidence / "server-session-observations.json", {"samples": samples, "encoderSamples": observer.samples,
                                                              "encoderObserverError": observer.error,
                                                              "encoderSnapshotStats": observer.snapshot_stats,
                                                              "cleanup": cleanup, "quickConnectApproved": bool(qc_codes)})
    receipt_path = evidence / "app-smoke-result.json"
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    if observer.error:
        raise RuntimeError("Owned fixture encoder observation failed: " + observer.error)
    require_no_uncaught_exceptions(uncaught)
    screen_reader = validate_voiceover_checkpoints(evidence)
    result = validate_receipt(receipt, samples, bool(qc_codes), ids, observer.samples, cleanup)
    result.update(validate_discovery(evidence))
    result.update(screen_reader)
    result.update(validate_native_close(evidence, environment.get("PICTURESHOW_SMOKE_NATIVE_CLOSE") == "1"))
    result.update(validate_decoder_envelope(evidence))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, required=True, help="Fresh private work directory, outside evidence")
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, default=Path("desktop/build/spike-runtime"))
    parser.add_argument("--java", default="java")
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--gui-lock", type=Path, help="Optional POSIX GUI coordination lock; no default path")
    parser.add_argument("--wait-gui-seconds", type=float, default=0,
                        help="Finite GUI lock acquisition budget; default 0 fails immediately if busy")
    args = parser.parse_args()
    if not math.isfinite(args.wait_gui_seconds) or args.wait_gui_seconds < 0:
        parser.error("--wait-gui-seconds must be finite and nonnegative")
    if args.gui_lock is not None and os.name != "posix":
        parser.error("--gui-lock requires POSIX flock; omit it on Windows")
    if args.gui_lock is None and args.wait_gui_seconds:
        parser.error("--wait-gui-seconds requires --gui-lock")
    if not args.ffmpeg:
        parser.error("FFmpeg is required to generate the original CC0 fixture")
    if args.work.exists() or args.evidence.exists():
        parser.error("Use fresh work and evidence directories; stale artifacts cannot pass")
    if args.work.resolve().is_relative_to(args.evidence.resolve()):
        parser.error("Private work must be outside uploaded evidence")
    args.work.mkdir(parents=True)
    args.evidence.mkdir(parents=True)
    started = time.monotonic()
    result = {"pass": False, "os": platform.system(), "architecture": platform.machine()}
    try:
        manifest = generate_media(args.work / "media", args.ffmpeg)
        write_json(args.evidence / "media-license.json", manifest)
        with owned_server(args.work / "fixture", args.work / "media", args.ffmpeg) as (admin, media_base, provenance):
            try:
                viewer, password, ids = provision(admin, media_base)
                quality_witness = platform.system() == "Windows" or os.getenv("PICTURESHOW_SMOKE_FORCE_QUALITY") == "1"
                witness = {"reason": "viewer-bitrate-cap" if quality_witness else "unavailable-decoder",
                           "bitrateCapMbps": 4 if quality_witness else None,
                           "conversionNoticeExpected": not quality_witness}
                write_json(args.evidence / "fixture.json", {"version": VERSION, "provenance": provenance, "libraries": admin.fixture_library_receipts, "probeCompletion": admin.fixture_probe_completion, "movieCount": 2, "episodeCount": 2, "conversionMovie": "Archive Signal", "conversionCodec": "ffv1", "conversionWitness": witness, "resumeSeconds": 12, "disposable": True})
                result["conversionWitness"] = witness
                with gui_lease(args.gui_lock, args.wait_gui_seconds):
                    result.update(run_app(args, viewer, password, ids, args.evidence))
                viewer.request("POST", "/Sessions/Logout")
                admin.request("POST", "/Sessions/Logout")
            except Exception as error:
                fixture_diagnostics(admin, args.evidence, error)
                raise
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        result["elapsedSeconds"] = round(time.monotonic() - started, 3)
        result["ownedServerTeardownAttempted"] = True
        write_json(args.evidence / "e2e-result.json", result)


if __name__ == "__main__":
    main()
