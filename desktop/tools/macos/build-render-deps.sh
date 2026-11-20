#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
set -euo pipefail

build=${1:?Expected a native build directory}
prefix="$build/prefix"
source_dir="$build/source"
system=${2:-macos}
tools=$(cd "$(dirname "$0")" && pwd -P)
export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig:$prefix/share/pkgconfig"
unset PKG_CONFIG_PATH PYTHONPATH
case "$system" in
  macos)
    export MACOSX_DEPLOYMENT_TARGET=15.0
    font_backend=-Dcoretext=enabled
    ;;
  linux)
    export PKG_CONFIG_LIBDIR="$PKG_CONFIG_LIBDIR:/usr/lib/$(cc -print-multiarch)/pkgconfig:/usr/lib/pkgconfig:/usr/share/pkgconfig"
    font_backend=-Dfontconfig=enabled
    ;;
  *) echo "Unknown render dependency platform: $system" >&2; exit 2 ;;
esac

while IFS=$'\t' read -r folder archive sha url; do
  if [[ ! -f "$source_dir/$archive" ]]; then
    curl -fsSL --retry 2 "$url" -o "$source_dir/$archive"
  fi
  printf '%s  %s\n' "$sha" "$source_dir/$archive" | shasum -a 256 -c
  tar -xf "$source_dir/$archive" -C "$source_dir"
  case "$archive" in
    glad.tar.gz|jinja.tar.gz|markupsafe.tar.gz|fast_float.tar.gz|Vulkan-Headers.tar.gz)
      # Match libplacebo's upstream submodule layout for shader tools and C++ headers.
      cp -R "$source_dir/$folder/." "$source_dir/libplacebo-7.387.0/3rdparty/${archive%.tar.gz}/"
      ;;
  esac
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
meson_install libass-0.17.4 "$font_backend" -Dlibunibreak=enabled
meson_install libplacebo-7.387.0 -Ddemos=false -Dopengl=enabled
