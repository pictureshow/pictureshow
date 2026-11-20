#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
set -euo pipefail

usage() {
  echo 'Usage: desktop/tools/package-macos.sh [--app-store | --unsigned] [--output DIR]'
  echo 'See desktop/tools/macos/README.md for build inputs and distribution steps.'
}

channel=developer-id
output=
while [[ $# -gt 0 ]]; do
  case "$1" in
    --app-store|--unsigned)
      [[ "$channel" == developer-id ]] || { usage >&2; exit 2; }
      channel=${1#--}
      shift ;;
    --output) output=${2:?Expected a directory after --output}; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

[[ "$(uname -s)" == Darwin ]] || { echo 'Build on macOS.' >&2; exit 2; }
repo=$(cd "$(dirname "$0")/../.." && pwd -P)
cd "$repo"
tools="$repo/desktop/tools/macos"
version=${PICTURESHOW_VERSION:-1.0}
build_number=${PICTURESHOW_BUILD_NUMBER:-1}
bundle_id=app.pictureshow
[[ "$version" =~ ^[1-9][0-9]*\.[0-9]+(\.[0-9]+)?$ && "$build_number" =~ ^[1-9][0-9]*(\.[0-9]+){0,2}$ ]] || {
  echo 'Use a dotted numeric marketing version and a positive numeric build number.' >&2; exit 2;
}

mkdir -p "${output:=$repo/desktop/build/macos-package}"
output=$(cd "$output" && pwd -P)
# Keep reports for every build, including the first failing command.
work=$(mktemp -d "$output/work-XXXXXX")
evidence="$work/evidence"
mkdir "$evidence"
echo "Packaging work and evidence: $work"

# FFmpeg configuration requires a build prefix without spaces.
alias_dir=$(mktemp -d)
trap 'python3 -c "from pathlib import Path; import sys; p=Path(sys.argv[1]); (p / \"native\").unlink(missing_ok=True); p.rmdir()" "$alias_dir"' EXIT
source "$tools/native-sources.sh"

verify_source() {
  printf '%s  %s\n' "$1" "$2" | shasum -a 256 -c
}
fetch_source() {
  local url=$1 digest=$2 dest=$3
  curl -fsSL --retry 2 "$url" -o "$dest"
  verify_source "$digest" "$dest"
}

jdk_version=21.0.13.1_2
jdk_tag=jdk-21.0.13.1%2B2
case "$(uname -m)" in
  arm64)
    jdk_arch=aarch64
    jdk_dir=jdk-21.0.13.1+2
    jdk_source_sha256=427e3eaec042f61c93258b5a42c86f3ddbad45a2ba95132dd4a3e3c07414bc8c
    jdk_sha256=48067f2b7c958ed104a9d8a5749be7d25220b0a356b4d2d1bcfaab3e16ad0b14 ;;
  x86_64)
    jdk_version=21.0.13_9
    jdk_tag=jdk-21.0.13%2B9
    jdk_arch=x64
    jdk_dir=jdk-21.0.13+9
    jdk_source_sha256=b5232c9980d8a0f0459676c084c41c6e2c771ed9e785890b03c6a438406ad1ca
    jdk_sha256=9aa1d1c17ae5467a022679e7e0af5c00797d94ac462ed1a46f4db07a8a34a6dd ;;
  *) echo 'Unsupported macOS architecture.' >&2; exit 2 ;;
esac
jdk_url="https://github.com/adoptium/temurin21-binaries/releases/download/$jdk_tag/OpenJDK21U-jdk_${jdk_arch}_mac_hotspot_$jdk_version.tar.gz"
if [[ -n "${PICTURESHOW_MAC_JDK_ARCHIVE:-}" ]]; then
  jdk_archive=$PICTURESHOW_MAC_JDK_ARCHIVE
  verify_source "$jdk_sha256" "$jdk_archive"
else
  jdk_archive="$work/temurin.tar.gz"
  fetch_source "$jdk_url" "$jdk_sha256" "$jdk_archive"
fi
tar -xf "$jdk_archive" -C "$work"
export JAVA_HOME="$work/$jdk_dir/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ -n "${PICTURESHOW_MAC_NATIVE_BUILD:-}" ]]; then
  native=$PICTURESHOW_MAC_NATIVE_BUILD
else
  native="$work/native"
  mkdir "$native"
  ln -s "$native" "$alias_dir/native"
  PICTURESHOW_NATIVE_BUILD="$alias_dir/native" PICTURESHOW_NATIVE_EVIDENCE="$evidence" PICTURESHOW_MAC_NATIVE_RELEASE=1 \
    bash desktop/tools/app-smoke/build-native.sh > "$evidence/native-build.log" 2>&1
