#!/bin/bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
#
# Rebuilds the native libraries in mpv/src/main/jniLibs from the pinned sources in tools/mpv/sources.txt:
#   libmpv.so                 mpv built with -Dgpl=false (LGPL 2.1 or later), with libass, freetype, fribidi,
#                             harfbuzz, fontconfig, expat and libplacebo linked in statically
#   libav*_mpv.so, libsw*_mpv.so  FFmpeg (LGPL 2.1 or later: no --enable-gpl, --enable-version3 or --enable-nonfree),
#                             with dav1d linked in statically; the _mpv suffix keeps them apart from media3-ffmpeg's
#   libpictureshow_mpv.so     the JNI glue in mpv/src/main/jni
#
# usage: NDK=<Android NDK r26b dir> WORK=<scratch dir> tools/mpv/build.sh
# optional environment:
#   OUT   output jniLibs directory (default: mpv/src/main/jniLibs in this checkout)
#   ABIS  ABIs to build (default: "arm64-v8a armeabi-v7a x86_64")
#   JOBS  build parallelism (default: nproc)
# Needs a Linux x86_64 host with the tools in tools/mpv/Dockerfile. See tools/mpv/README.md.
set -euo pipefail

ANDROID_API=23 # the app's minSdk
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
: "${NDK:?set NDK to the Android NDK r26b directory}"
: "${WORK:?set WORK to a scratch directory}"
OUT="${OUT:-$ROOT/mpv/src/main/jniLibs}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
JOBS="${JOBS:-$(nproc)}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TOOLCHAIN/bin" ] || { echo "no NDK toolchain at $TOOLCHAIN" >&2; exit 1; }
mkdir -p "$WORK/src"
WORK="$(cd "$WORK" && pwd)"
export PATH="$TOOLCHAIN/bin:$PATH"

# Fetch and verify the sources.
declare -A VERSION URL SUM
while read -r name version url sum; do
  case "$name" in ''|'#'*) continue ;; esac
  VERSION[$name]=$version; URL[$name]=$url; SUM[$name]=$sum
done < "$HERE/sources.txt"
archive() { echo "$WORK/src/$1-${VERSION[$1]}.tar.${URL[$1]##*.tar.}"; }
for name in "${!VERSION[@]}"; do
  if [ "$name" = libplacebo ]; then
    dir="$WORK/src/libplacebo-${VERSION[$name]}"
    [ -d "$dir" ] || git clone -q --recursive --depth 1 -b "${VERSION[$name]}" "${URL[$name]}" "$dir"
    [ "$(git -C "$dir" rev-parse HEAD)" = "${SUM[$name]}" ] || { echo "libplacebo is not at ${SUM[$name]}" >&2; exit 1; }
    continue
  fi
  file="$(archive "$name")"
  [ -f "$file" ] || curl -fsSL -o "$file" "${URL[$name]}"
  echo "${SUM[$name]}  $file" | sha256sum -c -
done

unpack() { # unpack <name> <dir>
  rm -rf "$2"; mkdir -p "$2"
  if [ "$1" = libplacebo ]; then cp -a "$WORK/src/libplacebo-${VERSION[$1]}/." "$2/"
  else tar -xf "$(archive "$1")" -C "$2" --strip-components=1; fi
}

