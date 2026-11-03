#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Generate 48h XMLTV relative to setup time. Rerun before scheduling receipts."""
import datetime as dt, pathlib, sys, xml.etree.ElementTree as X
root=pathlib.Path(sys.argv[1]); root.mkdir(parents=True,exist_ok=True)
now=dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
def stamp(d): return d.strftime('%Y%m%d%H%M%S +0000')
tv=X.Element('tv',{'generator-info-name':'Pictureshow CC0 fixture'})
slots=[]
for key,name in [('a','Test A'),('b','Test B')]:
    channel='ps-test-'+key; c=X.SubElement(tv,'channel',id=channel); X.SubElement(c,'display-name').text=name
    # Current show, then imminent 2-minute same-time pair, then repeated series.
    for i in range(98):
        start=now-dt.timedelta(minutes=5) if i==0 else (now+dt.timedelta(seconds=45) if i==1 else now+dt.timedelta(minutes=2+(i-1)*30))
        end=now+dt.timedelta(seconds=45) if i==0 else start+dt.timedelta(minutes=2 if i==1 else 28)
        pr=X.SubElement(tv,'programme',{'start':stamp(start),'stop':stamp(end),'channel':channel})
        X.SubElement(pr,'title',lang='en').text='Scope Series'
        X.SubElement(pr,'sub-title',lang='en').text=f'Scope {key} episode {i}'
        X.SubElement(pr,'desc').text='Original CC0 generated guide, tone and color test.'
        X.SubElement(pr,'category').text='Series'; X.SubElement(pr,'episode-num',system='xmltv_ns').text=f'0.{i}.0/1'
        X.SubElement(pr,'new')
        if i==1: slots.append({'channel':channel,'start':start.isoformat(),'end':end.isoformat()})
    # Deliberate overlapping listing and gaps are separate guide fixtures.
    pr=X.SubElement(tv,'programme',{'start':stamp(now+dt.timedelta(hours=3,minutes=5)),'stop':stamp(now+dt.timedelta(hours=3,minutes=8)),'channel':channel})
    X.SubElement(pr,'title').text='Scope overlapping guide slot'
X.ElementTree(tv).write(root/'guide.xml',encoding='utf-8',xml_declaration=True)
(root/'channels.m3u').write_text('#EXTM3U\n'+''.join(f'#EXTINF:-1 tvg-id="ps-test-{c}" tvg-name="Test {c.upper()}" tvg-chno="{i}",Test {c.upper()}\nhttp://origin:8080/{c}/index.m3u8\n' for i,c in enumerate('ab',1)))
import json
(root/'slots.json').write_text(json.dumps({'generatedUtc':now.isoformat(),'conflictPair':slots,'license':'CC0-1.0'},indent=2)+'\n')
print('Generated 48-hour guide and imminent conflict pair')
(root/'LICENSE.json').write_text(json.dumps({'license': 'CC0-1.0', 'creator': 'Legitimate LLC', 'content': 'Original generated live test signals and synthetic XMLTV/M3U metadata; no third-party media', 'sources': ['live.sh', 'guide.py'], 'channels': [{'id': 'ps-test-a', 'color': 'blue', 'toneHz': 440}, {'id': 'ps-test-b', 'color': 'red', 'toneHz': 880}], 'video': {'width': 320, 'height': 180, 'fps': 24, 'codec': 'h264', 'elapsedAndFrameOverlay': True}, 'audio': {'sampleRate': 48000, 'codec': 'aac', 'bitrate': 64000}, 'hls': {'segmentSeconds': 2, 'windowSegments': 6}, 'guide': {'relativeUtc': True, 'hours': 48, 'series': 'Scope Series', 'intentionalGapsAndOverlap': True}},indent=2)+'\n')