fi
ffmpeg_config="$native/source/ffmpeg-$ffmpeg_version/config.h"
mpv_config="$native/mpv-source/build/meson-info/intro-buildoptions.json"
cp "$ffmpeg_config" "$evidence/ffmpeg-config.h"
cp "$mpv_config" "$evidence/mpv-buildoptions.json"
for option in GPL NONFREE VERSION3; do
  grep -qx "#define CONFIG_$option 0" "$ffmpeg_config"
done
grep -qx '#define CONFIG_FFV1_DECODER 1' "$native/source/ffmpeg-$ffmpeg_version/config_components.h"
python3 - "$mpv_config" <<'PY'
import json
import sys

with open(sys.argv[1]) as f:
    options = {option["name"]: option["value"] for option in json.load(f)}
if options.get("gpl") is not False or options.get("libmpv") is not True:
    raise SystemExit("Expected libmpv with the GPL features disabled")
PY
verify_source "$ffmpeg_sha256" "$native/source/ffmpeg.tar.xz"
verify_source "$mpv_sha256" "$native/mpv-source/mpv.tar.gz"
verify_source "$dav1d_sha256" "$native/source/dav1d.tar.xz"

./gradlew --no-daemon --max-workers=2 :desktop:spikeRuntime -Dorg.gradle.jvmargs=-Xmx2g > "$evidence/gradle.log" 2>&1
input="$work/input"
mkdir "$input"
for jar in desktop/build/spike-runtime/lib/*.jar; do
  # JOGL and GlueGen are used by stand-alone graphics probes. The installed
  # viewer uses the Objective-C libmpv surface instead.
  case "$(basename "$jar")" in jogl-*|gluegen-*) continue ;; esac
  cp "$jar" "$input/"
done
iconset="$work/Pictureshow.iconset"
mkdir "$iconset"
for size in 16 32 128 256 512; do
  for scale in 1 2; do
    pixels=$((size * scale))
    suffix=
    if [[ "$scale" == 2 ]]; then suffix=@2x; fi
    sips -z "$pixels" "$pixels" app/src/main/res/mipmap-xxxhdpi/ic_launcher.png \
      --out "$iconset/icon_${size}x$size$suffix.png" >/dev/null
  done
done
iconutil -c icns "$iconset" -o "$work/Pictureshow.icns"

java_options=(
  --java-options '--enable-native-access=ALL-UNNAMED'
  --java-options '--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED'
  --java-options '-Dcompose.application.configure.swing.globals=true'
  --java-options '-Dcompose.interop.blending=true'
  --java-options '-Dspike.mpv.library=$APPDIR/../Frameworks/libmpv.dylib'
  --java-options '-Dspike.layer.bridge=$APPDIR/../Frameworks/libpictureshow_layer.dylib'
  --java-options '-Dspike.window.bridge=$APPDIR/../Frameworks/libpictureshow_window.dylib'
  --java-options '-Dpictureshow.playback.sources=$APPDIR/../Resources/PlaybackSources'
)
if [[ "$channel" == app-store ]]; then
  java_options+=(--java-options '-Dpictureshow.storekit.library=$APPDIR/../Frameworks/libpictureshow_storekit.dylib')
fi
jpackage --type app-image --name Pictureshow --dest "$work" --input "$input" --main-jar desktop.jar \
  --main-class app.pictureshow.desktop.PictureshowLauncherKt --app-version "$version" --mac-package-identifier "$bundle_id" \
  --vendor 'Legitimate LLC' --icon "$work/Pictureshow.icns" \
  --add-modules java.se,jdk.charsets,jdk.crypto.ec,jdk.unsupported,jdk.localedata \
  "${java_options[@]}" > "$evidence/jpackage.log" 2>&1

app="$work/Pictureshow.app"
sources="$app/Contents/Resources/PlaybackSources"
mkdir "$sources"
cp "$native/source/ffmpeg.tar.xz" "$sources/ffmpeg-$ffmpeg_version.tar.xz"
cp "$native/mpv-source/mpv.tar.gz" "$sources/mpv-$mpv_version.tar.gz"
cp "$native/source/dav1d.tar.xz" "$sources/dav1d-$dav1d_version.tar.xz"
cp "$tools/render-only-videotoolbox.patch" "$tools/LICENSE-INVENTORY.md" "$sources/"
git rev-parse HEAD > "$sources/BUILD-SOURCE.txt"
git diff --quiet HEAD || echo 'The source tree had local changes when this app was built.' >> "$sources/BUILD-SOURCE.txt"
cp "$native/source/dav1d-$dav1d_version/COPYING" "$sources/dav1d-COPYING"
cp "$native/mpv-source/mpv-$mpv_version/Copyright" "$sources/mpv-Copyright"
cp "$native/source/ffmpeg-$ffmpeg_version/COPYING.LGPLv2.1" "$sources/COPYING.LGPLv2.1"
while IFS=$'\t' read -r folder archive sha url; do
  verify_source "$sha" "$native/source/$archive"
  cp "$native/source/$archive" "$sources/$archive"
done < "$tools/render-sources.tsv"
cp "$native/source/libplacebo-7.387.0/LICENSE" "$sources/libplacebo-LICENSE"
cp "$native/source/libass-0.17.4/COPYING" "$sources/libass-COPYING"
cp "$native/source/freetype-2.14.1/docs/FTL.TXT" "$sources/freetype-FTL.TXT"
cp "$native/source/fribidi-1.0.16/COPYING" "$sources/fribidi-COPYING"
cp "$native/source/harfbuzz-12.3.2/COPYING" "$sources/harfbuzz-COPYING"
cp "$native/source/libunibreak-6.1/LICENCE" "$sources/libunibreak-LICENCE"
cp "$native/source/glad-73db193f853e2ee079bf3ca8a64aa2eaf6459043/LICENSE" "$sources/glad-LICENSE"
printf 'Temurin binary source: %s\nSHA-256: %s\n' "$jdk_url" "$jdk_sha256" > "$evidence/java-pin.txt"

if [[ "$channel" != unsigned ]]; then
  java_sources="$output/Pictureshow-Temurin-$jdk_arch-$jdk_version-sources.tar.gz"
  if [[ -f "$java_sources" ]]; then
    verify_source "$jdk_source_sha256" "$java_sources"
  else
    java_source_url="https://github.com/adoptium/temurin21-binaries/releases/download/$jdk_tag/OpenJDK21U-jdk-sources_$jdk_version.tar.gz"
    fetch_source "$java_source_url" "$jdk_source_sha256" "$work/temurin-sources.tar.gz"
    mv -f "$work/temurin-sources.tar.gz" "$java_sources"
  fi
fi

profile=
entitlements="$tools/developer-id.entitlements"
case "$channel" in
  developer-id) identity=${PICTURESHOW_MAC_APP_IDENTITY:?Set the Developer ID Application signing identity} ;;
  unsigned) identity=- ;;
  app-store)
    identity=${PICTURESHOW_MAC_APP_IDENTITY:?Set the Apple Distribution signing identity}
    profile=${PICTURESHOW_MAC_PROFILE:?Set the Mac App Store provisioning profile path}
    cp "$profile" "$app/Contents/embedded.provisionprofile"
    entitlements="$work/app-store.entitlements"
    python3 "$tools/store-profile.py" "$profile" "$bundle_id" "$tools/app-store.entitlements" "$entitlements"
    xcrun swiftc -O -target "$(uname -m)-apple-macos15.0" -emit-library \
      -module-name PictureshowStoreKit -Xlinker -install_name -Xlinker @rpath/libpictureshow_storekit.dylib \
      "$tools/StoreKitBridge.swift" -o "$work/libpictureshow_storekit.dylib"
    ;;
esac

bundle_options=()
if [[ "$channel" == app-store ]]; then bundle_options+=(--store-bridge "$work/libpictureshow_storekit.dylib"); fi
python3 "$tools/bundle.py" "$app" "$native" "$evidence" \
  --identity "$identity" --entitlements "$entitlements" --build "$build_number" "${bundle_options[@]}"
codesign -dvvv --entitlements :- "$app" > "$evidence/app-entitlements.plist" 2> "$evidence/app-signature.txt"
case "$channel" in
  unsigned)
    echo "Local ad-hoc app image: $app"
    ;;
  app-store)
    installer=${PICTURESHOW_MAC_INSTALLER_IDENTITY:?Set the Mac App Store installer signing identity}
    productbuild --component "$app" /Applications --sign "$installer" "$work/Pictureshow.pkg"
    pkgutil --check-signature "$work/Pictureshow.pkg" | tee "$evidence/pkg-signature.txt"
    mv -f "$work/Pictureshow.pkg" "$output/Pictureshow.pkg"
    ;;
  developer-id)
    mkdir "$work/disk-image"
    ditto "$app" "$work/disk-image/Pictureshow.app"
    ln -s /Applications "$work/disk-image/Applications"
    dmg="$work/Pictureshow.dmg"
    hdiutil create -volname Pictureshow -srcfolder "$work/disk-image" -format UDZO -ov "$dmg"
    codesign --force --sign "$identity" --timestamp "$dmg"
    key_entry=${PICTURESHOW_MAC_NOTARY_SECRET:?Set the secrets broker entry for the App Store Connect key}
    secret exec "$key_entry" -- python3 "$tools/notarize.py" "$dmg" "$evidence"
    xcrun stapler staple "$dmg"
    xcrun stapler validate "$dmg" | tee "$evidence/staple.txt"
    spctl -a -vvv -t install "$dmg" 2>&1 | tee "$evidence/dmg-gatekeeper.txt"
    mv -f "$dmg" "$output/Pictureshow.dmg"
    ;;
esac
