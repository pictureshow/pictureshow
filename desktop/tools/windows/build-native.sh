#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
set -euo pipefail
source desktop/tools/packaging/common.sh
build=$1
prefix="$build/prefix"
pinned="$build/ucrt64"
mkdir -p "$build/source" "$prefix" "$pinned" "$build/deps"
while IFS=$'\t' read -r filename digest _; do
  fetch_pin "$filename" "$digest" "https://repo.msys2.org/mingw/ucrt64/$filename" "$build/source"
  unpacked="$pinned/${filename%.pkg.tar.zst}"
  mkdir -p "$unpacked"
  tar -xf "$build/source/$filename" -C "$unpacked"
  cp -a "$unpacked/ucrt64/." "$build/deps/"
done < desktop/tools/windows/ucrt64-sources.tsv

# pkgconf relocates the UCRT64 prefix from the location of each .pc file.
# The compiler uses MSYS2, and media inputs take precedence over its defaults.
export PKG_CONFIG_LIBDIR="$(cygpath -m "$prefix")/lib/pkgconfig;$(cygpath -m "$build/deps")/lib/pkgconfig"
unset PKG_CONFIG_PATH
# Meson sees MSVC too on the hosted runner. Use the UCRT compiler that
# matches the pinned imports. PowerShell separately compiles the adapters.
export CC=gcc CXX=g++ AR=gcc-ar
ffmpeg_and_dav1d "$build" "$prefix" windows
build_mpv "$build" "$prefix" -Dshaderc=enabled -Dspirv-cross=enabled -Dd3d11=enabled \
  -Dwasapi=enabled -Dgl=disabled -Dwin32-threads=enabled
