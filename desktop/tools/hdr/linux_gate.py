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
app_diagnostic_readbacks=False
# Separate diagnostic receipts. These never substitute for compositor captures.
for mode in ('hdr','sdr'):
 for index,media in enumerate(('pq','sdr'),1):
  folder=root/(mode+'-output')/media
  direct_path=root/(mode+'-output')/f'direct-compositor-{index}.json'
  if direct_path.exists():
   direct=json.loads(direct_path.read_text())
   if mode=='sdr':
    direct['samples']=[{'name':v['name'],'rgb':[80*max(c,0)**2.2 for c in v['rgb']]} for v in direct['samples']]
    direct['encoding']='nits'
   verdict=analyze(manifest,media,direct)
   (folder/'direct-compositor-verdict.json').write_text(json.dumps(verdict,indent=2)+'\n')
  app_path=folder/'app-backbuffer.json'
  if app_path.exists():
   verdict=analyze(manifest,media,json.loads(app_path.read_text()))
   (folder/'app-backbuffer-verdict.json').write_text(json.dumps(verdict,indent=2)+'\n')
for mode in ('hdr','sdr'):
 log=(root/(mode+'-output')/'weston.log').read_text()
 if 'GL renderer: softpipe' not in log or 'uses ABGR16161616F shadow.' not in log:
  raise RuntimeError('Required observed Mesa softpipe / stock FP16 shadow compositor receipts missing')
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
  app_receipt=(folder/'native.txt').read_text()
  if 'GL_RENDERER=softpipe' not in app_receipt:
   raise RuntimeError('Required observed app Mesa softpipe renderer receipt missing')
  app_diagnostic_readbacks |= 'explicit-HDR-diagnostic=1' in app_receipt
  protocol_capture=json.loads((folder/'samples.json').read_text())
  # The independent frame-signal observer reads the stock compositor's actual
  # final FBO synchronously. Protocol/PBO screenshots remain retained evidence.
  direct_path=root/(mode+'-output')/f'direct-compositor-{1 if media=="pq" else 2}.json'
  capture=json.loads(direct_path.read_text()) if direct_path.exists() else protocol_capture
  if direct_path.exists():
   if capture.get('glError')!=0 or not isinstance(capture.get('fbo'),int) or capture['fbo']<=0 or capture.get('width')!=640 or capture.get('height')!=360:
    raise RuntimeError('Direct output FBO observer returned invalid graphics state')
   if capture.get('encoding')!=('pq' if mode=='hdr' else 'gamma2.2') or capture.get('primaries')!=('bt2020' if mode=='hdr' else 'bt709'):
    raise RuntimeError('Direct output FBO observer encoding differs from actual output profile')
  if mode=='sdr':
   capture['samples']=[{'name':s['name'],'rgb':[80*max(v,0)**2.2 for v in s['rgb']]} for s in capture['samples']]
   capture['encoding']='nits'
  verdict=analyze(manifest,media,capture)
  if mode=='sdr' and media=='pq':
   if verdict['pass'] or verdict['hdrAboveSdrWhite']:
    raise RuntimeError('SDR compositor control unexpectedly qualified as HDR')
   peak=max(max(patch['observedNits']) for patch in verdict['patches'])
   if not 60 <= peak <= 83:
    raise RuntimeError('SDR negative control must contain visible content capped at the declared 80 nit white')
   verdict['expectedRejection']=True
   verdict['negativePeakNits']=peak
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
(root/'gate.json').write_text(json.dumps({'pass':True,'boundary':'Stock Weston headless final output framebuffer, synchronous FBO observer when available; 8-bit PQ','observedRenderer':'Mesa softpipe with stock ABGR16161616F shadow','appDiagnosticVideoReadbacks':app_diagnostic_readbacks,'results':results},indent=2)+'\n')
print(json.dumps({'pass':True,'positive':['hdr-output/pq','hdr-output/sdr','sdr-output/sdr'],'expectedRejection':['sdr-output/pq']}))
