#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
set -euo pipefail

build=${1:?Expected a native build directory}
prefix="$build/prefix"
source_dir="$build/source"
tools=$(cd "$(dirname "$0")" && pwd -P)
export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig:$prefix/share/pkgconfig"
unset PKG_CONFIG_PATH
export MACOSX_DEPLOYMENT_TARGET=15.0

while IFS=$'\t' read -r folder archive sha url; do
  if [[ ! -f "$source_dir/$archive" ]]; then
    curl -fsSL --retry 2 "$url" -o "$source_dir/$archive"
  fi
  printf '%s  %s\n' "$sha" "$source_dir/$archive" | shasum -a 256 -c
  tar -xf "$source_dir/$archive" -C "$source_dir"
done < "$tools/render-sources.tsv"

meson_install() {
  local source=$1
  shift
  meson setup "$build/$source-build" "$source_dir/$source" --prefix="$prefix" --libdir=lib \
    --auto-features=disabled --wrap-mode=nodownload --buildtype=release -Ddefault_library=shared "$@"
  meson compile -C "$build/$source-build" -j2
  meson install -C "$build/$source-build"
}

meson_install freetype-2.14.1 -Dharfbuzz=disabled -Dpng=disabled
meson_install fribidi-1.0.16 -Dbin=false -Ddocs=false -Dtests=false
meson_install harfbuzz-12.3.2 -Dfreetype=enabled -Dtests=disabled -Dutilities=disabled
(
  cd "$source_dir/libunibreak-6.1"
  ./configure --prefix="$prefix" --libdir="$prefix/lib" --disable-static --enable-shared
  make -j2
  make install
)
meson_install libass-0.17.4 -Dcoretext=enabled -Dlibunibreak=enabled
mv "$source_dir/glad-73db193f853e2ee079bf3ca8a64aa2eaf6459043/glad" "$source_dir/libplacebo-7.387.0/3rdparty/glad/"
meson_install libplacebo-7.387.0 -Ddemos=false -Dopengl=enabled
