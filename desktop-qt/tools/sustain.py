#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Generate the same original CC0 testsrc2 signal at native 1080p60; 135 seconds physical duration."""
import subprocess,sys,os,hashlib,json
from pathlib import Path
p=Path(sys.argv[1])
subprocess.run(['ffmpeg','-hide_banner','-nostdin','-y','-f','lavfi','-i','testsrc2=size=1920x1080:rate=60:duration=135','-f','lavfi','-i','anullsrc=channel_layout=5.1:sample_rate=48000','-map','0:v','-map','1:a','-t','135','-c:v','libx265','-preset','ultrafast','-threads','0' if os.name=='nt' else '2','-x265-params',('pools=4:frame-threads=2:log-level=error' if os.name=='nt' else 'pools=1:frame-threads=1:log-level=error'),'-crf','32','-pix_fmt','yuv420p10le','-c:a','aac','-ac','6',str(p/'sustain-1080p60.mkv')],check=True)

(p/'SUSTAIN-LICENSE.json').write_text(json.dumps({'license':'CC0-1.0','creator':'Legitimate LLC','signal':'testsrc2 native1920x1080/60fps,135s, silentAAC5.1','sha256':hashlib.sha256((p/'sustain-1080p60.mkv').read_bytes()).hexdigest()},indent=2))
