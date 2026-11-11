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
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import socket
import subprocess
import tarfile
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


def sanitized(text: str, *private_values: str) -> str:
    for value in private_values:
        if value:
            text = text.replace(value, "<redacted>")
    text = re.sub(r'https?://[^\s\]"<>]+', "<server-url>", text)
    text = re.sub(r'(?i)(accessToken|api_key|token|password|secret)(["\s]*[=:]["\s]*)[^\s,;"}]+', r'\1\2<redacted>', text)
    text = re.sub(r'[A-Za-z]:[\\/][^\s"<>]+', "<server-path>", text)
    text = re.sub(r'/(?:Users|home|private|tmp|var|media|a)/[^\s"<>]+', "<server-path>", text)
    return text


def fixture_diagnostics(api: "API", evidence: Path, error: Exception) -> None:
    """Publish enough to diagnose a probe failure, without raw logs or tokens."""
    result = {"errorType": type(error).__name__, "error": sanitized(str(error), api.token)}
    try:
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
    # FFV1 is deliberately absent from desktopMpvProfile. Jellyfin must really
    # convert this original signal to H.264; changing a UI flag cannot satisfy it.
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


@contextlib.contextmanager
def owned_server(root: Path, media: Path, ffmpeg: str):
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
            # Explicit port, no automatic port mapping or LAN discovery traffic.
            (root / "config" / "network.xml").write_text(f"<?xml version=\"1.0\"?><NetworkConfiguration><BaseUrl/><EnableHttps>false</EnableHttps><InternalHttpPort>{port}</InternalHttpPort><PublicHttpPort>{port}</PublicHttpPort><EnableUPnP>false</EnableUPnP><EnableRemoteAccess>false</EnableRemoteAccess><EnableIPv4>true</EnableIPv4><EnableIPv6>false</EnableIPv6><LocalNetworkAddresses><string>127.0.0.1</string></LocalNetworkAddresses></NetworkConfiguration>", encoding="utf-8")
            # Fatal avoids request/query tokens in retained logs.
            write_json(root / "config" / "logging.json", {"Serilog": {"MinimumLevel": {"Default": "Warning", "Override": {"Microsoft": "Warning", "System": "Warning"}}}})
            server_log = (root / "server.log").open("w", encoding="utf-8")
            # Windows' official archive includes a matched real FFmpeg/FFprobe
            # pair. A Chocolatey shim can obscure the sibling FFprobe location.
            server_ffmpeg = str(exe.with_name("ffmpeg.exe")) if platform.system() == "Windows" else ffmpeg
            command = [str(exe), "--nowebclient", "--nonetchange", "--datadir", str(root / "data"), "--cachedir", str(root / "cache"), "--configdir", str(root / "config"), "--logdir", str(root / "logs"), "--ffmpeg", server_ffmpeg]
            process = subprocess.Popen(command, stdout=server_log, stderr=subprocess.STDOUT)
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


def provision(api: API, media_base: str):
    admin_password, password = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
    api.request("GET", "/Startup/Configuration")
    api.request("POST", "/Startup/Configuration", {"UICulture": "en-US", "MetadataCountryCode": "US", "PreferredMetadataLanguage": "en"})
    api.request("GET", "/Startup/User")
    api.request("POST", "/Startup/User", {"Name": "fixture-admin", "Password": admin_password})
    api.request("POST", "/Startup/RemoteAccess", {"EnableRemoteAccess": False, "EnableAutomaticPortMapping": False})
    api.request("POST", "/Startup/Complete")
    api.login("fixture-admin", admin_password)
    encoding = api.request("GET", "/System/Configuration/encoding")
    # Keep a short conversion observable while the player consumes it, rather
    # than letting FFmpeg finish the entire signal before the next process poll.
    encoding.update({"EnableThrottling": True, "ThrottleDelaySeconds": 5})
    api.request("POST", "/System/Configuration/encoding", encoding)
    viewer = api.request("POST", "/Users/New", {"Name": "desktop-viewer", "Password": password})
    for name, kind, path in [("Movies", "movies", "movies"), ("Shows", "tvshows", "shows")]:
        api.request("POST", "/Library/VirtualFolders", {"LibraryOptions": {"EnableRealtimeMonitor": False, "EnableInternetProviders": False, "PathInfos": [{"Path": media_base + "/" + path}], "MetadataSavers": [], "TypeOptions": [{"Type": item_type, "MetadataFetchers": [], "ImageFetchers": []} for item_type in ["Movie", "Series", "Season", "Episode"]]}}, {"name": name, "collectionType": kind, "refreshLibrary": "false"})
    api.request("POST", "/Library/Refresh")
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        catalog = api.request("GET", "/Items", query={"Recursive": "true", "IncludeItemTypes": "Movie,Episode", "Fields": "MediaSources,MediaStreams", "UserId": viewer["Id"]})
        items = catalog["Items"]
        movies = [item for item in items if item["Type"] == "Movie" and item.get("MediaSources")]
        episodes = [item for item in items if item["Type"] == "Episode" and item.get("MediaSources")]
        if len(movies) == 2 and len(episodes) == 2:
            break
        time.sleep(1)
    else:
        raise RuntimeError("Generated two movies and two episodes did not complete media probing")
    movie = next(item for item in movies if item["Name"] == "Signal One")
    archive = next(item for item in movies if item["Name"] == "Archive Signal")
    if not any(stream.get("Codec") == "ffv1" and stream.get("Type") == "Video"
               for source in archive["MediaSources"] for stream in source.get("MediaStreams", [])):
        raise RuntimeError("Archive Signal was not probed as the intended FFV1 conversion fixture")
    user_id = viewer["Id"]
    api.request("POST", f"/UserItems/{movie['Id']}/UserData", {"PlaybackPositionTicks": 120_000_000, "Played": False}, {"userId": user_id})
    viewer_api = API(api.url, "pictureshow-desktop-fixture-viewer")
    viewer_api.login("desktop-viewer", password)
    viewer_api.fixture_process = api.fixture_process
    viewer_api.fixture_container = api.fixture_container
    return viewer_api, password, {"movieId": movie["Id"], "archiveMovieId": archive["Id"], "episodeIds": [item["Id"] for item in sorted(episodes, key=lambda item: item.get("IndexNumber", 0))], "userId": user_id}


