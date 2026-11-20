#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Stage, relocate, and sign the Mach-O objects in a jpackage macOS image."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import re
import shutil
import struct
import subprocess
import tempfile
import uuid
from zipfile import ZipFile


def run(*args):
    return subprocess.check_output(args, encoding="utf-8")


def sign(path, identity, entitlements=None):
    args = ["codesign", "--force", "--sign", identity, "--options", "runtime"]
    args += ["--timestamp"] if identity != "-" else ["--timestamp=none"]
    if entitlements:
        args += ["--entitlements", str(entitlements)]
    run(*args, str(path))


def mach_kind(header):
    magic = header[:4]
    if magic in {b"\xce\xfa\xed\xfe", b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xce", b"\xfe\xed\xfa\xcf"}:
        return "thin"
    if magic in {b"\xca\xfe\xba\xbe", b"\xca\xfe\xba\xbf", b"\xbe\xba\xfe\xca", b"\xbf\xba\xfe\xca"} and len(header) >= 8:
        order = "big" if magic[:2] == b"\xca\xfe" else "little"
        # Java class files share the fat magic but the next four bytes are a class version.
        if 0 < int.from_bytes(header[4:8], order) <= 32:
            return "fat"
    return None


def is_mach(path):
    with path.open("rb") as file:
        return mach_kind(file.read(8)) is not None


def set_launcher_uuid(launcher, bundle_id, receipt):
    data = bytearray(launcher.read_bytes())
    if data[:4] != b"\xcf\xfa\xed\xfe":
        raise ValueError("Expected the stock, little-endian, single-architecture jpackage launcher")
    stock = run("dwarfdump", "--uuid", str(launcher))
    count = struct.unpack_from("<I", data, 16)[0]
    offset = 32
    offsets = []
    for _ in range(count):
        command, size = struct.unpack_from("<II", data, offset)
        if size < 8 or offset + size > len(data):
            raise ValueError("Invalid jpackage launcher load command")
        if command == 0x1B and size == 24:
            offsets.append(offset + 8)
        offset += size
    if len(offsets) != 1:
        raise ValueError("Expected exactly one LC_UUID in jpackage launcher")
    start = offsets[0]
    original = uuid.UUID(bytes=bytes(data[start:start + 16]))
    replacement = uuid.uuid5(uuid.NAMESPACE_URL, f"macos:{bundle_id}:{original}")
    data[start:start + 16] = replacement.bytes
    launcher.write_bytes(data)
    patched = run("dwarfdump", "--uuid", str(launcher))
    if str(replacement).upper() not in patched.upper() or patched == stock:
        raise ValueError("dwarfdump did not confirm the replacement launch UUID")
    receipt.write_text(f"Stock {stock.split(' (')[0]}\nPictureshow {patched.split(' (')[0]}\n")


def load_commands(path):
    output = run("otool", "-arch", "all", "-l", str(path))
    commands = dict()
    kind = None
    for line in output.splitlines():
        match = re.fullmatch(r"\s+cmd (LC_\w+)", line)
        if match:
            kind = match[1]
        match = re.fullmatch(r"\s+(?:name|path) (.+) \(offset \d+\)", line)
        if match and kind:
            commands.setdefault(kind, set()).add(match[1])
    return commands


DEPENDENCIES = {"LC_LOAD_DYLIB", "LC_LOAD_WEAK_DYLIB", "LC_REEXPORT_DYLIB", "LC_LAZY_LOAD_DYLIB", "LC_LOAD_UPWARD_DYLIB"}


def system_path(path):
    return path.startswith(("/usr/lib/", "/System/"))


def stage_player(native, destination):
    prefix = native / "prefix/lib"
    destination.mkdir()
    pending = [prefix / "libmpv.dylib", native / "libpictureshow_layer.dylib", native / "libpictureshow_window.dylib"]
    staged = {}
    while pending:
        source = pending.pop().resolve(strict=True)
        if source in staged:
            continue
        commands = load_commands(source)
        name, = {Path(install_id).name for install_id in commands["LC_ID_DYLIB"]}
        installed = destination / name
        if installed.exists():
            raise ValueError(f"Duplicate player install name {name}")
        shutil.copy2(source, installed)
        installed.chmod(0o755)
        staged[source] = installed
        args = ["-id", f"@rpath/{name}"]
        for rpath in sorted(commands.get("LC_RPATH", ())):
            if rpath.startswith("/"):
                args += ["-delete_rpath", rpath]
        for kind in sorted(DEPENDENCIES & commands.keys()):
            for dependency in sorted(commands[kind]):
                if system_path(dependency):
                    continue
                candidate = (prefix / Path(dependency).name).resolve(strict=True)
                if candidate.parent != prefix.resolve():
                    raise ValueError(f"Unpinned player dependency {dependency}")
                pending.append(candidate)
                candidate_ids = load_commands(candidate)["LC_ID_DYLIB"]
                basename, = {Path(install_id).name for install_id in candidate_ids}
                args += ["-change", dependency, f"@loader_path/{basename}"]
        run("install_name_tool", *args, str(installed))
    mpv = staged[(prefix / "libmpv.dylib").resolve()]
    if mpv.name != "libmpv.dylib":
        (destination / "libmpv.dylib").symlink_to(mpv.name)
    return sorted(path.name for path in staged.values())


def audit_mach(path, bundle, origins, output):
    commands = load_commands(path)
    for kind in DEPENDENCIES | {"LC_ID_DYLIB", "LC_RPATH", "LC_LOAD_DYLINKER"}:
        for name in commands.get(kind, ()):
            if not system_path(name) and not name.startswith(("@rpath/", "@loader_path/", "@executable_path/")):
                raise ValueError(f"{origins}: {kind} contains external path {name}")
    for kind in DEPENDENCIES:
        for name in commands.get(kind, ()):
            if name.startswith("@loader_path/") and not (path.parent / name.removeprefix("@loader_path/")).is_file():
                raise ValueError(f"{origins}: missing {name}")
            if name.startswith("@executable_path/") and not (bundle / "Contents/MacOS" / name.removeprefix("@executable_path/")).is_file():
                raise ValueError(f"{origins}: missing {name}")
            if name.startswith("@rpath/"):
                relative = name.removeprefix("@rpath/")
                paths = [
                    path.parent / rpath.removeprefix("@loader_path/")
                    for rpath in commands.get("LC_RPATH", ()) if rpath.startswith("@loader_path/")
                ]
                paths += [
                    bundle / "Contents/MacOS" / rpath.removeprefix("@executable_path/")
                    for rpath in commands.get("LC_RPATH", ()) if rpath.startswith("@executable_path/")
                ]
                if not any((directory / relative).is_file() for directory in paths):
                    raise ValueError(f"{origins}: could not resolve {name} from its runpath")
    output.append(f"{origins}:\n" + "\n".join(run("otool", "-arch", "all", "-L", str(path)).splitlines()[1:]))


def foreign_native(header, name):
    return header.startswith((b"\x7fELF", b"MZ", b"!<arch>\n")) and name.lower().endswith(
        (".dll", ".so", ".a", ".jnilib", ".exe")
    )


def signed_jar(jar, work, identity, bundle, output):
    archive_name = str(jar.relative_to(bundle))
    with ZipFile(jar) as archive:
        changes = {}
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            with archive.open(entry) as stream:
                header = stream.read(8)
            if foreign_native(header, entry.filename):
                changes[entry.filename] = None
            elif mach_kind(header):
                native = work / Path(entry.filename).name
                native.write_bytes(archive.read(entry))
                if subprocess.run(["lipo", "-verify_arch", run("uname", "-m").strip(), str(native)],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
                    changes[entry.filename] = None
                    if f"{entry.filename}.sha256" in archive.namelist():
                        changes[f"{entry.filename}.sha256"] = None
                    native.unlink()
                    continue
                audit_mach(native, bundle, f"{archive_name}!/{entry.filename}", output)
                sign(native, identity)
                run("codesign", "--verify", "--strict", str(native))
                signed = native.read_bytes()
                changes[entry.filename] = signed
                checksum = f"{entry.filename}.sha256"
                if checksum in archive.namelist():
                    changes[checksum] = hashlib.sha256(signed).hexdigest().encode("ascii")
                native.unlink()
        if not changes:
            return
        staged = work / jar.name
        with ZipFile(staged, "w") as replacement:
            for entry in archive.infolist():
                if entry.filename in changes:
                    data = changes[entry.filename]
                elif re.fullmatch(r"META-INF/(?:[^/]+\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)", entry.filename, re.IGNORECASE):
                    continue
                else:
                    data = archive.read(entry)
                if data is not None:
                    replacement.writestr(entry, data)
    shutil.move(staged, jar)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("native", type=Path)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--identity", required=True)
    parser.add_argument("--entitlements", required=True, type=Path)
    parser.add_argument("--build", required=True)
    parser.add_argument("--store-bridge", type=Path)
    args = parser.parse_args()
    contents = args.app / "Contents"
    plist = contents / "Info.plist"
    info = plistlib.loads(plist.read_bytes())
    info.update(
        CFBundleVersion=args.build,
        LSApplicationCategoryType="public.app-category.entertainment",
        LSMinimumSystemVersion="15.0",
        NSLocalNetworkUsageDescription="Pictureshow uses your local network to find and connect to your Jellyfin server.",
        NSHumanReadableCopyright="Copyright © Legitimate LLC.",
    )
    plist.write_bytes(plistlib.dumps(info))
    launcher = contents / "MacOS" / info["CFBundleExecutable"]
    set_launcher_uuid(launcher, info["CFBundleIdentifier"], args.evidence / "launcher-uuid.txt")
    players = stage_player(args.native, contents / "Frameworks")
    if args.store_bridge:
        shutil.copy2(args.store_bridge, contents / "Frameworks" / args.store_bridge.name)
    (args.evidence / "player-libraries.json").write_text(json.dumps(players, indent=2) + "\n")
    output = []
    with tempfile.TemporaryDirectory(prefix="pictureshow-sign-jar-") as folder:
        for jar in sorted((contents / "app").glob("*.jar")):
            signed_jar(jar, Path(folder), args.identity, args.app, output)
    for path in sorted(contents.rglob("*")):
        if path.is_file() and not path.is_symlink() and is_mach(path):
            audit_mach(path, args.app, str(path.relative_to(args.app)), output)
            if path != launcher:
                sign(path, args.identity)
    (args.evidence / "otool-dependencies.txt").write_text("\n\n".join(output) + "\n")
    sign(contents / "runtime", args.identity)
    sign(args.app, args.identity, args.entitlements)
    run("codesign", "--verify", "--deep", "--strict", str(args.app))


if __name__ == "__main__":
    main()
