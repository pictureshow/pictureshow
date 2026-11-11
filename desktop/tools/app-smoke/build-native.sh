#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
# Smoke dependency build, not a redistributable/signable release package.
set -euo pipefail
build="$PWD/desktop/build/app-native"
prefix="$build/prefix"
mkdir -p "$build/source" "$prefix" desktop-evidence/native-build
case "$(uname -s)" in
 Darwin) os=mac ;;
 Linux) os=linux ;;
 *) echo 'Use the pinned Windows LGPL archive in the workflow.' >&2; exit 2 ;;
esac
fetch() {
 local url=$1 digest=$2 filename=$3
 curl --fail --location --retry 2 "$url" -o "$build/source/$filename"
 python3 - "$build/source/$filename" "$digest" <<'PY'
import hashlib,pathlib,sys
p=pathlib.Path(sys.argv[1])
if hashlib.sha256(p.read_bytes()).hexdigest()!=sys.argv[2]:
 raise SystemExit('Pinned native source digest mismatch: '+p.name)
PY
 tar -xf "$build/source/$filename" -C "$build/source"
}
export PKG_CONFIG_PATH="$prefix/share/pkgconfig:$prefix/lib/pkgconfig:$prefix/lib/x86_64-linux-gnu/pkgconfig:${PKG_CONFIG_PATH:-}"
export LD_LIBRARY_PATH="$prefix/lib:$prefix/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
if [[ "$os" == linux ]]; then
 # Keep compositor and EGL implementation identical to the qualified Linux gate.
 # Its minimal FFmpeg is rebuilt below with the advertised desktop codecs too.
 bash desktop/tools/hdr/linux_build.sh
 cp -a desktop/build/hdr-linux/prefix/. "$prefix/"
 export PKG_CONFIG_PATH="$prefix/share/pkgconfig:$prefix/lib/pkgconfig:$prefix/lib/x86_64-linux-gnu/pkgconfig:${PKG_CONFIG_PATH:-}"
 export LD_LIBRARY_PATH="$prefix/lib:$prefix/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
fi
fetch https://ffmpeg.org/releases/ffmpeg-8.0.tar.xz b2751fccb6cc4c77708113cd78b561059b6fa904b24162fa0be2d60273d27b8e ffmpeg.tar.xz
extra=()
if [[ "$os" == mac ]]; then
 extra+=(--enable-videotoolbox --enable-audiotoolbox --enable-securetransport)
else
 extra+=(--enable-gnutls)
fi
(
 cd "$build/source/ffmpeg-8.0"
 ./configure --prefix="$prefix" --disable-gpl --disable-nonfree --enable-shared --disable-static \
  --disable-programs --disable-doc --disable-debug --disable-autodetect --disable-everything \
  --enable-protocol=file,http,https,tcp,tls,crypto,data,httpproxy --enable-network \
  --enable-decoders --enable-demuxers --enable-parsers --enable-libdav1d --disable-decoder=ffv1 \
  --enable-filter=buffer,buffersink,format,null,scale \
  --enable-swscale --enable-swresample "${extra[@]}"
 make -j2
 make install
) > desktop-evidence/native-build/ffmpeg-build.log 2>&1
cp "$build/source/ffmpeg-8.0/config.h" desktop-evidence/native-build/ffmpeg-config.h
cat > desktop-evidence/native-build/smoke-only-decoder-omissions.json <<'JSON'
{"omittedDecoders":["ffv1"],"scope":"isolated smoke dependency build only","reason":"Exercise genuine unavailable-decoder conversion without restricting the production device profile"}
JSON
if [[ "$os" == mac ]]; then
 sh desktop/tools/macos/build-mpv.sh "$prefix" "$build/mpv-source"
 python3 - "$build/mpv-source/mpv.tar.gz" <<'PY'
import hashlib,pathlib,sys
expected='bcfebbfa2b65a6d88dc97ea18117831072373e38b5785a2afb874ed4df5070dd'
if hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()!=expected:
 raise SystemExit('Pinned mpv 0.42 source digest mismatch')
PY
 clang++ -std=c++17 -O2 -fobjc-arc -dynamiclib desktop/src/native/macos_surface.mm \
  -I"$prefix/include" -L"$prefix/lib" -lmpv -Wl,-rpath,"$prefix/lib" \
  -framework Cocoa -framework OpenGL -framework Metal -framework QuartzCore -framework IOSurface \
  -o "$build/libpictureshow_layer.dylib"
 clang -O2 -fobjc-arc -dynamiclib desktop/src/native/macos_window.m -framework Cocoa -o "$build/libpictureshow_window.dylib"
 clang -O2 -fobjc-arc desktop/tools/app-smoke/macos-preflight.m \
  -framework Foundation -framework Metal -framework OpenGL -framework CoreGraphics \
  -o "$build/macos-native-preflight"
 "$build/macos-native-preflight" "$build/libpictureshow_layer.dylib" \
  > desktop-evidence/native-build/macos-preflight.json
else
 fetch https://github.com/mpv-player/mpv/archive/refs/tags/v0.41.0.tar.gz ee21092a5ee427353392360929dc64645c54479aefdb5babc5cfbb5fad626209 mpv.tar.gz
 meson setup "$build/mpv-build" "$build/source/mpv-0.41.0" --prefix="$prefix" --auto-features=disabled \
  -Dgpl=false -Dlibmpv=true -Dcplayer=false -Dgl=enabled -Dplain-gl=enabled -Degl=enabled \
  -Dbuildtype=release -Ddefault_library=shared
 meson compile -C "$build/mpv-build" -j2
 meson install -C "$build/mpv-build"
 bash desktop/tools/linux-wayland/build.sh "$prefix" "$PWD/desktop/build/hdr-linux/source/weston-16.0.2" "$build/native"
fi
python3 - "$prefix" <<'PY'
import hashlib,json,pathlib,sys
prefix=pathlib.Path(sys.argv[1]); libraries={}
for p in prefix.rglob('*'):
 if p.is_file() and ('.dylib' in p.name or '.so' in p.name):
  libraries[str(p.relative_to(prefix))]=hashlib.sha256(p.read_bytes()).hexdigest()
pathlib.Path('desktop-evidence/native-build/library-hashes.json').write_text(json.dumps(libraries,indent=2)+'\n')
PY
