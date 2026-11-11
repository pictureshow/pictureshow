#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Original CC0 numeric patterns, not a metadata-only retag. Requires ffmpeg/libx265.
python desktop/tools/hdr/fixture.py OUTPUT
ST2084 inverse EOTF -> BT2020 NCL limited YUV10 -> lossless Main10 HEVC.
SDR uses BT.1886 gamma 2.4, BT.709 and a declared 203 nit reference white.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess

W, H = 640, 360
WHITE = 203.0
NAMES = ['black', 'gray100', 'gray203', 'gray1000', 'red100', 'green100', 'blue100', 'gray50']
HDR = [(0,)*3, (100,)*3, (203,)*3, (1000,)*3, (100,0,0), (0,100,0), (0,0,100), (50,)*3]
SDR = [(0,)*3, (100,)*3, (203,)*3, (203,)*3, (100,0,0), (0,100,0), (0,0,100), (50,)*3]

def pq_encode(nits):
    m1, m2 = 2610/16384, 2523/32
    c1, c2, c3 = 3424/4096, 2413/128, 2392/128
    x = (nits/10000)**m1
    return ((c1+c2*x)/(1+c3*x))**m2

def pq_decode(code):
    m1, m2 = 2610/16384, 2523/32
    c1, c2, c3 = 3424/4096, 2413/128, 2392/128
    x = max(code, 0)**(1/m2)
    return 10000*(max(x-c1, 0)/(c2-c3*x))**(1/m1)

def signal(rgb, hdr):
    r, g, b = [pq_encode(x) if hdr else (x/WHITE)**(1/2.4) for x in rgb]
    kr, kb = (0.2627, 0.0593) if hdr else (0.2126, 0.0722)
    y = kr*r+(1-kr-kb)*g+kb*b
    return [round(64+876*y), round(512+896*(b-y)/(2*(1-kb))), round(512+896*(r-y)/(2*(1-kr)))]

def decode_signal(yuv, hdr):
    y, cb, cr = (yuv[0]-64)/876, (yuv[1]-512)/896, (yuv[2]-512)/896
    kr, kb = (0.2627, 0.0593) if hdr else (0.2126, 0.0722)
    r, b = y+2*(1-kr)*cr, y+2*(1-kb)*cb
    g = (y-kr*r-kb*b)/(1-kr-kb)
    return [pq_decode(x) if hdr else WHITE*max(x,0)**2.4 for x in (r,g,b)]

def generate(root):
    root.mkdir(parents=True, exist_ok=True)
    manifest = {'license':'CC0-1.0', 'creator':'Legitimate LLC', 'width':W, 'height':H,
                'sdrReferenceWhiteNits':WHITE, 'pattern':'eight equal vertical bars; sample central quarter, central height', 'files':{}}
    for hdr, colors in [(True,HDR),(False,SDR)]:
        name = 'pq' if hdr else 'sdr'
        codes = [signal(rgb,hdr) for rgb in colors]
        # Planar 4:2:0; edges aligned to chroma samples, flat interiors exact.
        frame = b''.join(b''.join(struct.pack('<H',codes[x//(w//8)][plane]) for x in range(w))*h
                         for plane,w,h in [(0,W,H),(1,W//2,H//2),(2,W//2,H//2)])
        raw = root/(name+'.yuv'); raw.write_bytes(frame)
        output = root/(name+'.mkv')
        subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-nostdin','-y','-stream_loop','-1',
            '-f','rawvideo','-pixel_format','yuv420p10le','-video_size',f'{W}x{H}','-framerate','1','-color_range','tv','-color_primaries','bt2020' if hdr else 'bt709','-color_trc','smpte2084' if hdr else 'bt709','-colorspace','bt2020nc' if hdr else 'bt709','-i',str(raw),
            '-t','2','-an','-c:v','libx265','-preset','ultrafast','-threads','2','-x265-params',
            'lossless=1:pools=1:frame-threads=1:log-level=error', '-color_range','tv',
            '-color_primaries','bt2020' if hdr else 'bt709','-color_trc','smpte2084' if hdr else 'bt709',
            '-colorspace','bt2020nc' if hdr else 'bt709', str(output)],check=True)
        decoded = subprocess.check_output(['ffmpeg','-v','error','-i',str(output),'-frames:v','1','-f','rawvideo','-pix_fmt','yuv420p10le','-'])
        if decoded != frame:
            raise RuntimeError('Lossless source verification failed')
        raw.unlink()
        manifest['files'][name] = {'path':output.name,'sha256':hashlib.sha256(output.read_bytes()).hexdigest(),
            'primaries':'bt2020' if hdr else 'bt709','encoding':'pq' if hdr else 'gamma2.4','decodedSourceExact':True,
            'patches':[{'name':n,'rgbNits':list(rgb),'quantizedRgbNits':decode_signal(c,hdr),'yuv10':c,'center':[ (i+.5)/8,.5]} for i,(n,rgb,c) in enumerate(zip(NAMES,colors,codes))]}
    (root/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(json.dumps({'sourceExact':True,'files':{k:v['sha256'] for k,v in manifest['files'].items()}}))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('output',type=Path)
    generate(p.parse_args().output)
