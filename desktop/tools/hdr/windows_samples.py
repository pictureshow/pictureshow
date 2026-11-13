#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Read WGC float pixels (no image conversion); center-patch medians.
Usage: windows_samples.py CAPTURE_PREFIX FIXTURE_MANIFEST OUTPUT_JSON
"""
import json, math, statistics, struct, sys
from pathlib import Path
prefix, manifest_path, output = sys.argv[1:]
prefix = Path(prefix)
meta = json.loads(prefix.with_suffix('.json').read_text())
manifest = json.loads(Path(manifest_path).read_text())
width, height = meta['width'], meta['height']
data = prefix.with_suffix('.rgba16f').read_bytes()
if len(data) != width * height * 8:
    raise SystemExit('Capture byte count does not match declared RGBA16F bounds')
x, y, w, h = meta['videoBounds']
# Actual libmpv keepaspect viewport for 640x360; controls are away from center.
vw, vh = min(w, h * 16 / 9), min(h, w * 9 / 16)
x, y = x + (w - vw) / 2, y + (h - vh) / 2
names = ['black', 'gray100', 'gray203', 'gray1000', 'red100', 'green100', 'blue100', 'gray50']
patches = manifest.get('patches')
if isinstance(patches, list):
    names = [p['name'] for p in patches]
meta['boundary'] = meta.get('captureAPI', 'unknown') + ':' + meta.get('mode', 'swapchain')
meta['samples'] = []
for n, name in enumerate(names):
    cx, cy = int(x + (n + .5) * vw / len(names)), int(y + vh / 2)
    values = [[], [], []]
    for yy in range(cy - 3, cy + 4):
        for xx in range(cx - 3, cx + 4):
            if not (0 <= xx < width and 0 <= yy < height):
                raise SystemExit('Patch center outside captured pixels')
            rgb = struct.unpack_from('<4e', data, (yy * width + xx) * 8)[:3]
            if not all(math.isfinite(v) for v in rgb):
                raise SystemExit('Nonfinite captured color')
            for c, v in zip(values, rgb):
                c.append(v)
    meta['samples'].append({'name': name, 'rgb': [statistics.median(c) for c in values], 'samplePixels': 49})
Path(output).write_text(json.dumps(meta, indent=2) + '\n')
