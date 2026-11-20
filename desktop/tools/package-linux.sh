#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
set -euo pipefail
cd "$(dirname "$0")/../.."
[[ "$(uname -s)/$(uname -m)" == Linux/x86_64 ]] || { echo 'Build on x86_64 Linux.' >&2; exit 2; }
source desktop/tools/packaging/common.sh
output=${1:-desktop/build/linux-package}
version=${PICTURESHOW_VERSION:-1.0}
app_id=app.pictureshow.Pictureshow
mkdir -p "$output"
output=$(cd "$output" && pwd)
work=$(mktemp -d)
trap 'python3 -c "import shutil,sys; shutil.rmtree(sys.argv[1])" "$work"' EXIT
build="$work/native"
prefix="$build/prefix"
evidence="$output/evidence"
mkdir -p "$build/source" "$work/input" "$evidence"
export SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-$(git show -s --format=%ct HEAD)}
export PKG_CONFIG_PATH="$prefix/lib/pkgconfig:$prefix/share/pkgconfig"
export LD_LIBRARY_PATH="$prefix/lib"
bash desktop/tools/macos/build-render-deps.sh "$build" linux
for input in wayland-protocols.tar.gz weston.tar.gz; do
  packaging_input "$input" "$build/source"
  tar -xf "$build/source/$input" -C "$build/source"
done
meson setup "$build/protocols-build" "$build/source/wayland-protocols-1.46" --prefix="$prefix" -Dtests=false
meson install -C "$build/protocols-build"
ffmpeg_and_dav1d "$build" "$prefix" linux
build_mpv "$build" "$prefix" -Dgl=enabled -Dplain-gl=enabled -Degl=enabled -Dalsa=enabled -Dpulse=enabled
bash desktop/tools/linux-wayland/build.sh "$prefix" "$build/source/weston-16.0.2" "$build/surface"
unset LD_LIBRARY_PATH

packaging_input temurin-linux.tar.gz "$work"
tar -xf "$work/temurin-linux.tar.gz" -C "$work"
export JAVA_HOME="$work/jdk-$sdk_version"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon :desktop:spikeRuntime
cp desktop/build/spike-runtime/lib/*.jar "$work/input/"
jpackage --type app-image --name Pictureshow --dest "$work" --input "$work/input" --main-jar desktop.jar \
  --main-class app.pictureshow.desktop.PictureshowLauncherKt --app-version "$version" \
  --vendor 'Legitimate LLC' --add-modules "$java_modules" \
  --java-options '--enable-native-access=ALL-UNNAMED' \
  --java-options '-Dcompose.interop.blending=true' \
  --java-options '-Dcompose.accessibility.enable=true' \
  --java-options '-Dpictureshow.playback.sources=$APPDIR/PlaybackSources'
app="$work/Pictureshow"
grep -Eq '(^|[ "])jdk.httpserver([ "]|$)' "$app/lib/runtime/release"
offer="$app/lib/app/PlaybackSources"
stage_source_offer "$build" "$offer"
cp desktop/tools/macos/render-sources.tsv "$offer/"
while IFS=$'\t' read -r folder archive _; do
  cp "$build/source/$archive" "$offer/"
  for notice in "$build/source/$folder/"{COPYING*,LICENSE*,LICENCE*}; do
    [[ ! -f "$notice" ]] || cp "$notice" "$offer/$folder-${notice##*/}"
  done
done < desktop/tools/macos/render-sources.tsv
cp "$build/source/freetype-2.14.1/docs/FTL.TXT" "$offer/freetype-FTL.TXT"
cp "$build/source/wayland-protocols-1.46/COPYING" "$offer/wayland-protocols-COPYING"
cp "$build/source/weston-16.0.2/COPYING" "$offer/weston-COPYING"
cp "$app/lib/runtime/legal/java.base/LICENSE" "$offer/Temurin-LICENSE"
python3 desktop/tools/packaging/audit.py --target "$app/lib/pictureshow" --evidence "$offer" --prefix "$prefix/lib" \
  --binary "$build/surface/libpictureshow_wayland.so"
for binary in "$app/lib/pictureshow/"*.so*; do
  patchelf --set-rpath '$ORIGIN' "$binary"
done
LD_LIBRARY_PATH="$app/lib/pictureshow" python3 desktop/tools/packaging/audit.py --target "$app/lib/pictureshow" --evidence "$offer"
# AppDir and Flatpak read the same executable, libraries, desktop entry and icon.
install -Dm755 desktop/tools/linux/AppRun "$app/AppRun"
install -Dm644 "desktop/tools/linux/$app_id.desktop" "$app/share/applications/$app_id.desktop"
install -Dm644 "desktop/tools/linux/$app_id.metainfo.xml" "$app/share/metainfo/$app_id.metainfo.xml"
for row in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  size=${row#*:}
  install -Dm644 "app/src/main/res/mipmap-${row%:*}/ic_launcher.png" \
    "$app/share/icons/hicolor/${size}x${size}/apps/$app_id.png"
done
# 128 is the Flathub minimum standard icon size.
convert "$app/share/icons/hicolor/192x192/apps/$app_id.png" -resize 128x128 \
  "$work/$app_id.png"
install -Dm644 "$work/$app_id.png" "$app/share/icons/hicolor/128x128/apps/$app_id.png"
cp "$app/share/applications/$app_id.desktop" "$app/"
cp "$app/share/icons/hicolor/192x192/apps/$app_id.png" "$app/$app_id.png"
ln -s "$app_id.png" "$app/.DirIcon"
cp "$app/lib/runtime/release" "$evidence/java-runtime-modules.txt"
cp "$offer/"{library-imports.json,ffmpeg-runtime-config.json,mpv-buildoptions.json} "$evidence/"
packaging_input appimagetool "$work"
packaging_input appimage-runtime "$work"
chmod +x "$work/appimagetool"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$work/appimagetool" --runtime-file "$work/appimage-runtime" \
  "$app" "$output/Pictureshow-$version-x86_64.AppImage"
mkdir "$output/flatpak"
cp "desktop/tools/linux/$app_id.yml" "$output/flatpak/"
mv "$app" "$output/flatpak/payload"
packaging_input temurin-sources.tar.gz "$output"
(
  cd "$output"
  sha256sum "./Pictureshow-$version-x86_64.AppImage" ./temurin-sources.tar.gz > SHA256SUMS
)
