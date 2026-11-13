#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
set -euo pipefail
if [[ $# -lt 7 ]]; then
 echo 'Usage: run-isolated.sh WESTON_PREFIX WESTON_SOURCE MPV_PREFIX NATIVE_LIBRARY CLASSPATH MEDIA EVIDENCE [--sustain=125]' >&2
 exit 2
fi
weston_prefix=$(cd "$1" && pwd)
weston_source=$(cd "$2" && pwd)
mpv_prefix=$(cd "$3" && pwd)
native_library=$4
classpath=$5
media=$6
mkdir -p "$7"
evidence=$(cd "$7" && pwd)
shift 7
here=$(cd "$(dirname "$0")" && pwd)
export LD_LIBRARY_PATH="$weston_prefix/lib/x86_64-linux-gnu:$mpv_prefix/lib/x86_64-linux-gnu:$mpv_prefix/lib:${LD_LIBRARY_PATH:-}"
export PKG_CONFIG_PATH="$weston_prefix/lib/x86_64-linux-gnu/pkgconfig:${PKG_CONFIG_PATH:-}"
cc -fPIC -shared "$here/capture-authority.c" -o "$evidence/capture-authority.so" $(pkg-config --cflags --libs libweston-16)
python3 "$here/patch-test-plugin.py" "$weston_source"
ninja -C "$weston_source/build" -j2 tests/harness/test-plugin.so
export XDG_RUNTIME_DIR
XDG_RUNTIME_DIR=$(mktemp -d)
export WAYLAND_DISPLAY=pictureshow-probe
cat > "$evidence/weston.ini" <<'CONFIG'
[core]
color-management=true
idle-time=0
[shell]
locking=false
panel-position=none
CONFIG
"$weston_prefix/bin/weston" -B headless --renderer=gl --width=1920 --height=1080 \
 --refresh-rate=60000 --socket="$WAYLAND_DISPLAY" --config="$evidence/weston.ini" \
 --modules="$weston_source/build/tests/harness/test-plugin.so,$evidence/capture-authority.so" \
 --log="$evidence/weston.log" > "$evidence/weston-console.log" 2>&1 &
compositor_pid=$!
cleanup() {
 kill "$compositor_pid" 2>/dev/null || true
 wait "$compositor_pid" 2>/dev/null || true
 python3 -c 'import shutil,sys;shutil.rmtree(sys.argv[1],ignore_errors=True)' "$XDG_RUNTIME_DIR"
}
trap cleanup EXIT
for n in $(seq 1 30); do [[ -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]] && break; sleep 0.1; done
[[ -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]]
export MPV_LIBRARY="$mpv_prefix/lib/x86_64-linux-gnu/libmpv.so"
[[ -f "$MPV_LIBRARY" ]] || export MPV_LIBRARY="$mpv_prefix/lib/libmpv.so"
export SPIKE_WAYLAND_BRIDGE="$native_library" SPIKE_NULL_AUDIO=1 SKIKO_RENDER_API=SOFTWARE
java -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED -cp "$classpath" \
 app.pictureshow.desktop.MainKt wayland "$media" "$evidence" "$@"
