#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
set -euo pipefail
build="$PWD/desktop/build/app-native"
prefix="$build/prefix"
export LD_LIBRARY_PATH="$prefix/lib:$prefix/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
export MPV_LIBRARY="$prefix/lib/x86_64-linux-gnu/libmpv.so"
[[ -f "$MPV_LIBRARY" ]] || export MPV_LIBRARY="$prefix/lib/libmpv.so"
export SPIKE_WAYLAND_BRIDGE="$build/native/libpictureshow_wayland.so"
export SPIKE_NULL_AUDIO=1 SPIKE_SOFTWARE_GPU=1 SKIKO_RENDER_API=SOFTWARE LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=softpipe
export XDG_RUNTIME_DIR
XDG_RUNTIME_DIR=$(mktemp -d)
export WAYLAND_DISPLAY=pictureshow-app-smoke
compositor_pid=''
cleanup() {
 if [[ -n "$compositor_pid" ]]; then kill "$compositor_pid" 2>/dev/null || true; wait "$compositor_pid" 2>/dev/null || true; fi
 python3 -c 'import shutil,sys;shutil.rmtree(sys.argv[1],ignore_errors=True)' "$XDG_RUNTIME_DIR"
}
trap cleanup EXIT
mkdir -p desktop-evidence/compositor
cat > "$XDG_RUNTIME_DIR/weston.ini" <<'CONFIG'
[core]
color-management=true
idle-time=0
[shell]
locking=false
startup-animation=none
panel-position=none
background-color=0xff000000
[output]
name=headless
eotf-mode=st2084
colorimetry-mode=bt2020rgb
color-profile=auto:
CONFIG
env MESA_EXTENSION_OVERRIDE=-GL_EXT_shader_framebuffer_fetch_non_coherent \
 "$prefix/bin/weston" -B headless --renderer=gl --width=1920 --height=1080 \
 --socket="$WAYLAND_DISPLAY" --config="$XDG_RUNTIME_DIR/weston.ini" \
 --log="$PWD/desktop-evidence/compositor/weston.log" > desktop-evidence/compositor/console.log 2>&1 &
compositor_pid=$!
for n in $(seq 1 600); do
 [[ -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]] && break
 kill -0 "$compositor_pid"
 sleep .1
done
[[ -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]]
# AWT shell uses its owned Xvfb; video uses the separate qualified Wayland surface.
xvfb-run -a -s '-screen 0 1920x1080x24' python3 desktop/tools/app-smoke/run.py \
 --work desktop/build/disposable-app-smoke --evidence desktop-evidence/app-smoke
