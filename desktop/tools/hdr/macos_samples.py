#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Sample actual drawable readback. Usage: macos_samples.py PREFIX OUTPUT.json"""
import json
from pathlib import Path
import statistics
import struct
import sys
from fixture import NAMES
prefix,output=Path(sys.argv[1]),Path(sys.argv[2])
meta=json.loads(prefix.with_suffix('.json').read_text())
w,h=meta['width'],meta['height'];data=prefix.with_suffix('.rgba16f').read_bytes()
assert len(data)==w*h*8
samples=[]
for i,name in enumerate(NAMES):
    channels=[[],[],[]]
    for dy in range(-10,11):
        for dx in range(-10,11):
            x,y=round((i+.5)*w/8)+dx,h//2+dy
            rgb=struct.unpack_from('<4e',data,(y*w+x)*8)
            for c in range(3):channels[c].append(rgb[c])
    samples.append({'name':name,'rgb':[statistics.median(c) for c in channels]})
meta['samples']=samples
output.write_text(json.dumps(meta,indent=2)+'\n')
