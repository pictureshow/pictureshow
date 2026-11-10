#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Run actual Compose/libmpv PQ+SDR then independently capture WindowServer FP16.
Build runtime with :desktop:spikeRuntime, native adapters per NATIVE-SURFACES.md.
Build capture: clang -O2 -fobjc-arc macos_capture.m -framework Cocoa
-framework ScreenCaptureKit -framework CoreMedia -framework CoreVideo -o macos-capture
No screenshots of unrelated windows, no permission dialogs or display changes.
"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from analyze import analyze

p=argparse.ArgumentParser(description=__doc__)
for key in ['java','runtime','layer','window','mpv','capture','fixtures','output']:
    p.add_argument('--'+key,type=Path,required=True)
p.add_argument('--gui-lock',type=Path,required=True,help='Shared with other test jobs')
p.add_argument('--wait-gui-seconds',type=int,default=0,help='Finite wait for an existing GUI job; no app starts before lock')
a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=False)
lock=a.gui_lock.open('a')
deadline=time.monotonic()+a.wait_gui_seconds
while True:
    try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);break
    except BlockingIOError:
        if time.monotonic()>=deadline:sys.exit('GUI owned by another job; no app started')
        time.sleep(.2)
preflight=json.loads(subprocess.check_output([str(a.capture),'preflight'],text=True))
(a.output/'preflight.json').write_text(json.dumps(preflight,indent=2)+'\n')
if preflight['architecture']!='arm64' or not preflight['screenRecordingPreflight']:
    (a.output/'verdict.json').write_text(json.dumps({'pass':False,'reason':'Need Apple Silicon and existing Screen Recording permission','preflight':preflight},indent=2)+'\n')
    sys.exit(2)
manifest=json.loads((a.fixtures/'manifest.json').read_text())
rows={}
for kind in ['pq','sdr']:
    out=(a.output/kind).resolve();out.mkdir()
    env=os.environ.copy();env.pop('DYLD_LIBRARY_PATH',None)
    env.update(SPIKE_LAYER_BRIDGE=str(a.layer.resolve()),SPIKE_WINDOW_BRIDGE=str(a.window.resolve()),MPV_LIBRARY=str(a.mpv.resolve()),SPIKE_HDR_HOLD_SECONDS='20',SPIKE_HDR_READBACK_DIR=str(out),SPIKE_NULL_AUDIO='1')
    args=[str(a.java),'--enable-native-access=ALL-UNNAMED','--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED','-Djava.awt.headless=false','-cp',str(a.runtime),'app.pictureshow.desktop.MainKt','layer',str((a.fixtures/(kind+'.mkv')).resolve()),str(out)]
    with (out/'stdout.log').open('w') as log,(out/'stderr.log').open('w') as err:
        app=subprocess.Popen(args,env=env,stdout=log,stderr=err)
        (out/'pid.txt').write_text(str(app.pid)+'\n')
        try:
            deadline=time.monotonic()+45
            while not (out/'01-window-native.json').exists() and app.poll() is None and time.monotonic()<deadline:time.sleep(.2)
            if not (out/'01-window-native.json').exists():raise RuntimeError('Actual app failed to reach stable playback')
            # Save both modes: canonical capture may preserve HDR independent of SDR scanout.
            for mode in ['local','canonical']:
                file=out/(mode+'.json')
                r=subprocess.run([str(a.capture),str(file),mode],capture_output=True,text=True,timeout=25)
                (out/(mode+'.log')).write_text(r.stdout+r.stderr)
                if r.returncode==0:
                    rows[kind+'-'+mode]=analyze(manifest,kind,json.loads(file.read_text()))
                else:rows[kind+'-'+mode]={'pass':False,'error':'Capture failed; see log/JSON'}
            app.wait(timeout=40)
            if app.returncode!=0:raise RuntimeError('Actual app teardown failed')
            subprocess.run([sys.executable,str(Path(__file__).with_name('macos_samples.py')),str(out/'drawable'),str(out/'drawable-samples.json')],check=True)
            rows[kind+'-drawable']=analyze(manifest,kind,json.loads((out/'drawable-samples.json').read_text()))
        finally:
            if app.poll() is None:
                app.terminate()
                try:app.wait(timeout=10)
                except subprocess.TimeoutExpired:app.kill();app.wait()
headroom=all((a.output/k/'local.json').exists() and json.loads((a.output/k/'local.json').read_text()).get('captureHeadroom',0)>1 for k in ['pq','sdr'])
result={'pass':headroom and all(rows[k+'-'+boundary]['pass'] for k in ['pq','sdr'] for boundary in ['local','drawable']),
        'capturePasses':{k:v['pass'] for k,v in rows.items()},'captures':rows,
        'headroomAboveOne':headroom,'preflight':preflight,
        'claim':'OS capture boundary, no physical signal or photometric proof'}
(a.output/'verdict.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k!='captures'},indent=2))
sys.exit(0 if result['pass'] else 1)
