#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail
mkdir -p native/source desktop-evidence/licensing
ffmpeg_name=ffmpeg-n8.1.3-34-ga35157abb9-win64-lgpl-shared-8.1
curl -fL --retry 2 "https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/autobuild-2026-11-10-12-39/$ffmpeg_name.zip" -o native/ffmpeg.zip
printf '%s  %s\n' 3045c2213ed8cbb05edf0d5a631b4490061de3b7567ec145644156e7c98da2c6 native/ffmpeg.zip | sha256sum -c -
unzip -q native/ffmpeg.zip -d native/source
ffmpeg_prefix="$PWD/native/source/$ffmpeg_name"
export PATH="$ffmpeg_prefix/bin:$PATH"
export PKG_CONFIG_PATH="$ffmpeg_prefix/lib/pkgconfig:/ucrt64/lib/pkgconfig"
# This package must never enter the runtime through automatic dependency selection.
if pacman -Q mingw-w64-ucrt-x86_64-ffmpeg; then echo 'GPL MSYS FFmpeg is installed' >&2; exit 1; fi
curl -fL --retry 2 https://github.com/mpv-player/mpv/archive/refs/tags/v0.42.0.tar.gz -o native/mpv.tar.gz
printf '%s  %s\n' bcfebbfa2b65a6d88dc97ea18117831072373e38b5785a2afb874ed4df5070dd native/mpv.tar.gz | sha256sum -c -
tar xf native/mpv.tar.gz -C native/source
meson setup native/mpv-build native/source/mpv-0.42.0 --prefix="$PWD/native/install" \
  --buildtype=release --default-library=shared --prefer-static=false --auto-features=disabled \
  -Dgpl=false -Dlibmpv=true -Dcplayer=true -Dlua=disabled -Dd3d11=enabled -Dwasapi=enabled \
  -Dwin32-threads=enabled -Dwin32-subsystem=console -Dmanpage-build=disabled
meson compile -C native/mpv-build -j 4
meson install -C native/mpv-build
cp native/mpv-build/meson-logs/meson-log.txt desktop-evidence/licensing/
meson introspect native/mpv-build --buildoptions > desktop-evidence/licensing/mpv-build-options.json
meson introspect native/mpv-build --dependencies > desktop-evidence/licensing/mpv-build-dependencies.json
pacman -Q > desktop-evidence/licensing/msys-packages.txt
python desktop/tools/windows-shared/closure.py "$ffmpeg_prefix"
