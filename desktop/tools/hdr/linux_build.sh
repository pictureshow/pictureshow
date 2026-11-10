#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
# Ubuntu 24.04 runner only; packages installed by workflow. No host GPU required.
set -euo pipefail
root=$(pwd)
mkdir -p desktop/build/hdr-linux/source desktop-evidence/hdr-linux
build="$root/desktop/build/hdr-linux"
prefix="$build/prefix"
mkdir -p "$prefix"
export PKG_CONFIG_PATH="$prefix/share/pkgconfig:$prefix/lib/pkgconfig:$prefix/lib/x86_64-linux-gnu/pkgconfig:${PKG_CONFIG_PATH:-}"
export LD_LIBRARY_PATH="$prefix/lib:$prefix/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
fetch() {
 local url=$1 digest=$2 name=$3
 curl --fail --location --retry 2 "$url" -o "$build/source/$name"
 echo "$digest  $build/source/$name" | sha256sum -c -
 tar -xf "$build/source/$name" -C "$build/source"
}
fetch https://gitlab.freedesktop.org/wayland/wayland-protocols/-/archive/1.46/wayland-protocols-1.46.tar.gz 83afedde1e63751578ba51b5f6c36052802f85634ed68be41ecfcc3a515ab03d protocols.tar.gz
meson setup "$build/protocols-build" "$build/source/wayland-protocols-1.46" --prefix="$prefix" -Dtests=false
meson install -C "$build/protocols-build"
fetch https://gitlab.freedesktop.org/emersion/libdisplay-info/-/archive/0.3.0/libdisplay-info-0.3.0.tar.gz 2b467e3336aec63819d6aca28d7310d3dc7415b2b3a3c3a5aec9d3727053c078 display.tar.gz
meson setup "$build/display-build" "$build/source/libdisplay-info-0.3.0" --prefix="$prefix"
meson compile -C "$build/display-build" -j2
meson install -C "$build/display-build"
fetch https://gitlab.freedesktop.org/wayland/weston/-/archive/16.0.2/weston-16.0.2.tar.gz 789d1254ccc6a7c04cd25be717eba1bdd7d14f14d43cff980a5536d60cdf25a5 weston.tar.gz
meson setup "$build/weston-build" "$build/source/weston-16.0.2" --prefix="$prefix" \
 -Dbackend-drm=false -Dbackend-headless=true -Dbackend-pipewire=false -Dbackend-rdp=false -Dbackend-vnc=false -Dbackend-wayland=false -Dbackend-x11=false -Dbackend-default=headless -Drenderer-vulkan=false -Dxwayland=false -Dsystemd=false -Dshell-lua=false -Dshell-desktop=true -Dshell-ivi=false -Dshell-kiosk=false -Dcolor-management-lcms=true -Ddemo-clients=false '-Dsimple-clients=[]' -Dtools=info,debug -Dtests=false -Ddoc=false
meson compile -C "$build/weston-build" -j2
meson install -C "$build/weston-build"
# Match leg133's Linux LGPL mpv0.41 rendering core; 0.42 needs a newer libplacebo.
fetch https://ffmpeg.org/releases/ffmpeg-8.0.tar.xz b2751fccb6cc4c77708113cd78b561059b6fa904b24162fa0be2d60273d27b8e ffmpeg.tar.xz
(
 cd "$build/source/ffmpeg-8.0"
 ./configure --prefix="$prefix" --disable-gpl --disable-nonfree --enable-shared --disable-static --disable-programs --disable-doc --disable-debug --disable-autodetect --disable-everything --enable-protocol=file --enable-demuxer=matroska --enable-decoder=hevc --enable-parser=hevc --enable-filter=buffer,buffersink,format,null,scale --enable-swscale --enable-swresample
 make -j2
 make install
) > desktop-evidence/hdr-linux/ffmpeg-build.log 2>&1
fetch https://github.com/mpv-player/mpv/archive/refs/tags/v0.41.0.tar.gz ee21092a5ee427353392360929dc64645c54479aefdb5babc5cfbb5fad626209 mpv.tar.gz
meson setup "$build/mpv-build" "$build/source/mpv-0.41.0" --prefix="$prefix" --auto-features=disabled -Dgpl=false -Dlibmpv=true -Dcplayer=false -Dgl=enabled -Dplain-gl=enabled -Degl=enabled -Dbuildtype=release -Ddefault_library=shared
meson compile -C "$build/mpv-build" -j2
meson install -C "$build/mpv-build"
meson introspect "$build/mpv-build" --buildoptions > desktop-evidence/hdr-linux/mpv-build-options.json
cp "$build/source/ffmpeg-8.0/config.h" desktop-evidence/hdr-linux/ffmpeg-config.h
bash desktop/tools/linux-wayland/build.sh "$prefix" "$build/source/weston-16.0.2" "$build/native"
cc -fPIC -shared desktop/tools/hdr/linux_authority.c -o "$build/native/capture-authority.so" $(pkg-config --cflags --libs libweston-16)
