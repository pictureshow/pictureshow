#!/bin/sh
# SPDX-License-Identifier: MPL-2.0
# Requires LGPL ffmpeg, libplacebo >=7.387, libass, meson, ninja, pkg-config.
# Usage: sh build-mpv.sh PREFIX SCRATCH (explicit isolated directories)
set -eu
prefix=$1
scratch=$2
mkdir -p "$scratch"
curl -fsSL https://github.com/mpv-player/mpv/archive/refs/tags/v0.42.0.tar.gz -o "$scratch/mpv.tar.gz"
tar -xf "$scratch/mpv.tar.gz" -C "$scratch"
meson setup "$scratch/build" "$scratch/mpv-0.42.0" --prefix="$prefix" --buildtype=release -Dgpl=false -Dlibmpv=true -Dcplayer=false -Dtests=false -Dswift-build=disabled -Dcocoa=disabled -Dvulkan=disabled -Dlua=disabled -Djavascript=disabled -Dmanpage-build=disabled -Dhtml-build=disabled
meson compile -C "$scratch/build" -j2
meson install -C "$scratch/build"
