#!/bin/sh
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
# Requires architecture-matched shared LGPL FFmpeg, libass, libplacebo, and
# meson/ninja/pkg-config. Build tools are not application runtime dependencies.
# Example: sh build-mpv.sh /path/without-spaces/prefix /path/without-spaces/scratch
set -eu
prefix=$1
scratch=$2
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mkdir -p "$scratch"
curl -fsSL https://github.com/mpv-player/mpv/archive/refs/tags/v0.42.0.tar.gz -o "$scratch/mpv.tar.gz"
tar -xf "$scratch/mpv.tar.gz" -C "$scratch"
patch -d "$scratch/mpv-0.42.0" -p1 < "$script_dir/render-only-videotoolbox.patch"
export PKG_CONFIG_PATH="$prefix/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
meson setup "$scratch/build" "$scratch/mpv-0.42.0" --prefix="$prefix" \
  --auto-features=disabled --buildtype=release -Dgpl=false -Dlibmpv=true \
  -Dcplayer=false -Dgl=enabled -Dplain-gl=enabled -Dvideotoolbox-gl=enabled \
  -Dcoreaudio=enabled -Dvulkan=disabled -Dswift-build=disabled -Ddefault_library=shared
meson compile -C "$scratch/build" -j2
meson install -C "$scratch/build"
