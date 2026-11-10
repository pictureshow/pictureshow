#!/bin/sh
# SPDX-License-Identifier: MPL-2.0
set -eu
mkdir -p desktop/build/native
xcrun clang -dynamiclib -fobjc-arc -framework Cocoa desktop/src/native/mac_activate.m -o desktop/build/native/libpictureshow-mac.dylib
