#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
set -euo pipefail
if [[ $# != 3 ]]; then
 echo 'Usage: build.sh MPV_PREFIX WESTON_SOURCE OUTPUT_DIRECTORY' >&2
 echo 'Requires a C/C++ compiler, pkg-config, wayland-scanner, wayland-protocols, Wayland/EGL/OpenGL development packages.' >&2
 exit 2
fi
mpv_prefix=$(cd "$1" && pwd)
weston_source=$(cd "$2" && pwd)
mkdir -p "$3"
out=$(cd "$3" && pwd)
source_root=$(cd "$(dirname "$0")/../.." && pwd)
protocols=$(pkg-config --variable=pkgdatadir wayland-protocols)
mkdir -p "$out/generated"
protocol() {
 local name=$1 path=$2
 wayland-scanner client-header "$path" "$out/generated/$name-client-protocol.h"
 wayland-scanner private-code "$path" "$out/generated/$name-protocol.c"
 cc -fPIC -c "$out/generated/$name-protocol.c" -o "$out/generated/$name-protocol.o"
}
protocol xdg-shell "$protocols/stable/xdg-shell/xdg-shell.xml"
protocol color-management-v1 "$protocols/staging/color-management/color-management-v1.xml"
protocol weston-output-capture "$weston_source/protocol/weston-output-capture.xml"
protocol weston-test "$weston_source/protocol/weston-test.xml"
c++ -std=c++17 -shared -fPIC -O2 -pthread -I"$out/generated" -I"$mpv_prefix/include" \
 "$source_root/src/native/linux_wayland.cpp" "$out"/generated/*-protocol.o \
 $(pkg-config --cflags --libs wayland-client wayland-egl egl gl) \
 -L"$mpv_prefix/lib" -L"$mpv_prefix/lib/x86_64-linux-gnu" -lmpv -o "$out/libpictureshow_wayland.so"
echo "Built $out/libpictureshow_wayland.so; set SPIKE_WAYLAND_BRIDGE to this file."
echo 'The Weston capture and test-seat protocols are diagnostic-only; normal presentation needs neither.'
