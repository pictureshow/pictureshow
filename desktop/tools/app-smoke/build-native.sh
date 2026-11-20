#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
# Shared source build for CI smoke and the macOS release package.
set -euo pipefail
build="${PICTURESHOW_NATIVE_BUILD:-$PWD/desktop/build/app-native}"
evidence="${PICTURESHOW_NATIVE_EVIDENCE:-$PWD/desktop-evidence/native-build}"
release="${PICTURESHOW_MAC_NATIVE_RELEASE:-0}"
prefix="$build/prefix"
mkdir -p "$build/source" "$prefix" "$evidence"
case "$(uname -s)" in
 Darwin) os=mac ;;
 Linux) os=linux ;;
 *) echo 'Use the pinned Windows LGPL archive in the workflow.' >&2; exit 2 ;;
esac
if [[ "$release" == 1 ]]; then
 [[ "$os" == mac ]] || { echo 'The release native build is macOS only.' >&2; exit 2; }
 # Resolve third-party libraries from the pinned prefix, without Homebrew dependencies.
 export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig:$prefix/share/pkgconfig"
 unset PKG_CONFIG_PATH
 export MACOSX_DEPLOYMENT_TARGET=15.0
fi
fetch() {
 local url=$1 digest=$2 filename=$3
 curl --fail --location --retry 2 --silent --show-error "$url" -o "$build/source/$filename"
 python3 - "$build/source/$filename" "$digest" <<'PY'
import hashlib,pathlib,sys
p=pathlib.Path(sys.argv[1])
if hashlib.sha256(p.read_bytes()).hexdigest()!=sys.argv[2]:
 raise SystemExit('Pinned native source digest mismatch: '+p.name)
PY
 tar -xf "$build/source/$filename" -C "$build/source"
}
source desktop/tools/macos/native-sources.sh
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
if [[ "$release" == 1 ]]; then
 fetch "$dav1d_url" "$dav1d_sha256" dav1d.tar.xz
 meson setup "$build/dav1d-build" "$build/source/dav1d-$dav1d_version" --prefix="$prefix" --libdir=lib \
  --buildtype=release -Ddefault_library=shared -Denable_tools=false -Denable_tests=false
 meson compile -C "$build/dav1d-build" -j2
 meson install -C "$build/dav1d-build"
fi
fetch "$ffmpeg_url" "$ffmpeg_sha256" ffmpeg.tar.xz
extra=()
if [[ "$os" == mac ]]; then
 extra+=(--enable-videotoolbox --enable-audiotoolbox --enable-securetransport)
else
 extra+=(--enable-gnutls)
fi
if [[ "$release" == 0 ]]; then
 extra+=(--disable-everything --enable-protocol=file,http,https,tcp,tls,crypto,data,httpproxy
  --enable-decoders --enable-demuxers --enable-parsers --disable-decoder=ffv1
  --enable-filter=buffer,buffersink,format,null,scale)
fi
(
 cd "$build/source/ffmpeg-$ffmpeg_version"
 ./configure --prefix="$prefix" --disable-gpl --disable-nonfree --enable-shared --disable-static \
  --disable-programs --disable-doc --disable-debug --disable-autodetect \
  --enable-network --enable-libdav1d --enable-swscale --enable-swresample "${extra[@]}"
 make -j2
 make install
) > "$evidence/ffmpeg-build.log" 2>&1
cp "$build/source/ffmpeg-$ffmpeg_version/config.h" "$evidence/ffmpeg-config.h"
if [[ "$release" == 0 ]]; then
cat > "$evidence/smoke-only-decoder-omissions.json" <<'JSON'
{"omittedDecoders":["ffv1"],"scope":"isolated smoke dependency build only","reason":"Exercise genuine unavailable-decoder conversion without restricting the production device profile"}
JSON
fi
if [[ "$os" == mac ]]; then
 if [[ "$release" == 1 ]]; then
  bash desktop/tools/macos/build-render-deps.sh "$build"
 fi
 sh desktop/tools/macos/build-mpv.sh "$prefix" "$build/mpv-source"
 clang++ -std=c++17 -O2 -fobjc-arc -dynamiclib desktop/src/native/macos_surface.mm \
  -I"$prefix/include" -L"$prefix/lib" -lmpv -Wl,-rpath,"$prefix/lib" \
  -framework Cocoa -framework OpenGL -framework Metal -framework QuartzCore -framework IOSurface \
  -o "$build/libpictureshow_layer.dylib"
 clang -O2 -fobjc-arc -dynamiclib desktop/src/native/macos_window.m -framework Cocoa -o "$build/libpictureshow_window.dylib"
 clang -O2 -fobjc-arc desktop/tools/app-smoke/macos-preflight.m \
  -framework Foundation -framework Metal -framework OpenGL -framework CoreGraphics -framework IOSurface \
  -o "$build/macos-native-preflight"
 "$build/macos-native-preflight" "$build/libpictureshow_layer.dylib" \
 > "$evidence/macos-preflight.json"
 cp "$build/mpv-source/build/meson-info/intro-buildoptions.json" "$evidence/mpv-buildoptions.json"
else
 fetch https://github.com/mpv-player/mpv/archive/refs/tags/v0.41.0.tar.gz ee21092a5ee427353392360929dc64645c54479aefdb5babc5cfbb5fad626209 mpv.tar.gz
 meson setup "$build/mpv-build" "$build/source/mpv-0.41.0" --prefix="$prefix" --auto-features=disabled \
  -Dgpl=false -Dlibmpv=true -Dcplayer=false -Dgl=enabled -Dplain-gl=enabled -Degl=enabled \
  -Dbuildtype=release -Ddefault_library=shared
 meson compile -C "$build/mpv-build" -j2
 meson install -C "$build/mpv-build"
 bash desktop/tools/linux-wayland/build.sh "$prefix" "$PWD/desktop/build/hdr-linux/source/weston-16.0.2" "$build/native"
fi
python3 - "$prefix" "$evidence" <<'PY'
import hashlib,json,pathlib,sys
prefix=pathlib.Path(sys.argv[1]); libraries={}
for p in prefix.rglob('*'):
 if p.is_file() and ('.dylib' in p.name or '.so' in p.name):
  libraries[str(p.relative_to(prefix))]=hashlib.sha256(p.read_bytes()).hexdigest()
(pathlib.Path(sys.argv[2])/'library-hashes.json').write_text(json.dumps(libraries,indent=2)+'\n')
PY
