#!/bin/bash
# Generates the synthetic clips the media3-ffmpeg instrumented tests play: two seconds of sine tones,
# a different frequency on every channel (60 Hz on LFE), encoded with the ffmpeg CLI.
#
# usage: WORK=<scratch dir> tools/ffmpeg/make-test-clips.sh [output dir]
#        (default output: media3-ffmpeg/src/androidTest/assets/clips)
# Needs ffmpeg (with libopus, libvorbis and libmp3lame), python3, curl, xz, gcc, make and patch.
# E-AC-3 7.1 needs an encoder that writes dependent substreams, which released FFmpeg lacks, so the
# script builds a small host ffmpeg from the pinned FFmpeg source with test-clips/eac3-dependent-substream.patch.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/../../media3-ffmpeg/src/androidTest/assets/clips}"
: "${WORK:?set WORK to a scratch directory}"
FFMPEG_VERSION=6.0.1
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
FFMPEG_SHA256=9b16b8731d78e596b4be0d720428ca42df642bb2d78342881ff7f5bc29fc9623
mkdir -p "$OUT" "$WORK"
WORK="$(cd "$WORK" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FF=(ffmpeg -hide_banner -loglevel error -y)
D=2

# tone <channel layout> <frequencies...>: an aevalsrc input, one sine per channel.
tone() {
  local layout=$1; shift
  local expr="" f
  for f in "$@"; do expr+="${expr:+|}0.4*sin(2*PI*$f*t)"; done
  echo "aevalsrc=${expr}:c=${layout}:s=48000:d=$D"
}
T51=$(tone '5.1(side)' 440 550 660 60 770 880)
T20=$(tone stereo 440 660)

"${FF[@]}" -f lavfi -i "$T51" -c:a ac3 -b:a 448k "$OUT/ac3_5.1.mkv"
"${FF[@]}" -f lavfi -i "$T51" -c:a dca -strict -2 "$OUT/dts_5.1.mkv"
"${FF[@]}" -f lavfi -i "$T51" -c:a truehd -strict -2 "$OUT/truehd_5.1.mkv"
"${FF[@]}" -f lavfi -i "$T51" -c:a flac -sample_fmt s32 -bits_per_raw_sample 24 "$OUT/flac_5.1_24bit.mkv"
"${FF[@]}" -f lavfi -i "$T51" -c:a alac -sample_fmt s16p "$OUT/alac_5.1.m4a"
"${FF[@]}" -f lavfi -i "$T20" -c:a libopus -b:a 128k "$OUT/opus_2.0.mkv"
"${FF[@]}" -f lavfi -i "$T20" -c:a libvorbis -q:a 4 "$OUT/vorbis_2.0.mkv"
"${FF[@]}" -f lavfi -i "$T20" -c:a libmp3lame -b:a 192k "$OUT/mp3_2.0.mkv"

# E-AC-3 7.1 is laid out the way 7.1 E-AC-3 is usually carried: a 5.1 independent substream plus a four-channel
# (2/2) dependent substream whose channel map (Ls, Rs, Lrs/Rrs; 0x1A00) replaces the side pair and adds
# the rear pair. The two are interleaved frame by frame.
HOSTFF="$WORK/host-ffmpeg/ffmpeg"
if [ ! -x "$HOSTFF" ]; then
  TARBALL="$WORK/ffmpeg-${FFMPEG_VERSION}.tar.xz"
  [ -f "$TARBALL" ] || curl -fsSL -o "$TARBALL" "$FFMPEG_URL"
  echo "$FFMPEG_SHA256  $TARBALL" | sha256sum -c -
  rm -rf "$WORK/host-ffmpeg"
  mkdir -p "$WORK/host-ffmpeg"
  tar -xJf "$TARBALL" -C "$WORK/host-ffmpeg" --strip-components=1
  (
    cd "$WORK/host-ffmpeg"
    patch -p1 < "$HERE/test-clips/eac3-dependent-substream.patch"
    ./configure --disable-everything --disable-doc --disable-ffprobe --disable-ffplay --disable-autodetect \
      --disable-x86asm --enable-swresample --enable-encoder=eac3 --enable-decoder=pcm_f64le --enable-muxer=eac3 --enable-protocol=file \
      --enable-indev=lavfi --enable-filter=aevalsrc,abuffer,abuffersink,aresample,aformat,anull > configure.log 2>&1
    make -j"${JOBS:-$(nproc)}" ffmpeg > make.log 2>&1
  )
fi
HFF=("$HOSTFF" -hide_banner -loglevel error -y)
"${HFF[@]}" -f lavfi -i "$T51" -c:a eac3 -b:a 384k -f eac3 "$TMP/main.eac3"
EAC3_DEPENDENT_CHANMAP=0x1A00 "${HFF[@]}" -f lavfi -i "$(tone 'quad(side)' 770 880 990 1100)" -c:a eac3 -b:a 256k \
  -f eac3 "$TMP/rear.eac3"
python3 "$HERE/test-clips/interleave_eac3.py" "$TMP/main.eac3" "$TMP/rear.eac3" "$TMP/71.eac3"
"${FF[@]}" -f eac3 -i "$TMP/71.eac3" -c:a copy "$OUT/eac3_7.1.mkv"

ls -l "$OUT"
