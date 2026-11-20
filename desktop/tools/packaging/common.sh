#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
# Shared by the Linux and Windows package entry points, from the repository root.
source desktop/tools/macos/native-sources.sh
readonly sdk_version='21.0.13+9'
readonly java_modules=java.se,jdk.charsets,jdk.crypto.ec,jdk.httpserver,jdk.unsupported,jdk.localedata
readonly jobs=${PICTURESHOW_BUILD_JOBS:-4}

fetch_pin() {
  local filename=$1 digest=$2 url=$3 directory=$4
  if [[ ! -f "$directory/$filename" ]]; then
    curl -fsSL --retry 2 "$url" -o "$directory/$filename"
  fi
  printf '%s  %s\n' "$digest" "$directory/$filename" | sha256sum -c -
}

packaging_input() {
  local wanted=$1 directory=$2 filename digest url
  while IFS=$'\t' read -r filename digest url; do
    if [[ "$filename" == "$wanted" ]]; then
      fetch_pin "$filename" "$digest" "${url%$'\r'}" "$directory"
      return
    fi
  done < desktop/tools/packaging/inputs.tsv
  echo "Missing build input pin for $wanted" >&2
  return 1
}

ffmpeg_and_dav1d() {
  local build=$1 prefix=$2 system=$3
  local tls platform_opts=()
  case "$system" in
    linux) tls=--enable-gnutls ;;
    windows) tls=--enable-schannel; platform_opts+=(--target-os=mingw32 --arch=x86_64 --enable-d3d11va --enable-dxva2) ;;
  esac
  fetch_pin dav1d.tar.xz "$dav1d_sha256" "$dav1d_url" "$build/source"
  fetch_pin ffmpeg.tar.xz "$ffmpeg_sha256" "$ffmpeg_url" "$build/source"
  tar -xf "$build/source/dav1d.tar.xz" -C "$build/source"
  tar -xf "$build/source/ffmpeg.tar.xz" -C "$build/source"
  meson setup "$build/dav1d-build" "$build/source/dav1d-$dav1d_version" --prefix="$prefix" --libdir=lib \
    --wrap-mode=nodownload --buildtype=release -Ddefault_library=shared -Denable_tools=false -Denable_tests=false
  meson compile -C "$build/dav1d-build" -j "$jobs"
  meson install -C "$build/dav1d-build"
  (
    cd "$build/source/ffmpeg-$ffmpeg_version"
    ./configure --prefix="$prefix" --disable-gpl --disable-nonfree --disable-version3 \
      --enable-shared --disable-static --disable-programs --disable-doc --disable-debug \
      --disable-autodetect --enable-network --enable-libdav1d "$tls" "${platform_opts[@]}"
    make -j"$jobs"
    make install
  ) > "$build/ffmpeg-build.log" 2>&1 || { tail -80 "$build/ffmpeg-build.log"; return 1; }
  local cfg="$build/source/ffmpeg-$ffmpeg_version/config.h"
  for flag in GPL NONFREE VERSION3 STATIC; do grep -qx "#define CONFIG_$flag 0" "$cfg"; done
  grep -qx '#define CONFIG_SHARED 1' "$cfg"
  grep -qx '#define CONFIG_FFV1_DECODER 1' "$build/source/ffmpeg-$ffmpeg_version/config_components.h"
}

build_mpv() {
  local build=$1 prefix=$2
  shift 2
  fetch_pin mpv.tar.gz "$mpv_sha256" "$mpv_url" "$build/source"
  tar -xf "$build/source/mpv.tar.gz" -C "$build/source"
  meson setup "$build/mpv-build" "$build/source/mpv-$mpv_version" --prefix="$prefix" --libdir=lib \
    --wrap-mode=nodownload --buildtype=release --default-library=shared --auto-features=disabled \
    -Dprefer_static=false -Dgpl=false -Dlibmpv=true -Dcplayer=false -Dlua=disabled "$@"
  meson compile -C "$build/mpv-build" -j "$jobs"
  meson install -C "$build/mpv-build"
}

stage_source_offer() {
  local build=$1 offer=$2
  mkdir -p "$offer"
  cp "$build/source/"{ffmpeg.tar.xz,mpv.tar.gz,dav1d.tar.xz} "$offer/"
  cp "$build/source/ffmpeg-$ffmpeg_version/COPYING.LGPLv2.1" "$offer/"
  cp "$build/source/dav1d-$dav1d_version/COPYING" "$offer/dav1d-COPYING"
  cp "$build/source/mpv-$mpv_version/Copyright" "$offer/mpv-Copyright"
  cp "$build/source/mpv-$mpv_version/LICENSE.LGPL" "$offer/mpv-LICENSE.LGPL"
  cp "$build/source/ffmpeg-$ffmpeg_version/"{config.h,config_components.h} "$offer/"
  cp "$build/mpv-build/meson-info/intro-buildoptions.json" "$offer/mpv-buildoptions.json"
  cp desktop/tools/macos/native-sources.sh desktop/tools/packaging/{inputs.tsv,LICENSE-INVENTORY.md} "$offer/"
  git rev-parse HEAD > "$offer/BUILD-SOURCE.txt"
}
