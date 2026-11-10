#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
set -euo pipefail
build="$PWD/desktop/build/hdr-linux"
prefix="$build/prefix"
export LD_LIBRARY_PATH="$prefix/lib:$prefix/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
export MPV_LIBRARY="$prefix/lib/x86_64-linux-gnu/libmpv.so"
[[ -f "$MPV_LIBRARY" ]] || export MPV_LIBRARY="$prefix/lib/libmpv.so"
export SPIKE_WAYLAND_BRIDGE="$build/native/libpictureshow_wayland.so" SPIKE_NULL_AUDIO=1 SKIKO_RENDER_API=SOFTWARE SPIKE_SOFTWARE_GPU=1 LIBGL_ALWAYS_SOFTWARE=1
export XDG_RUNTIME_DIR
XDG_RUNTIME_DIR=$(mktemp -d)
export WAYLAND_DISPLAY=hdr-numeric
compositor_pid=''
cleanup() {
 if [[ -n "$compositor_pid" ]]; then kill "$compositor_pid" 2>/dev/null || true; wait "$compositor_pid" 2>/dev/null || true; fi
 python3 -c 'import shutil,sys;shutil.rmtree(sys.argv[1],ignore_errors=True)' "$XDG_RUNTIME_DIR"
}
trap cleanup EXIT
for mode in hdr sdr; do
 out="$PWD/desktop-evidence/hdr-linux/$mode-output"
 mkdir -p "$out"
 if [[ "$mode" == hdr ]]; then eotf=st2084; gamut=bt2020rgb; else eotf=sdr; gamut=default; fi
 cat > "$out/weston.ini" <<CONFIG
[core]
color-management=true
idle-time=0
[shell]
locking=false
panel-position=none
background-color=0xff000000
[output]
name=headless
eotf-mode=$eotf
colorimetry-mode=$gamut
color-profile=auto:
CONFIG
 "$prefix/bin/weston" -B headless --renderer=gl --width=640 --height=360 --socket="$WAYLAND_DISPLAY" --config="$out/weston.ini" --modules="$build/native/capture-authority.so" --log="$out/weston.log" > "$out/weston-console.log" 2>&1 &
 compositor_pid=$!
 for n in $(seq 1 100); do [[ -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]] && break; kill -0 "$compositor_pid"; sleep .1; done
 [[ -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]]
 for media in pq sdr; do
  java -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED -cp 'desktop/build/spike-runtime/lib/*' app.pictureshow.desktop.MainKt wayland "desktop-fixtures/hdr/$media.mkv" "$out/$media" --hdr-numeric
 done
 kill "$compositor_pid"; wait "$compositor_pid" || true; compositor_pid=''
 [[ ! -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]] || python3 -c 'import os;os.unlink(os.environ["XDG_RUNTIME_DIR"]+"/"+os.environ["WAYLAND_DISPLAY"])'
done
python3 desktop/tools/hdr/linux_samples.py desktop-fixtures/hdr/manifest.json desktop-evidence/hdr-linux
python3 desktop/tools/hdr/linux_gate.py desktop-fixtures/hdr/manifest.json desktop-evidence/hdr-linux
