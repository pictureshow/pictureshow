#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Original mathematical video, silent 5.1 audio, and synthetic subtitle signals: CC0-1.0.
"""Generate a small local-playback corpus; requires ffmpeg with libx265 and libaom-av1 (or libsvtav1).
Usage: python desktop/tools/fixtures.py OUTPUT
No network, accounts, server, or third-party media.
"""
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys

root = Path(sys.argv[1])
root.mkdir(parents=True, exist_ok=True)
(root / 'sub.srt').write_text('1\n00:00:00,000 --> 00:00:11,500\nSRT LOCAL subtitle\n')
(root / 'sub.ass').write_text('''[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,28,&H0000FFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,20,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:00:11.50,Default,,0,0,0,,ASS LOCAL styled subtitle
''')
# PGS packet writer, independently implemented from the documented segment layout.
# A simple original 5x7 bitmap alphabet avoids font and image dependencies.
font = {
 'P':['11110','10001','10001','11110','10000','10000','10000'],
 'G':['01110','10001','10000','10111','10001','10001','01110'],
 'S':['01111','10000','10000','01110','00001','00001','11110'],
 'L':['10000','10000','10000','10000','10000','10000','11111'],
 'O':['01110','10001','10001','10001','10001','10001','01110'],
 'C':['01111','10000','10000','10000','10000','10000','01111'],
 'A':['01110','10001','10001','11111','10001','10001','10001'],
 ' ':['00000']*7,
}
text='PGS LOCAL'; scale=4; w=len(text)*6*scale; h=9*scale
rle=bytearray()
for y in range(h):
 for x in range(w):
  col=x//scale; row=y//scale-1
  on=0<=row<7 and col%6<5 and font[text[col//6]][row][col%6]=='1'
  # Palette 1 white, palette 2 black background; byte 0 reserved for RLE escapes.
  rle.append(1 if on else 2)
 rle += b'\0\0'
def segment(kind, payload, pts=0):
 return b'PG'+struct.pack('>IIBH',pts,0,kind,len(payload))+payload
def pcs(number, count):
 return struct.pack('>HHBHBBBB',640,360,0x10,number,0x80 if count else 0,0,0,count)+(struct.pack('>HBBHH',0,0,0,(640-w)//2,290) if count else b'')
pgs=segment(0x16,pcs(0,1))
pgs+=segment(0x17,struct.pack('>BBHHHH',1,0,(640-w)//2,290,w,h))
pgs+=segment(0x14,bytes([0,0,1,235,128,128,255,2,16,128,128,230]))
data=struct.pack('>HH',w,h)+rle
pgs+=segment(0x15,struct.pack('>HBB',0,0,0xC0)+len(data).to_bytes(3,'big')+data)
pgs+=segment(0x80,b'')
pgs+=segment(0x16,pcs(1,0),int(11.5*90000))+segment(0x80,b'',int(11.5*90000))
(root/'sub.sup').write_bytes(pgs)

def run(args):
 subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-nostdin','-y',*map(str,args)],check=True)
common=['-f','lavfi','-i','testsrc2=size=640x360:rate=24:duration=12','-f','lavfi','-i','anullsrc=channel_layout=5.1:sample_rate=48000','-i',root/'sub.srt','-i',root/'sub.ass','-i',root/'sub.sup','-map','0:v','-map','1:a','-map','2:s','-map','3:s','-map','4:s','-t','12','-c:a','aac','-ac','6','-c:s','copy','-metadata:s:s:0','title=SRT LOCAL','-metadata:s:s:1','title=ASS LOCAL','-metadata:s:s:2','title=PGS LOCAL']
for name,pix in [('hevc-main','yuv420p'),('hevc-main10','yuv420p10le')]:
 run(common+['-c:v','libx265','-preset','ultrafast','-threads','2','-x265-params','pools=1:frame-threads=1:log-level=error','-pix_fmt',pix,root/(name+'.mkv')])
encoders=subprocess.check_output(['ffmpeg','-hide_banner','-encoders'],stderr=subprocess.DEVNULL,text=True)
av1=['-c:v','libaom-av1','-cpu-used','8'] if 'libaom-av1' in encoders else ['-c:v','libsvtav1','-preset','12','-svtav1-params','lp=2']
run(common+av1+['-threads','2','-crf','45','-pix_fmt','yuv420p10le',root/'av1.mkv'])
run(['-i',root/'hevc-main10.mkv','-map','0','-c','copy','-bsf:v','hevc_metadata=colour_primaries=9:transfer_characteristics=16:matrix_coefficients=9',root/'hevc-pq-metadata.mkv'])
(root/'LICENSE.json').write_text(json.dumps({'license':'CC0-1.0','creator':'Legitimate LLC','note':'Generated signals. PQ variant changes metadata only; no calibrated HDR luminance claim.','sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(root.glob('*.mkv'))}},indent=2)+'\n')
