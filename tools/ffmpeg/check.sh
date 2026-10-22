#!/bin/bash
# Checks the prebuilt FFmpeg libraries: every library for every ABI, FFmpeg's embedded configure
# line has no --enable-gpl, --enable-nonfree or --enable-version3, the license string is LGPL 2.1,
# every expected decoder is compiled in, the JNI library links FFmpeg dynamically, and the libraries
# use unversioned sonames and 16 KB-aligned segments.
#
# usage: tools/ffmpeg/check.sh [jniLibs dir]   (default: media3-ffmpeg/src/main/jniLibs)
# optional environment: ABIS, EXPECTED_DECODERS, READELF (llvm-readelf or GNU readelf)
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DIR="${1:-$HERE/../../media3-ffmpeg/src/main/jniLibs}"
ABIS="${ABIS:-armeabi-v7a arm64-v8a x86 x86_64}"
EXPECTED_DECODERS="${EXPECTED_DECODERS:-aac mp3 ac3 eac3 truehd mlp dca vorbis opus amrnb amrwb flac alac pcm_mulaw pcm_alaw}"
READELF="${READELF:-readelf}"
LIBS="libavcodec.so libavutil.so libswresample.so libffmpegJNI.so"
fail=0
bad() { echo "FAIL $*"; fail=1; }

for ABI in $ABIS; do
  d="$DIR/$ABI"
  for lib in $LIBS; do
    [ -s "$d/$lib" ] || { bad "$ABI: missing $lib"; continue; }
    if [ "$lib" != libffmpegJNI.so ]; then
      soname=$("$READELF" -d "$d/$lib" | sed -n 's/.*(SONAME).*\[\(.*\)\].*/\1/p')
      [ "$soname" = "$lib" ] || bad "$ABI/$lib: soname '$soname'"
    fi
    align=$("$READELF" -lW "$d/$lib" | awk '$1=="LOAD"{print $NF}' | sort -u)
    for a in $align; do [ $((a)) -ge 16384 ] || bad "$ABI/$lib: LOAD alignment $a below 16 KB"; done
  done
  [ -s "$d/libavcodec.so" ] || continue

  needed=$("$READELF" -d "$d/libffmpegJNI.so" | sed -n 's/.*(NEEDED).*\[\(.*\)\].*/\1/p' | tr '\n' ' ')
  for lib in libavcodec.so libavutil.so libswresample.so; do
    case " $needed" in *" $lib "*) ;; *) bad "$ABI: libffmpegJNI.so does not link $lib dynamically (NEEDED: $needed)";; esac
  done

  for lib in libavcodec.so libavutil.so libswresample.so; do
    text=$(strings -n 8 "$d/$lib")
    conf=$(grep -e '--target-os=android' <<<"$text" | head -1)
    [ -n "$conf" ] || { bad "$ABI/$lib: no embedded configure line"; continue; }
    for flag in --enable-gpl --enable-nonfree --enable-version3; do
      case " $conf " in *" $flag "*) bad "$ABI/$lib: configured with $flag";; esac
    done
    name=${lib#lib}; name=${name%.so}
    grep -qx "lib$name license: LGPL version 2.1 or later" <<<"$text" \
      || bad "$ABI/$lib: license string is not 'LGPL version 2.1 or later'"
  done

  # Decoders are checked by their AVCodec symbols (ff_<name>_decoder, kept in .symtab by build.sh).
  # Their names alone prove nothing: libavcodec's codec descriptor table names every codec FFmpeg knows.
  symbols=$("$READELF" -sW "$d/libavcodec.so" | awk '{print $NF}' | grep -E '^ff_[a-z0-9_]+_(decoder|encoder|parser)$' | sort -u)
  [ -n "$symbols" ] || bad "$ABI: libavcodec.so has no ff_*_decoder symbols (stripped of .symtab?)"
  for dec in $EXPECTED_DECODERS; do
    grep -qx -- "ff_${dec}_decoder" <<<"$symbols" || bad "$ABI: decoder $dec not in libavcodec.so"
  done
  encoders=$(grep -E '_encoder$' <<<"$symbols" | tr '\n' ' ')
  [ -z "$encoders" ] || bad "$ABI: libavcodec.so has encoders: $encoders"
  decoders=$(sed -n 's/^ff_\(.*\)_decoder$/\1/p' <<<"$symbols" | tr '\n' ' ')
  # configure adds the parsers some decoders need (ac3 for eac3, mlp for truehd)
  parsers=$(sed -n 's/^ff_\(.*\)_parser$/\1/p' <<<"$symbols" | tr '\n' ' ')
  [ $fail = 0 ] && echo "ok $ABI: $(cd "$d" && ls -l $LIBS | awk '{printf "%s=%s ", $NF, $5}')" && echo "   decoders: $decoders; parsers: $parsers"
done

if [ $fail = 0 ]; then
  text=$(strings -n 8 "$DIR/${ABIS%% *}/libavcodec.so")
  echo "configure: $(grep -e '--target-os=android' <<<"$text" | head -1)"
  echo "PASS"
else
  echo "check.sh found problems"
fi
exit $fail
