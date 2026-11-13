#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Fail closed unless PQ and SDR survive actual HDR desktop composition.
Usage: windows_verdict.py EVIDENCE FIXTURE_MANIFEST
App swapchain and per-window capture are diagnostic partial proofs only.
"""
import json
from pathlib import Path
import sys
from analyze import analyze

root, manifest = Path(sys.argv[1]), json.loads(Path(sys.argv[2]).read_text())
rows = {}
for kind in ('pq', 'sdr'):
    for mode in ('swapchain', 'window', 'display'):
        path = root / f'hdr-{kind}' / f'{mode}-samples.json'
        key = f'{kind}-{mode}'
        if path.exists():
            try:
                rows[key] = analyze(manifest, kind, json.loads(path.read_text()))
            except (ValueError, KeyError, TypeError) as error:
                rows[key] = {'pass': False, 'error': str(error)}
        else:
            rows[key] = {'pass': False, 'error': 'No independent pixel capture'}
capability = []
path = root / 'display-after.jsonl'
if path.exists():
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        try:
            capability.append(json.loads(line))
        except json.JSONDecodeError:
            pass
hdr = any(x.get('hdrEnabled') == 1 and x.get('hdrSupported') == 1 for x in capability)
# Also require the actual app output to be the PQ BT2020 HDR desktop, not
# a different enabled monitor. Numeric display capture must match independently.
actual_hdr = []
for kind in ('pq', 'sdr'):
    receipt = root / f'hdr-{kind}' / '01-window-native.json'
    d = json.loads(receipt.read_text()) if receipt.exists() else {}
    actual_hdr.append(d.get('outputColorSpace') == 12)
passed = hdr and all(actual_hdr) and all(rows[f'{k}-display']['pass'] for k in ('pq', 'sdr'))
result = {'pass': passed, 'hdrOutputEnabled': hdr, 'appOnHdrOutput': actual_hdr,
          'outputCapability': capability, 'captures': rows,
          'claim': 'Numeric compositor boundary only; no physical HDMI/photometry claim',
          'missing': [] if passed else ['Need enabled HDR desktop on the actual app monitor and matching PQ+SDR FP16 display captures']}
(root / 'windows-verdict.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({'pass': passed, 'hdrOutputEnabled': hdr, 'appOnHdrOutput': actual_hdr,
                  'capturePasses': {k: v['pass'] for k, v in rows.items()}}, indent=2))
sys.exit(0 if passed else 1)
