#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Stage the media-library closure and validate the binaries actually loaded."""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


FFMPEG = ("avcodec", "avfilter", "avformat", "avutil", "swresample", "swscale")
# These form the Linux desktop platform interface. The package owns the pinned
# media libraries. Mesa, Wayland, fonts, audio and libc follow the host/runtime.
LINUX_PLATFORM = re.compile(
    r"(?:ld-linux-x86-64|lib(?:c|dl|m|mvec|pthread|rt|gcc_s|stdc\+\+|resolv|"
    r"EGL|GL|GLX|GLdispatch|OpenGL|wayland-client|wayland-egl|"
    r"X11|Xau|Xdmcp|Xext|xcb|bsd|md|drm|gbm|"
    r"asound|pulse(?:common|-simple)?(?:-[0-9.]+)?|sndfile|FLAC|vorbis(?:enc)?|opus|ogg|"
    r"dbus-1|systemd|asyncns|cap|gcrypt|gpg-error|lz4|lzma|zstd|"
    r"fontconfig|freetype|expat|png16|brotlidec|brotlicommon|z|"
    r"gnutls|p11-kit|idn2|unistring|tasn1|nettle|hogweed|gmp|ffi))\.so(?:\.[0-9]+)*"
)


def pe_imports(path):
    output = subprocess.check_output(["objdump", "-p", path], text=True)
    return re.findall(r"DLL Name:\s*(\S+)", output)


def elf_imports(path):
    output = subprocess.check_output(["readelf", "-d", path], text=True)
    return re.findall(r"\(NEEDED\).*?\[([^]]+)\]", output)


def digest(path):
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


def stage(windows, prefix, supplemental, binary, target, evidence):
    glob = "*.dll" if windows else "*.so*"
    roots = [*prefix.glob(glob)]
    index = {item.name.lower(): item for item in roots}
    origins = {}
    if supplemental:
        for archive in sorted(supplemental.iterdir()):
            for item in (archive / "ucrt64/bin").glob(glob):
                index.setdefault(item.name.lower(), item)
                origins[item.name.lower()] = archive
    index.update((item.name.lower(), item) for item in binary)
    mpv_name = "libmpv-2.dll" if windows else "libmpv.so.2"
    queue = [index[mpv_name], *binary]
    system = set()
    if windows:
        system = {item.name.lower() for item in (Path(os.environ["SystemRoot"]) / "System32").glob("*.dll")}
    imports = pe_imports if windows else elf_imports
    graph = {}
    packages = set()
    target.mkdir(parents=True, exist_ok=True)
    while queue:
        path = queue.pop()
        name = path.name.lower()
        if name in graph:
            continue
        dependencies = imports(path)
        staged = target / path.name
        shutil.copy2(path, staged)
        if not windows:
            subprocess.run(["patchelf", "--set-rpath", "$ORIGIN", staged], check=True)
        graph[name] = {"imports": dependencies, "sha256": digest(staged)}
        if name in origins:
            archive = origins[name]
            packages.add(archive)
            graph[name]["package"] = archive.name
        for dependency in dependencies:
            key = dependency.lower()
            if key in index:
                queue.append(index[key])
            elif not (key in system or (windows and key.startswith(("api-ms-win-", "ext-ms-win-"))) or
                      (not windows and LINUX_PLATFORM.fullmatch(dependency))):
                raise SystemExit(f"{path.name}: dependency missing from the bundle and platform list: {dependency}")
    for library in (*FFMPEG, "libass", "libplacebo"):
        if not any(library in item.lower() for item in graph[mpv_name]["imports"]):
            raise SystemExit(f"{mpv_name} must import {library} as a separate library")
    for archive in sorted(packages):
        destination = evidence / "UCRT64" / archive.name
        destination.mkdir(parents=True)
        for name in (".PKGINFO", ".BUILDINFO"):
            shutil.copy2(archive / name, destination / name.removeprefix("."))
        notices = archive / "ucrt64/share/licenses"
        if notices.is_dir():
            for notice in notices.iterdir():
                shutil.copytree(notice, destination / notice.name, dirs_exist_ok=True)
        elif not (archive.name.startswith("mingw-w64-ucrt-x86_64-libass-0.17.5-") and
                  (evidence / "libass-0.17.5-COPYING").is_file()):
            raise SystemExit(f"No license files provided for {archive.name}")
    (evidence / "library-imports.json").write_text(json.dumps(graph, indent=2) + "\n")


def verify(target, evidence):
    options = {option["name"]: option["value"] for option in
               json.loads((evidence / "mpv-buildoptions.json").read_text())}
    required = {"gpl": False, "libmpv": True, "default_library": "shared", "prefer_static": False}
    for name, value in required.items():
        if options.get(name) != value:
            raise SystemExit(f"libmpv must be built with {name}={value!r}")
    windows = os.name == "nt"
    # Keep the Windows directory cookie alive for the lifetime of the loads.
    dll_search = os.add_dll_directory(str(target)) if windows else None
    result = {}
    for symbol in FFMPEG:
        pattern = f"{symbol}-*.dll" if windows else f"lib{symbol}.so.*"
        (path,) = target.glob(pattern)
        library = ctypes.CDLL(str(path))
        details = {}
        for name in ("license", "configuration"):
            function = getattr(library, f"{symbol}_{name}")
            function.restype = ctypes.c_char_p
            details[name] = function().decode()
        config = details["configuration"].split()
        if (not details["license"].startswith("LGPL version 2.1") or
                not {"--enable-shared", "--disable-static", "--disable-gpl", "--disable-nonfree"}.issubset(config) or
                {"--enable-gpl", "--enable-nonfree", "--enable-version3"}.intersection(config)):
            raise SystemExit(f"{path.name}: unexpected license/configuration: {details}")
        result[path.name] = details
    ctypes.CDLL(str(target / ("libmpv-2.dll" if windows else "libmpv.so.2")))
    if dll_search:
        dll_search.close()
    (evidence / "ffmpeg-runtime-config.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"Verified {len(result)} shared FFmpeg libraries report LGPL 2.1 or later")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--prefix", type=Path)
    parser.add_argument("--supplemental", type=Path)
    parser.add_argument("--binary", type=Path, action="append", default=[])
    args = parser.parse_args()
    if args.prefix:
        stage(os.name == "nt", args.prefix, args.supplemental, args.binary, args.target, args.evidence)
    else:
        verify(args.target.resolve(), args.evidence)


if __name__ == "__main__":
    main()
