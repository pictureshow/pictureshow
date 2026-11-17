#!/bin/bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
#
# Checks the prebuilt mpv libraries, from the binaries themselves: every library for every ABI with an unversioned
# soname (FFmpeg's carry the _mpv suffix); every LOAD segment 16 KB aligned; FFmpeg's license string is LGPL 2.1 or
# later and its embedded configure line has no --enable-gpl, --enable-version3 or --enable-nonfree; mpv's embedded
# feature list has gpl off; libpictureshow_mpv.so needs libmpv.so; nothing needs a library the APK doesn't package or
# Android doesn't provide; and no string names a local path or host. Prints sizes per library and per ABI.
#
# usage: tools/mpv/check.sh [jniLibs dir]   (default: mpv/src/main/jniLibs)
# optional environment: ABIS, READELF (llvm-readelf or GNU readelf)
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DIR="${1:-$HERE/../../mpv/src/main/jniLibs}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
READELF="${READELF:-readelf}"
FFMPEG="libavcodec_mpv.so libavfilter_mpv.so libavformat_mpv.so libavutil_mpv.so libswresample_mpv.so libswscale_mpv.so"
LIBS="$FFMPEG libmpv.so libpictureshow_mpv.so"
SYSTEM="libandroid.so liblog.so libEGL.so libGLESv2.so libGLESv3.so libmediandk.so libOpenSLES.so libc.so libm.so libdl.so libz.so"
fail=0
bad() { echo "FAIL $*"; fail=1; }

for ABI in $ABIS; do
  d="$DIR/$ABI"
  total=0
  sizes=""
  for lib in $LIBS; do
    [ -s "$d/$lib" ] || { bad "$ABI: missing $lib"; continue; }
    soname=$("$READELF" -d "$d/$lib" | sed -n 's/.*(SONAME).*\[\(.*\)\].*/\1/p')
    [ "$soname" = "$lib" ] || bad "$ABI/$lib: soname '$soname'"
    align=$("$READELF" -lW "$d/$lib" | awk '$1=="LOAD"{print $NF}' | sort -u)
    [ -n "$align" ] || bad "$ABI/$lib: no LOAD segments"
    for a in $align; do [ $((a)) -ge 16384 ] || bad "$ABI/$lib: LOAD alignment $a below 16 KB"; done
    for need in $("$READELF" -d "$d/$lib" | sed -n 's/.*(NEEDED).*\[\(.*\)\].*/\1/p'); do
      case " $LIBS $SYSTEM " in *" $need "*) ;; *) bad "$ABI/$lib needs $need, which is neither packaged nor Android's";; esac
    done
    # Local paths and host names: build directories, home directories, mounted volumes, and the .local/.lan names.
    leak=$(strings -n 6 "$d/$lib" | grep -E '/home/|/Users/|/Volumes/|/fb/|/root/|[A-Za-z0-9-]+\.(local|lan|home\.arpa)\b' | head -3)
    [ -z "$leak" ] || bad "$ABI/$lib names a local path or host: $leak"
    size=$(stat -c %s "$d/$lib" 2>/dev/null || stat -f %z "$d/$lib")
    total=$((total + size)); sizes="$sizes $lib=$size"
  done
  [ -s "$d/libavcodec_mpv.so" ] && [ -s "$d/libmpv.so" ] && [ -s "$d/libpictureshow_mpv.so" ] || continue

  needed=" $("$READELF" -d "$d/libpictureshow_mpv.so" | sed -n 's/.*(NEEDED).*\[\(.*\)\].*/\1/p' | tr '\n' ' ')"
  for lib in libmpv.so libavcodec_mpv.so; do
    case "$needed" in *" $lib "*) ;; *) bad "$ABI: libpictureshow_mpv.so does not need $lib (NEEDED:$needed)";; esac
  done

  for lib in $FFMPEG; do
    text=$(strings -n 8 "$d/$lib")
    conf=$(grep -e '--target-os=android' <<<"$text" | head -1)
    [ -n "$conf" ] || { bad "$ABI/$lib: no embedded configure line"; continue; }
    for flag in --enable-gpl --enable-nonfree --enable-version3; do
      case " $conf " in *" $flag "*) bad "$ABI/$lib: configured with $flag";; esac
    done
    name=${lib#lib}; name=${name%_mpv.so}
    grep -qx "lib$name license: LGPL version 2.1 or later" <<<"$text" \
      || bad "$ABI/$lib: license string is not 'LGPL version 2.1 or later'"
  done

  # mpv's audio passthrough needs FFmpeg's spdif muxer; its options are named only in the muxer (spdifenc.c).
  [ "$(strings -n 8 "$d/libavformat_mpv.so" | grep -cx -e 'dtshd_rate' -e 'spdif_flags')" = 2 ] \
    || bad "$ABI: libavformat_mpv.so lacks the spdif muxer (mpv's audio passthrough)"

  # libmpv embeds its meson options (the "configuration" property): -Dgpl=false must be among them. The enabled
  # features come from meson's own summary, which build.sh keeps as $FEATURES/mpv-features-<abi>.txt; a GPL build
  # lists "gpl" there.
  [ "$(strings -n 8 "$d/libmpv.so" | grep -c -e '-Dgpl=false')" -gt 0 ] || bad "$ABI: libmpv.so was not configured with -Dgpl=false"
  features=$(cat "${FEATURES:-/nonexistent}/mpv-features-$ABI.txt" 2>/dev/null)
  [ -n "$features" ] || bad "$ABI: no mpv feature summary"
  case " $features " in *" gpl "*) bad "$ABI: libmpv.so was built with gpl";; esac
  for want in audiotrack egl-android libass libplacebo android-media-ndk; do
    case " $features " in *" $want "*) ;; *) bad "$ABI: libmpv.so lacks $want";; esac
  done

  [ $fail = 0 ] && echo "ok $ABI:$sizes total=$total gzip9=$(cat "$d"/*.so | gzip -9 | wc -c | tr -d ' ')"
done

if [ $fail = 0 ]; then
  first="$DIR/${ABIS%% *}"
  echo "ffmpeg configure: $(strings -n 8 "$first/libavcodec_mpv.so" | grep -e '--target-os=android' | head -1)"
  echo "mpv features: $(cat "$FEATURES/mpv-features-${ABIS%% *}.txt")"
  echo "PASS"
else
  echo "check.sh found problems"
fi
exit $fail
