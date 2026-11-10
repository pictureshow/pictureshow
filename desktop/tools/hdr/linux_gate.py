#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Fail-closed pixel gate, including an actual SDR compositor negative control."""
import copy
import json
from pathlib import Path
import re
import sys
from analyze import analyze
manifest=json.loads(Path(sys.argv[1]).read_text())
root=Path(sys.argv[2])
results={}
for mode in ('hdr','sdr'):
 log=(root/(mode+'-output')/'weston.log').read_text()
 # Values come from weston_output_get_eotf_mode/get_colorimetry_mode at capture time.
 # ST2084=4, BT2020_RGB=8; SDR=1, default=1.
 matches=re.findall(r'HDR_NUMERIC_OUTPUT name=headless eotf=(\d+) colorimetry=(\d+) profile=([^\n]+)',log)
 if len(matches)!=2:
  raise RuntimeError('Two actual output profile receipts required per isolated compositor')
 expected=(4,8) if mode=='hdr' else (1,1)
 if any((int(e),int(c))!=expected for e,c,p in matches):
  raise RuntimeError('Unexpected compositor output encoding')
 for media in ('pq','sdr'):
  folder=root/(mode+'-output')/media
  capture=json.loads((folder/'samples.json').read_text())
  if mode=='sdr':
   capture['samples']=[{'name':s['name'],'rgb':[80*max(v,0)**2.2 for v in s['rgb']]} for s in capture['samples']]
   capture['encoding']='nits'
  verdict=analyze(manifest,media,capture)
  if mode=='sdr' and media=='pq':
   if verdict['pass'] or verdict['hdrAboveSdrWhite']:
    raise RuntimeError('SDR compositor control unexpectedly qualified as HDR')
   verdict['expectedRejection']=True
  elif mode=='sdr':
   # Ordinary SDR auto: uses an 80 nit output reference; preserve relative SDR appearance.
   reference=copy.deepcopy(manifest)
   for patch in reference['files']['sdr']['patches']:
    patch['quantizedRgbNits']=[v*80/203 for v in patch['quantizedRgbNits']]
   verdict=analyze(reference,media,capture)
   verdict['referenceWhiteAdaptation']='source203nit to SDR output80nit; no fitted normalization'
   if not verdict['pass']:
    raise RuntimeError('SDR source failed SDR output control: '+json.dumps(verdict))
  elif not verdict['pass']:
   (folder/'verdict.json').write_text(json.dumps(verdict,indent=2)+'\n')
   raise RuntimeError('HDR output numeric gate failed: '+json.dumps(verdict))
  (folder/'verdict.json').write_text(json.dumps(verdict,indent=2)+'\n')
  results[mode+'-output/'+media]=verdict
(root/'gate.json').write_text(json.dumps({'pass':True,'boundary':'Stock Weston headless composited framebuffer, 8-bit PQ output; llvmpipe','results':results},indent=2)+'\n')
print(json.dumps({'pass':True,'positive':['hdr-output/pq','hdr-output/sdr','sdr-output/sdr'],'expectedRejection':['sdr-output/pq']}))
