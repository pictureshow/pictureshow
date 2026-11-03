#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Force original 24-bit PCM fixture through the server's MP3 audio conversion."""
import argparse,datetime as dt,hashlib,json,os,pathlib,subprocess,tempfile
from api import API,vault,credential
from contracts import Capture
p=argparse.ArgumentParser();p.add_argument('--out',required=True,type=pathlib.Path);a=p.parse_args();cap=Capture();client=API(os.environ['JF_URL'],'scope-audio-contract');version=os.environ['JF_VERSION']
try:
    utc=dt.datetime.now(dt.timezone.utc)
    if 6<=utc.hour<10 or (utc.hour==5 and utc.minute>=50): raise RuntimeError('outside fixture load window')
    creds=json.loads(os.environ['JF_CREDENTIALS'])['scope-viewer-a'] if 'JF_CREDENTIALS' in os.environ else vault(credential(version,'scope-viewer-a'))
    client.login(creds);cap.secrets=[creds['password'],client.token]
    items=client.ok('GET','/Items',query={'userId':client.user,'recursive':'true','includeItemTypes':'Audio','fields':'MediaSources'})['Items']
    item=next(i for i in items if i['Name']=='PCM Source');source=item['MediaSources'][0]
    original=next(s['Codec'] for s in source['MediaStreams'] if s['Type']=='Audio')
    code,media,_=client.req('GET',f'/Audio/{item["Id"]}/universal',query={'userId':client.user,'deviceId':client.device,'container':'mp3','audioCodec':'mp3','transcodingContainer':'mp3','transcodingProtocol':'http','maxStreamingBitrate':128000,'audioBitRate':128000},raw=True)
    with tempfile.NamedTemporaryFile(suffix='.mp3') as f:
        f.write(media);f.flush();probe=subprocess.run(['ffprobe','-v','error','-show_entries','format=duration:stream=codec_name','-of','json',f.name],capture_output=True,text=True)
        observed=json.loads(probe.stdout) if probe.returncode==0 else {}
        decoded=subprocess.run(['ffmpeg','-v','error','-i',f.name,'-f','null','-'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
    cap.check('pcm-source-forces-mp3-conversion',code==200 and original=='pcm_s24le' and any(s.get('codec_name')=='mp3' for s in observed.get('streams',[])) and decoded,{'status':code,'sourceCodec':original,'bytes':len(media),'sha256':hashlib.sha256(media).hexdigest(),'probe':observed,'decoded':decoded,'scope':'finite HTTP audio bytes; independent offline playback/package qualification belongs to 84B'})
except Exception as error: cap.check('audio-harness-completed',False,{'error':str(error) if isinstance(error,RuntimeError) else type(error).__name__})
finally:
    if client.token: client.req('POST','/Sessions/Logout')
    a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps({'version':version,'checks':cap.checks},indent=2)+'\n')
if any(not c['passed'] for c in cap.checks):raise SystemExit(1)
