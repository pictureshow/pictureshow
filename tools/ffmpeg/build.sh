#!/bin/bash
# Rebuilds the native libraries in media3-ffmpeg/src/main/jniLibs from pinned sources:
# FFmpeg (LGPL 2.1 or later, configured without GPL or nonfree parts) as shared libraries,
# and Media3's JNI wrapper (media3-ffmpeg/src/main/jni) linked against them.
#
# usage: NDK=<Android NDK r26b dir> WORK=<scratch dir> tools/ffmpeg/build.sh
# optional environment:
#   OUT       output jniLibs directory (default: media3-ffmpeg/src/main/jniLibs in this checkout)
#   CMAKE     cmake binary, with ninja on PATH (default: cmake)
#   ABIS      ABIs to build (default: "armeabi-v7a arm64-v8a x86 x86_64")
#   DECODERS  FFmpeg decoders to enable (default: the audio decoders Media3's FfmpegLibrary maps)
#   JOBS      make/ninja parallelism (default: nproc)
# Needs a Linux x86_64 host with bash, make, perl, curl, xz, sha256sum and strings.
# See tools/ffmpeg/README.md.
set -euo pipefail

FFMPEG_VERSION=6.0.1
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
FFMPEG_SHA256=9b16b8731d78e596b4be0d720428ca42df642bb2d78342881ff7f5bc29fc9623
ANDROID_API=23 # the app's minSdk

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
: "${NDK:?set NDK to the Android NDK r26b directory}"
: "${WORK:?set WORK to a scratch directory}"
OUT="${OUT:-$ROOT/media3-ffmpeg/src/main/jniLibs}"
CMAKE="${CMAKE:-cmake}"
ABIS="${ABIS:-armeabi-v7a arm64-v8a x86 x86_64}"
DECODERS="${DECODERS:-aac mp3 ac3 eac3 truehd mlp dca vorbis opus amrnb amrwb flac alac pcm_mulaw pcm_alaw}"
JOBS="${JOBS:-$(nproc)}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TOOLCHAIN/bin" ] || { echo "no NDK toolchain at $TOOLCHAIN" >&2; exit 1; }
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd)"

# Fetch and verify the source.
TARBALL="$WORK/ffmpeg-${FFMPEG_VERSION}.tar.xz"
[ -f "$TARBALL" ] || curl -fsSL -o "$TARBALL" "$FFMPEG_URL"
echo "$FFMPEG_SHA256  $TARBALL" | sha256sum -c -
export PATH="$TOOLCHAIN/bin:$PATH"

# Everything off except the decoders, libavcodec, libavutil and libswresample.
# No --enable-gpl, --enable-nonfree or --enable-version3: the result is LGPL 2.1 or later.
COMMON=(
  --target-os=android
  --enable-cross-compile
  --disable-static
  --enable-shared
  --disable-doc
  --disable-programs
  --disable-everything
  --disable-autodetect
  --disable-network
  --disable-avdevice
  --disable-avformat
  --disable-swscale
  --disable-postproc
  --disable-avfilter
  --disable-symver
  --enable-swresample
  --disable-v4l2-m2m
  --disable-vulkan
)
for d in $DECODERS; do COMMON+=("--enable-decoder=$d"); done

for ABI in $ABIS; do
  case "$ABI" in
    armeabi-v7a) TRIPLE=armv7a-linux-androideabi; ARCH=(--arch=arm --cpu=armv7-a)
                 CFLAGS="-march=armv7-a -mfloat-abi=softfp"; LDFLAGS="-Wl,--fix-cortex-a8" ;;
    arm64-v8a)   TRIPLE=aarch64-linux-android; ARCH=(--arch=aarch64 --cpu=armv8-a); CFLAGS=""; LDFLAGS="" ;;
    x86)         TRIPLE=i686-linux-android; ARCH=(--arch=x86 --cpu=i686 --disable-asm); CFLAGS=""; LDFLAGS="" ;;
    x86_64)      TRIPLE=x86_64-linux-android; ARCH=(--arch=x86_64 --cpu=x86-64 --disable-asm); CFLAGS=""; LDFLAGS="" ;;
    *) echo "unknown ABI $ABI" >&2; exit 1 ;;
  esac
  LDFLAGS="$LDFLAGS -Wl,-z,max-page-size=16384"
  # FFmpeg embeds its configure line in the libraries (avcodec_configuration()), and in-tree
  # builds keep source paths relative, so tools come from PATH and nothing names a local directory.
  DEST="$WORK/install/$ABI"
  PREFIX="$DEST/usr/local"
  BUILD="$WORK/build/$ABI"
  rm -rf "$DEST" "$BUILD"
  mkdir -p "$BUILD"
  tar -xJf "$TARBALL" -C "$BUILD" --strip-components=1
  echo "== FFmpeg $FFMPEG_VERSION for $ABI"
  (
    cd "$BUILD"
    ./configure \
      "${ARCH[@]}" \
      --cc="${TRIPLE}${ANDROID_API}-clang" \
      --cxx="${TRIPLE}${ANDROID_API}-clang++" \
      --nm=llvm-nm \
      --ar=llvm-ar \
      --ranlib=llvm-ranlib \
      --strip=llvm-strip \
      --extra-cflags="$CFLAGS" \
      --extra-ldflags="$LDFLAGS" \
      "${COMMON[@]}" > configure.log 2>&1
    make -j"$JOBS" > make.log 2>&1
    make install-libs install-headers DESTDIR="$DEST" > install.log 2>&1
  )

  echo "== JNI wrapper for $ABI"
  JBUILD="$WORK/build/jni-$ABI"
  rm -rf "$JBUILD"
  "$CMAKE" -S "$ROOT/media3-ffmpeg/src/main/jni" -B "$JBUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$ANDROID_API" \
    -DCMAKE_BUILD_TYPE=Release -DFFMPEG_INSTALL="$PREFIX" > "$JBUILD.cmake.log" 2>&1
  "$CMAKE" --build "$JBUILD" -j "$JOBS" > "$JBUILD.ninja.log" 2>&1

  # Keep symbol tables in all prebuilts for release crash symbolication. AGP strips APK copies
  # and extracts SYMBOL_TABLE bundle metadata; check.sh also uses the FFmpeg decoder symbols.
  mkdir -p "$OUT/$ABI"
  rm -f "$OUT/$ABI"/*.so
  for lib in avcodec avutil swresample; do
    llvm-strip --strip-debug -o "$OUT/$ABI/lib$lib.so" "$BUILD/lib$lib/lib$lib.so"
  done
  llvm-strip --strip-debug -o "$OUT/$ABI/libffmpegJNI.so" "$JBUILD/libffmpegJNI.so"
done

ABIS="$ABIS" READELF="$TOOLCHAIN/bin/llvm-readelf" "$HERE/check.sh" "$OUT"