for ABI in $ABIS; do
  case "$ABI" in
    armeabi-v7a) TRIPLE=armv7a-linux-androideabi; FAMILY=arm; CPU=armv7; FFARCH=(--arch=arm --cpu=armv7-a)
                 CFLAGS="-march=armv7-a -mfloat-abi=softfp -mfpu=neon"; LDEXTRA="-Wl,--fix-cortex-a8" ;;
    arm64-v8a)   TRIPLE=aarch64-linux-android; FAMILY=aarch64; CPU=armv8; FFARCH=(--arch=aarch64 --cpu=armv8-a)
                 CFLAGS=""; LDEXTRA="" ;;
    x86_64)      TRIPLE=x86_64-linux-android; FAMILY=x86_64; CPU=x86_64; FFARCH=(--arch=x86_64 --cpu=x86-64 --disable-asm)
                 CFLAGS=""; LDEXTRA="" ;;
    *) echo "unknown ABI $ABI" >&2; exit 1 ;;
  esac
  BUILD="$WORK/build/$ABI"
  SYSROOT="$WORK/install/$ABI" # everything installs to /usr/local under this, like a device sysroot
  PREFIX=/usr/local
  # RESUME=1 keeps finished libraries (a .done stamp each) and rebuilds from the first unfinished one.
  [ "${RESUME:-0}" = 1 ] || rm -rf "$BUILD" "$SYSROOT"
  mkdir -p "$BUILD" "$SYSROOT"
  # Source paths are mapped away so that no build directory ends up in a binary.
  FLAGS="$CFLAGS -fPIC -ffile-prefix-map=$BUILD/= -ffile-prefix-map=$SYSROOT/= -ffile-prefix-map=$NDK/=ndk/"
  LDFLAGS="$LDEXTRA -Wl,-z,max-page-size=16384"
  quote() { local out="" a; for a in "$@"; do out="$out'$a', "; done; echo "[${out%, }]"; }
  # Meson finds a bare cross-file name under $XDG_DATA_HOME/meson/cross, and libmpv embeds its options verbatim
  # ("configuration"), so the name carries no build path.
  export XDG_DATA_HOME="$BUILD/xdg"
  mkdir -p "$XDG_DATA_HOME/meson/cross"
  CROSS="$XDG_DATA_HOME/meson/cross/android-$ABI.ini"
  cat > "$CROSS" <<EOF