def encoding_process_count(api: API) -> int:
    """Inspect only the owned server tree/container, never command arguments."""
    if api.fixture_container is not None:
        names = subprocess.check_output(["docker", "top", api.fixture_container, "-eo", "comm"], text=True)
        return sum(Path(line.strip()).name.lower() in ("ffmpeg", "ffmpeg.exe")
                   for line in names.splitlines())
    server_pid = api.fixture_process.pid
    if platform.system() == "Windows":
        command = "Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name | ConvertTo-Json -Compress"
        records = json.loads(subprocess.check_output(["pwsh", "-NoProfile", "-Command", command], text=True))
        processes = [(record["ProcessId"], record["ParentProcessId"], record["Name"])
                     for record in records]
    else:
        lines = subprocess.check_output(["ps", "-axo", "pid=,ppid=,comm="], text=True)
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


def run_app(args, viewer: API, password: str, ids: dict, evidence: Path):
    command = [args.java, "--enable-native-access=ALL-UNNAMED"]
    if platform.system() == "Darwin":
        command += ["--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED"]
    command += ["-Dcompose.interop.blending=true", "-cp", str(args.runtime.resolve() / "lib" / "*"), "app.pictureshow.desktop.MainKt", "app-smoke", viewer.url, "desktop-viewer", password, str(evidence.resolve())]
    environment = dict(os.environ)
    environment["PICTURESHOW_DATA_DIR"] = str(args.work.resolve() / "viewer-data")
    environment["SPIKE_NULL_AUDIO"] = "1"
    environment["PICTURESHOW_SMOKE"] = "1"
    raw_log = args.work / "app.log"
    samples, encoder_samples, qc_codes = [], [], set()
    next_encoder_sample = 0.0
    with raw_log.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=environment)
        try:
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
                if time.monotonic() >= next_encoder_sample:
                    encoder_samples.append({"observedMonotonic": round(time.monotonic(), 3),
                                            "encoderCount": encoding_process_count(viewer)})
                    next_encoder_sample = time.monotonic() + 1
                time.sleep(.25)
            if process.poll() is None:
                raise RuntimeError("Desktop app E2E exceeded its finite smoke timeout")
            if process.returncode != 0:
                raise RuntimeError(f"Desktop app E2E exited {process.returncode}; inspect sanitized app-output.txt")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
            # Logs may carry app URL tokens, passwords or native server paths. Keep
            # raw logs local; publish only expected progress and failure messages.
            lines = []
            for line in raw_log.read_text(encoding="utf-8", errors="replace").splitlines():
                if line.startswith(("SMOKE ", "Exception in thread", "Caused by:", "java.lang.", "kotlin.")):
                    line = sanitized(line, password, viewer.token)
                    lines.append(line)
            (evidence / "app-output.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
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
    write_json(evidence / "server-session-observations.json", {"samples": samples, "encoderSamples": encoder_samples,
                                                              "cleanup": cleanup, "quickConnectApproved": bool(qc_codes)})
    receipt_path = evidence / "app-smoke-result.json"
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    return validate_receipt(receipt, samples, bool(qc_codes), ids, encoder_samples, cleanup)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, required=True, help="Fresh private work directory, outside evidence")
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, default=Path("desktop/build/spike-runtime"))
    parser.add_argument("--java", default="java")
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    parser.add_argument("--timeout", type=int, default=240)
    args = parser.parse_args()
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
                write_json(args.evidence / "fixture.json", {"version": VERSION, "provenance": provenance, "movieCount": 2, "episodeCount": 2, "conversionMovie": "Archive Signal", "conversionCodec": "ffv1", "resumeSeconds": 12, "disposable": True})
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
