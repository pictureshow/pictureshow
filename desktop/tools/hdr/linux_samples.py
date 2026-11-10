#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Read only numeric values, preserving actual eight-bit compositor capture codes."""
import json
from pathlib import Path
import statistics
import sys
manifest=json.loads(Path(sys.argv[1]).read_text())
root=Path(sys.argv[2])
for mode in ('hdr','sdr'):
 for media in ('pq','sdr'):
  folder=root/(mode+'-output')/media
  data=(folder/'numeric.ppm').read_bytes()
  magic, dimensions, maximum, pixels=data.split(b'\n',3)
  assert magic==b'P6' and maximum==b'255'
  width,height=map(int,dimensions.split())
  assert len(pixels)==width*height*3
  samples=[]
  for patch in manifest['files'][media]['patches']:
   x,y=[int(v*n) for v,n in zip(patch['center'],[width,height])]
   channels=[[pixels[(yy*width+xx)*3+c]/255 for yy in range(y-2,y+3) for xx in range(x-2,x+3)] for c in range(3)]
   samples.append({'name':patch['name'],'rgb':[statistics.median(v) for v in channels]})
  result={'encoding':'pq' if mode=='hdr' else 'gamma2.2','primaries':'bt2020' if mode=='hdr' else 'bt709','referenceWhiteNits':203 if mode=='hdr' else 80,'capture':'Weston composited FRAMEBUFFER, stock 8-bit RGB; quantized PQ, not 10-bit output proof','width':width,'height':height,'samples':samples}
  (folder/'samples.json').write_text(json.dumps(result,indent=2)+'\n')
  print(json.dumps({'output':mode,'media':media,'samples':samples}))