[binaries]
c = '${TRIPLE}${ANDROID_API}-clang'
cpp = '${TRIPLE}${ANDROID_API}-clang++'
ar = 'llvm-ar'
nm = 'llvm-nm'
strip = 'llvm-strip'
pkg-config = 'pkg-config'
[host_machine]
system = 'android'
cpu_family = '$FAMILY'
cpu = '$CPU'
endian = 'little'
[properties]
pkg_config_libdir = '$SYSROOT$PREFIX/lib/pkgconfig'
sys_root = '$SYSROOT'
[built-in options]
c_args = $(quote $FLAGS)
cpp_args = $(quote $FLAGS)
c_link_args = $(quote $LDFLAGS)
cpp_link_args = $(quote $LDFLAGS)
EOF
  export PKG_CONFIG_LIBDIR="$SYSROOT$PREFIX/lib/pkgconfig" PKG_CONFIG_SYSROOT_DIR="$SYSROOT"
  LOG="$BUILD/logs"; mkdir -p "$LOG"

  meson_build() { # meson_build <name> <meson args...>
    local name=$1; shift
    done_already "$name" && return
    echo "== $name ${VERSION[$name]} for $ABI"
    unpack "$name" "$BUILD/$name"
    (cd "$BUILD/$name" &&
      meson setup out --cross-file "android-$ABI.ini" --prefix=$PREFIX --libdir=lib --buildtype=release -Db_ndebug=true \
        -Ddefault_library=static "$@" > "$LOG/$name.setup.log" 2>&1 &&
      ninja -C out -j "$JOBS" > "$LOG/$name.build.log" 2>&1 &&
      DESTDIR="$SYSROOT" meson install -C out --no-rebuild > "$LOG/$name.install.log" 2>&1) ||
      { echo "$name failed for $ABI; logs in $LOG" >&2; tail -n 40 "$LOG/$name".*.log >&2; exit 1; }
    touch "$BUILD/.$name.done"
  }
  done_already() { [ -f "$BUILD/.$1.done" ] && echo "== $1 already built for $ABI"; }

  meson_build dav1d -Denable_tools=false -Denable_tests=false -Denable_examples=false -Denable_docs=false

  if ! done_already ffmpeg; then
  echo "== ffmpeg ${VERSION[ffmpeg]} for $ABI"
  unpack ffmpeg "$BUILD/ffmpeg"
  # avcodec_configuration() embeds this line, so it names no local path (pkg-config finds dav1d through the
  # environment). Protocols: what the loopback http:// URLs and mpv's stream layer need.
  (cd "$BUILD/ffmpeg" && ./configure "${FFARCH[@]}" \
      --target-os=android --enable-cross-compile \
      --cc="${TRIPLE}${ANDROID_API}-clang" --cxx="${TRIPLE}${ANDROID_API}-clang++" \
      --nm=llvm-nm --ar=llvm-ar --ranlib=llvm-ranlib --strip=llvm-strip --pkg-config=pkg-config \
      --pkg-config-flags=--static --extra-cflags="$CFLAGS" --extra-ldflags="$LDFLAGS" \
      --prefix=$PREFIX --build-suffix=_mpv --enable-shared --disable-static --disable-symver \
      --disable-doc --disable-programs --disable-autodetect --disable-avdevice --disable-devices \
      --disable-encoders --disable-muxers --enable-encoder=mjpeg,png --enable-muxer=image2 \
      --disable-protocols --enable-protocol=file,http,tcp,crypto,data,hls,subfile,concat,concatf,pipe \
      --enable-network --enable-zlib --enable-jni --enable-mediacodec --enable-libdav1d \
      --disable-vulkan --disable-v4l2-m2m --disable-debug > "$LOG/ffmpeg.configure.log" 2>&1 &&
    make -j"$JOBS" > "$LOG/ffmpeg.make.log" 2>&1 &&
    make install-libs install-headers DESTDIR="$SYSROOT" > "$LOG/ffmpeg.install.log" 2>&1) ||
    { echo "ffmpeg failed for $ABI" >&2; tail -n 40 "$LOG"/ffmpeg.*.log >&2; exit 1; }
  touch "$BUILD/.ffmpeg.done"
  fi
  # mpv asks pkg-config for libavcodec etc.: point the plain names at the suffixed libraries.
  for pc in "$SYSROOT$PREFIX"/lib/pkgconfig/lib*_mpv.pc; do
    plain="${pc%_mpv.pc}.pc"
    sed "s/^Name: \(.*\)_mpv$/Name: \1/" "$pc" > "$plain"
  done

  if ! done_already expat; then
  echo "== expat ${VERSION[expat]} for $ABI"
  unpack expat "$BUILD/expat"
  (cmake -S "$BUILD/expat" -B "$BUILD/expat/out" -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" \
      -DANDROID_PLATFORM="android-$ANDROID_API" -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=$PREFIX \
      -DCMAKE_INSTALL_LIBDIR=lib -DCMAKE_C_FLAGS="$FLAGS" -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
      -DEXPAT_SHARED_LIBS=OFF -DEXPAT_BUILD_TOOLS=OFF -DEXPAT_BUILD_EXAMPLES=OFF -DEXPAT_BUILD_TESTS=OFF \
      -DEXPAT_BUILD_DOCS=OFF -DEXPAT_BUILD_FUZZERS=OFF > "$LOG/expat.setup.log" 2>&1 &&
    ninja -C "$BUILD/expat/out" -j "$JOBS" > "$LOG/expat.build.log" 2>&1 &&
    DESTDIR="$SYSROOT" ninja -C "$BUILD/expat/out" install > "$LOG/expat.install.log" 2>&1) ||
    { echo "expat failed for $ABI" >&2; tail -n 40 "$LOG"/expat.*.log >&2; exit 1; }
  touch "$BUILD/.expat.done"
  fi

  meson_build freetype -Dharfbuzz=disabled -Dpng=disabled -Dzlib=disabled -Dbzip2=disabled -Dbrotli=disabled \
    -Dtests=disabled
  meson_build fribidi -Ddocs=false -Dbin=false -Dtests=false
  meson_build harfbuzz -Dfreetype=enabled -Dglib=disabled -Dgobject=disabled -Dcairo=disabled -Dchafa=disabled \
    -Dpng=disabled -Dzlib=disabled -Dicu=disabled -Dtests=disabled -Dintrospection=disabled -Ddocs=disabled \
    -Dutilities=disabled -Dsubset=disabled -Draster=disabled -Dvector=disabled -Dgpu=disabled -Dgpu_demo=disabled
  # harfbuzz is C++; libmpv is linked by the C driver, so name the NDK's static C++ runtime for it.
  grep -q 'lc++_static' "$SYSROOT$PREFIX/lib/pkgconfig/harfbuzz.pc" ||
    sed -i 's/^Libs:\(.*\)$/Libs:\1 -lc++_static -lc++abi/' "$SYSROOT$PREFIX/lib/pkgconfig/harfbuzz.pc"
  # Android has no fontconfig setup of its own: the app writes a fonts.conf that points at /system/fonts and a
  # cache directory it owns, and sets FONTCONFIG_FILE. These defaults only matter if it doesn't.
  meson_build fontconfig -Dxml-backend=expat -Ddoc=disabled -Dnls=disabled -Dtests=disabled -Dtools=disabled \
    -Dcache-build=disabled -Diconv=disabled -Ddefault-fonts-dirs=/system/fonts -Dadditional-fonts-dirs=no \
    -Dcache-dir=/data/local/tmp/fontconfig -Dbaseconfig-dir=/system/etc/fonts -Dconfig-dir=/system/etc/fonts/conf.d \
    -Dtemplate-dir=/system/etc/fonts/conf.avail -Dxml-dir=/system/etc/fonts
  meson_build libass -Dfontconfig=enabled -Ddirectwrite=disabled -Dcoretext=disabled -Dlibunibreak=disabled \
    -Dtest=disabled -Dcompare=disabled -Dprofile=disabled -Dfuzz=disabled -Dcheckasm=disabled
  meson_build libplacebo -Dvulkan=disabled -Dopengl=enabled -Dd3d11=disabled -Dglslang=disabled -Dshaderc=disabled \
    -Dlcms=disabled -Ddovi=enabled -Dlibdovi=disabled -Ddemos=false -Dtests=false -Dbench=false -Dfuzz=false \
    -Dunwind=disabled -Dxxhash=disabled
  meson_build mpv -Ddefault_library=shared -Dprefer_static=true -Dgpl=false -Dlibmpv=true -Dcplayer=false \
    -Dbuild-date=false -Dtests=false -Dlua=disabled -Djavascript=disabled -Dcplugins=disabled -Diconv=disabled \
    -Dlibarchive=disabled -Dlibbluray=disabled -Dlibcurl=disabled -Duchardet=disabled -Drubberband=disabled \
    -Dlcms2=disabled -Dzimg=disabled -Djpeg=disabled -Dvapoursynth=disabled -Dsubrandr=disabled \
    -Dlibavdevice=disabled -Dzlib=enabled -Dvulkan=disabled -Dshaderc=disabled -Dspirv-cross=disabled \
    -Dgl=enabled -Degl=disabled -Degl-android=enabled -Dplain-gl=enabled -Daudiotrack=enabled -Daaudio=disabled \
    -Dopensles=disabled -Dandroid-media-ndk=enabled -Dmanpage-build=disabled -Dhtml-build=disabled \
    -Dpdf-build=disabled
  cp "$LOG/mpv.setup.log" "$WORK/mpv-meson-$ABI.log"
  sed -n 's/^Message: List of enabled features: //p' "$LOG/mpv.setup.log" > "$WORK/mpv-features-$ABI.txt"

  echo "== JNI glue for $ABI"
  JBUILD="$BUILD/jni"
  (cmake -S "$ROOT/mpv/src/main/jni" -B "$JBUILD" -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" \
      -DANDROID_PLATFORM="android-$ANDROID_API" -DCMAKE_BUILD_TYPE=Release -DMPV_PREFIX="$SYSROOT$PREFIX" \
      -DCMAKE_C_FLAGS="$FLAGS" > "$LOG/jni.setup.log" 2>&1 &&
    ninja -C "$JBUILD" -j "$JOBS" > "$LOG/jni.build.log" 2>&1) ||
    { echo "JNI glue failed for $ABI" >&2; tail -n 40 "$LOG"/jni.*.log >&2; exit 1; }

  # Symbol tables stay for crash symbolication (AGP strips the APK copies); DWARF goes.
  mkdir -p "$OUT/$ABI"
  find "$OUT/$ABI" -maxdepth 1 -name '*.so' -delete
  for lib in avcodec avfilter avformat avutil swresample swscale; do
    llvm-strip --strip-debug -o "$OUT/$ABI/lib${lib}_mpv.so" "$SYSROOT$PREFIX/lib/lib${lib}_mpv.so"
  done
  llvm-strip --strip-debug -o "$OUT/$ABI/libmpv.so" "$SYSROOT$PREFIX/lib/libmpv.so"
  llvm-strip --strip-debug -o "$OUT/$ABI/libpictureshow_mpv.so" "$JBUILD/libpictureshow_mpv.so"
done

ABIS="$ABIS" FEATURES="$WORK" READELF="$TOOLCHAIN/bin/llvm-readelf" "$HERE/check.sh" "$OUT"
