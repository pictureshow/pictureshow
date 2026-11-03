#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Check generated receipts against actual local bytes and decoded samples."""
import argparse,hashlib,json,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('media',type=pathlib.Path);p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args();root=a.media
manifest=json.loads((root/'LICENSE.json').read_text());assert manifest['license']=='CC0-1.0'
for f in manifest['files']: assert hashlib.sha256((root/f['file']).read_bytes()).hexdigest()==f['sha256'],f['file']
def pcm(path): return subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-f','s16le','-acodec','pcm_s16le','-'])
reference=pcm(root/'reference/gapless.wav'); parts=[pcm(root/f'music/Scope Ensemble/Gapless/{i:02d} Boundary.flac') for i in [1,2]]
assert parts[0]+parts[1]==reference
assert len(parts[0])==len(parts[1])==240000*2
codecs=set()
for f in manifest['files']:
 if f['file'].startswith('music/') and not f['file'].endswith('.m3u'):
  codecs.add(json.loads(subprocess.check_output(['ffprobe','-v','error','-show_entries','stream=codec_name','-of','json',str(root/f['file'])]))['streams'][0]['codec_name'])
assert {'flac','aac','mp3','opus','pcm_s24le'}<=codecs
m3u=(root/'music/Scope Repeats.m3u').read_text().splitlines();assert m3u[1]==m3u[2]
result={'license':'CC0-1.0','fileHashesVerified':len(manifest['files']),'audioCodecs':sorted(codecs),'gapless':{'sampleRate':48000,'samplesPerPart':240000,'concatenatedPCMEqualsReference':True,'referencePCMsha256':hashlib.sha256(reference).hexdigest()},'rawPlaylistContainsRepeatedPath':True,'scope':'Generated source verification; no renderer gapless claim'}
a.out.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
