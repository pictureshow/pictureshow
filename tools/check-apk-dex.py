#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Reject APKs that require DEX inflation during a cold process start."""
import json
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    dex = [entry for entry in apk.infolist() if entry.filename.endswith('.dex')]
    compressed = [entry.filename for entry in dex if entry.compress_type != zipfile.ZIP_STORED]
    print(json.dumps({'dex_files': len(dex), 'compressed_dex': compressed}))
    if not dex or compressed:
        sys.exit('DEX must be stored uncompressed to avoid inflation on the startup critical path')
