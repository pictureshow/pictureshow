#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Run actual Compose/libmpv PQ+SDR then independently capture WindowServer FP16.
Build runtime with :desktop:spikeRuntime, native adapters per NATIVE-SURFACES.md.
Build capture: clang -O2 -fobjc-arc macos_capture.m -framework Cocoa
-framework ScreenCaptureKit -framework CoreMedia -framework CoreVideo -o macos-capture
Captures are cropped to the generated app. No permission dialogs or physical display changes.
Optional temporary virtual display is created and cleaned within the GUI lock.
"""
import argparse
import copy
import fcntl
import json
import os
import re
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
p.add_argument('--virtual-display',type=Path,help='Compiled macos_virtual.m; isolated finite PQ display, no mirror/main changes')
p.add_argument('--stream',action='store_true',help='Use SCStream; automatic for PQ capture modes')
p.add_argument('--capture-mode',choices=['pq2020','pqp3','linear','preset'],default='pq2020',help='Independent capture encoding; PQ2020 retains full source gamut')
p.add_argument('--expect-sdr-output',action='store_true',help='Negative HDR-output control; SDR relative white is explicitly100nits')
p.add_argument('--virtual-transfer',type=int,choices=[0,1],default=1)
a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=False)
lock=a.gui_lock.open('a')
deadline=time.monotonic()+a.wait_gui_seconds
while True:
    try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);break
    except BlockingIOError:
        if time.monotonic()>=deadline:sys.exit('GUI owned by another job; no app started')
        time.sleep(.2)
virtual=None
position_env={}
try:
    if a.virtual_display:
        virtual_log=a.output/'virtual.jsonl'
        virtual_err=(a.output/'virtual.stderr.log').open('w')
        virtual_out=virtual_log.open('w')
        virtual=subprocess.Popen([str(a.virtual_display),'--create',str(a.virtual_transfer),'120'],stdout=virtual_out,stderr=virtual_err)
        (a.output/'virtual.pid').write_text(str(virtual.pid)+'\n')
        deadline=time.monotonic()+10
        screen=None
        while time.monotonic()<deadline and virtual.poll() is None:
            for line in virtual_log.read_text().splitlines():
                try:
                    receipt=json.loads(line)
                    if receipt.get('phase')=='virtual':screen=receipt
                except json.JSONDecodeError:pass
            if screen:break
            time.sleep(.2)
        if not screen:raise RuntimeError('Virtual display did not register')
        coords=[int(x) for x in re.findall(r'-?\d+',screen['bounds'])]
        position_env={'SPIKE_HDR_WINDOW_X':str(coords[0]+40),'SPIKE_HDR_WINDOW_Y':str(coords[1]+40)}
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
        env.update(position_env)
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
                    capture_env=os.environ.copy()
                    for key in ['SPIKE_HDR_CAPTURE_PQ','SPIKE_HDR_CAPTURE_PQ2020','SPIKE_HDR_CAPTURE_PRESET']:capture_env.pop(key,None)
                    if a.capture_mode.startswith('pq'):capture_env['SPIKE_HDR_CAPTURE_PQ']='1'
                    if a.capture_mode=='pq2020':capture_env['SPIKE_HDR_CAPTURE_PQ2020']='1'
                    if a.capture_mode=='preset':capture_env['SPIKE_HDR_CAPTURE_PRESET']='1'
                    r=subprocess.run([str(a.capture),str(file),mode]+(['display'] if a.virtual_display else ['filtered'])+(['stream'] if a.stream or a.capture_mode.startswith('pq') else []),env=capture_env,capture_output=True,text=True,timeout=25)
                    (out/(mode+'.log')).write_text(r.stdout+r.stderr)
                    if r.returncode==0:
                        captured=json.loads(file.read_text())
                        if virtual is not None and (virtual.poll() is not None or captured.get('displayID')!=screen['displayID']):
                            raise RuntimeError('Capture did not remain on the live owned virtual display')
                        comparison=manifest
                        if a.expect_sdr_output and kind=='sdr':
                            comparison=copy.deepcopy(manifest)
                            for patch in comparison['files']['sdr']['patches']:
                                patch['quantizedRgbNits']=[v*100/manifest['sdrReferenceWhiteNits'] for v in patch['quantizedRgbNits']]
                        rows[kind+'-'+mode]=analyze(comparison,kind,captured)
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
    sdr_headroom=all((a.output/k/'local.json').exists() and 0<json.loads((a.output/k/'local.json').read_text()).get('captureHeadroom',0)<=1 for k in ['pq','sdr'])
    passed=(sdr_headroom and rows['sdr-local']['pass'] and not rows['pq-local']['pass'] and all(rows[k+'-drawable']['pass'] for k in ['pq','sdr'])) if a.expect_sdr_output else (headroom and all(rows[k+'-'+boundary]['pass'] for k in ['pq','sdr'] for boundary in ['local','drawable']))
    result={'pass':passed,'expectedOutput':'SDR100-nit-white negative control' if a.expect_sdr_output else 'HDR',
            'capturePasses':{k:v['pass'] for k,v in rows.items()},'captures':rows,
            'headroomAboveOne':headroom,'preflight':preflight,
            'claim':'OS capture boundary, no physical signal or photometric proof'}
    (a.output/'verdict.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k!='captures'},indent=2))
    sys.exit(0 if result['pass'] else 1)
except Exception as error:
    result={'pass':False,'error':type(error).__name__+': '+str(error),'captures':locals().get('rows',{})}
    (a.output/'verdict.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2));sys.exit(2)
finally:
    if virtual is not None:
        if virtual.poll() is None:virtual.terminate()
        try:virtual.wait(timeout=5)
        except subprocess.TimeoutExpired:virtual.kill();virtual.wait()
        virtual_out.close();virtual_err.close()
