#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
# Run in an x64 UCRT64 shell with x64 MSVC, Windows SDK and WiX 3 on PATH.
set -euo pipefail
cd "$(dirname "$0")/../.."
[[ "${MSYSTEM:-}" == UCRT64 ]] || { echo 'Build in the x64 MSYS2 UCRT64 environment.' >&2; exit 2; }
source desktop/tools/packaging/common.sh
output="${1:-desktop/build/windows-package}"
mkdir -p "$output"
output=$(cd "$output" && pwd)
work=$(mktemp -d "$output/work-XXXXXX")
export SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-$(git show -s --format=%ct HEAD)}
bash desktop/tools/windows/build-native.sh "$work/native"
stage_source_offer "$work/native" "$work/PlaybackSources"
cp desktop/tools/windows/ucrt64-sources.tsv "$work/PlaybackSources/"
# The pinned libass binary package omits its upstream license file.
packaging_input windows-libass.tar.xz "$work/PlaybackSources"
tar -xOf "$work/PlaybackSources/windows-libass.tar.xz" libass-0.17.5/COPYING > "$work/PlaybackSources/libass-0.17.5-COPYING"
packaging_input temurin-windows.zip "$work"
unzip -q "$work/temurin-windows.zip" -d "$work"
packaging_input temurin-sources.tar.gz "$output"
MSYS2_ARG_CONV_EXCL='*' pwsh -NoProfile -File desktop/tools/windows/assemble.ps1 \
  -Work "$(cygpath -m "$work")" -Output "$(cygpath -m "$output")" -Jdk "$(cygpath -m "$work/jdk-$sdk_version")" \
  -Modules "$java_modules"
