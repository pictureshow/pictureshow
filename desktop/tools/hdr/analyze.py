#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Compare an independent capture's flat-bar medians against generated source.
Usage: analyze.py MANIFEST pq|sdr CAPTURE [--relative .06] [--absolute 3]
Capture schema: {encoding:linear|pq|nits, primaries:bt2020|bt709,
referenceWhiteNits:80|203, samples:[{name:gray100,rgb:[.5,.5,.5]},...]}.
No automatic fitting/normalizing: referenceWhiteNits is an explicit contract.
A pass validates pixels at the capture boundary only; source metadata and output
capability receipts are separate mandatory evidence for an end-to-end verdict.
"""
import argparse
import json
import math
from pathlib import Path
from fixture import pq_decode

# BT.2020 -> BT.709, both linear/D65. Negative scRGB channels are valid.
TO709=((1.660491,-.587641,-.072850),(-.124550,1.132900,-.008349),(-.018151,-.100579,1.118730))
TO2020=((.627404,.329283,.043313),(.069097,.919540,.011362),(.016391,.088013,.895595))

def transform(v,m):
    return [sum(x*y for x,y in zip(row,v)) for row in m]

def analyze(manifest, kind, capture, relative=.06, absolute=3):
    if kind not in ('pq','sdr'):
        raise ValueError('unknown source')
    if capture.get('encoding') not in ('linear','pq','nits') or capture.get('primaries') not in ('bt2020','bt709'):
        raise ValueError('capture requires known encoding and primaries')
    if capture['encoding']=='linear' and (not isinstance(capture.get('referenceWhiteNits'),(int,float)) or not math.isfinite(capture['referenceWhiteNits']) or capture['referenceWhiteNits']<=0):
        raise ValueError('linear capture requires a positive finite reference white')
    expected=manifest['files'][kind]
    samples=capture.get('samples',[])
    if len(samples)!=8 or len({s['name'] for s in samples})!=8:
        raise ValueError('exactly eight distinct named patch medians required')
    got={s['name']:s['rgb'] for s in samples}
    if set(got)!={p['name'] for p in expected['patches']}:
        raise ValueError('unexpected/missing patch names')
    rows=[]
    for patch in expected['patches']:
        rgb=got[patch['name']]
        if len(rgb)!=3 or not all(isinstance(x,(int,float)) and math.isfinite(x) for x in rgb):
            raise ValueError('nonfinite/malformed RGB sample')
        nits=([pq_decode(x) for x in rgb] if capture['encoding']=='pq' else
              [x*capture['referenceWhiteNits'] for x in rgb] if capture['encoding']=='linear' else rgb)
        target=patch['quantizedRgbNits']
        if expected['primaries']!=capture['primaries']:
            target=transform(target,TO709 if capture['primaries']=='bt709' else TO2020)
        errors=[abs(a-b) for a,b in zip(nits,target)]
        limits=[max(absolute,abs(x)*relative) for x in target]
        rows.append({'name':patch['name'],'observedNits':nits,'expectedNits':target,'absoluteErrors':errors,
                     'limits':limits,'pass':all(e<=t for e,t in zip(errors,limits))})
    # A tone-mapped/clamped SDR result must fail even if the rest looks right.
    hdr=next(r for r in rows if r['name']=='gray1000')
    above=kind!='pq' or min(hdr['observedNits'])>manifest['sdrReferenceWhiteNits']*2
    return {'pass':all(r['pass'] for r in rows) and above,'boundary':capture.get('boundary','UNSPECIFIED'),
            'source':kind,'captureEncoding':capture['encoding'],'capturePrimaries':capture['primaries'],
            'hdrAboveSdrWhite':above,'relativeTolerance':relative,'absoluteToleranceNits':absolute,'patches':rows}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('manifest',type=Path);p.add_argument('source',choices=['pq','sdr']);p.add_argument('capture',type=Path)
    p.add_argument('--relative',type=float,default=.06);p.add_argument('--absolute',type=float,default=3)
    a=p.parse_args()
    try:
        if not (0<=a.relative<=.15 and 0<=a.absolute<=10):
            raise ValueError('tolerances outside calibrated gate bounds')
        result=analyze(json.loads(a.manifest.read_text()),a.source,json.loads(a.capture.read_text()),a.relative,a.absolute)
    except (ValueError,KeyError,TypeError) as e:
        result={'pass':False,'error':str(e)}
    print(json.dumps(result,indent=2,allow_nan=False))
    raise SystemExit(0 if result['pass'] else 1)
