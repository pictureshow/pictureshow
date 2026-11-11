#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Generate original CC0 fixtures. Requires ffmpeg; no downloaded assets."""
import argparse, hashlib, json, pathlib, subprocess
p=argparse.ArgumentParser(); p.add_argument('output',type=pathlib.Path); a=p.parse_args(); root=a.output
root.mkdir(parents=True,exist_ok=True)
manifest={'license':'CC0-1.0','creator':'Legitimate LLC','description':'Original mathematical test signals and patterns dedicated to CC0. No third-party media.','gapless':{'sample_rate':48000,'split_sample':240000,'reference':'reference/gapless.wav'},'files':[]}
def run(rel,args,tags=None):
    dest=root/rel; dest.parent.mkdir(parents=True,exist_ok=True)
    cmd=['ffmpeg','-hide_banner','-loglevel','error','-nostdin','-y',*args]
    for k,v in (tags or {}).items(): cmd+=['-metadata',f'{k}={v}']
    subprocess.run(cmd+[str(dest)],check=True)
    manifest['files'].append({'file':rel,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest(),'parameters':args,'tags':tags or {}})
def audio(rel,freq,duration,codec,tags):
    run('music/'+rel,['-f','lavfi','-i',f'aevalsrc=0.16*sin(2*PI*{freq}*t)+0.03*sin(2*PI*(220*t+30*t*t)):s=48000:d={duration}','-c:a',codec,'-threads','1'],tags)
for disc,track,ext,codec in [(1,1,'flac','flac'),(1,2,'m4a','aac'),(2,1,'mp3','libmp3lame'),(2,2,'opus','libopus')]:
    audio(f'Scope Ensemble/Two Discs/Disc {disc}/{track:02d} Signal.{ext}',330+disc*100+track*30,12,codec,{'title':f'Signal {disc}-{track}','album':'Two Discs','artist':'Scope Ensemble','album_artist':'Scope Ensemble','disc':str(disc),'track':str(track)})
for i,artist in enumerate(['Scope Red','Scope Blue','Scope Green']):
    audio(f'Various Artists/Compilation/{i+1:02d} Shared Title.flac',440+i*80,15,'flac',{'title':'Shared Title','album':'Compilation','artist':artist,'album_artist':'Various Artists','track':str(1) if i<2 else ''})
audio('Scope Ensemble/Long Seek/Long Seek.flac',240,180,'flac',{'title':'Long Seek','album':'Long Seek','artist':'Scope Ensemble','track':'1'})
# PCM split is sample exact; no encoder delay at the lossless boundary.
run('reference/gapless.wav',['-f','lavfi','-i','sine=frequency=997:sample_rate=48000:duration=10','-c:a','pcm_s16le'])
for i in range(2):
    run(f'music/Scope Ensemble/Gapless/{i+1:02d} Boundary.flac',['-i',str(root/'reference/gapless.wav'),'-af',f'atrim=start_sample={i*240000}:end_sample={(i+1)*240000},asetpts=PTS-STARTPTS','-c:a','flac'],{'title':f'Boundary {i+1}','album':'Gapless','artist':'Scope Ensemble','track':str(i+1)})
# Deliberately non-AAC source for conversion qualification; profile must exclude it.
audio('Scope Ensemble/Conversion/PCM Source.wav',550,20,'pcm_s24le',{'title':'PCM Source','album':'Conversion','artist':'Scope Ensemble','track':'1'})
run('video/Scope Pattern/Scope Pattern.mkv',['-f','lavfi','-i','testsrc2=size=320x180:rate=24:duration=90','-f','lavfi','-i','sine=frequency=660:sample_rate=48000:duration=90','-c:v','libx264','-preset','ultrafast','-threads','1','-pix_fmt','yuv420p','-g','48','-c:a','aac','-shortest'])
run('restricted/Restricted Pattern/Restricted Pattern.mkv',['-i',str(root/'video/Scope Pattern/Scope Pattern.mkv'),'-c','copy'])
# Offline: two languages of sound and captions, one direct-play file and one the phone can't decode (HEVC 10-bit, AC-3).
for rel,cue in [('reference/offline-spa.srt','Subtitulo en espanol'),('reference/offline-fra.srt','Sous-titre en francais')]:
    (root/rel).write_text(f'1\n00:00:01,000 --> 00:06:00,000\n{cue}\n')
def offline(rel,duration,video,audio):
    run(rel,['-f','lavfi','-i',f'testsrc2=size=640x360:rate=24:duration={duration}','-f','lavfi','-i',f'sine=frequency=660:sample_rate=48000:duration={duration}','-f','lavfi','-i',f'sine=frequency=880:sample_rate=48000:duration={duration}','-i',str(root/'reference/offline-spa.srt'),'-i',str(root/'reference/offline-fra.srt'),'-map','0:v','-map','1:a','-map','2:a','-map','3:s','-map','4:s',*video,*audio,'-c:s','srt','-metadata:s:a:0','language=eng','-metadata:s:a:1','language=spa','-metadata:s:s:0','language=spa','-metadata:s:s:1','language=fra','-disposition:s:0','default','-threads','1'])
    srt=f"1\n00:00:01,000 --> 00:00:30,000\nEnglish external subtitle one\n\n2\n00:03:00,000 --> 00:06:00,000\nEnglish external subtitle two\n"
    if duration>60: (root/rel.replace('.mkv','.eng.srt')).write_text(srt); manifest['files'].append({'file':rel.replace('.mkv','.eng.srt'),'sha256':hashlib.sha256(srt.encode()).hexdigest(),'parameters':['external caption text written by this generator'],'tags':{}})
h264=['-c:v','libx264','-preset','ultrafast','-g','48','-b:v','1500k']; aac=['-c:a','aac','-b:a','128k']
offline('video/Scope Offline/Scope Offline.mkv',360,h264,aac)
offline('video/Scope Offline Convert/Scope Offline Convert.mkv',360,['-c:v','libx265','-preset','ultrafast','-b:v','1800k','-pix_fmt','yuv420p10le'],['-c:a','ac3','-b:a','192k'])
run('video/Scope Offline Large/Scope Offline Large.mkv',['-f','lavfi','-i','testsrc2=size=1920x1080:rate=24:duration=20','-f','lavfi','-i','sine=frequency=660:sample_rate=48000:duration=20','-c:v','libx265','-preset','ultrafast','-b:v','4000k','-pix_fmt','yuv420p10le','-c:a','ac3','-b:a','192k','-threads','1'])
for episode in (1,2): offline(f'shows/Scope Serial/Season 01/Scope Serial S01E{episode:02d}.mkv',40,h264,aac)
# Public manifest records relative, portable arguments only.
for f in manifest['files']:
    f['parameters']=[s.replace(str(root),'${OUTPUT}') for s in f['parameters']]
playlist='Scope Ensemble/Two Discs/Disc 1/01 Signal.flac\n'*2
(root/'music/Scope Repeats.m3u').write_text('#EXTM3U\n'+playlist)
manifest['files'].append({'file':'music/Scope Repeats.m3u','sha256':hashlib.sha256(('#EXTM3U\n'+playlist).encode()).hexdigest(),'parameters':['two relative references to the same generated track'],'tags':{}})
(root/'LICENSE.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(f'Generated {len(manifest["files"])} original files')
