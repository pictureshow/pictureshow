#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Original mathematical video and silent 5.1 audio: CC0-1.0.
"""135-second 1080p60 Main10 fixture, repeating a six-second encoded signal without decoder resets."""
import hashlib,json,subprocess,sys
from pathlib import Path
root=Path(sys.argv[1]);root.mkdir(parents=True,exist_ok=True)
short=root/'short1080p60.mkv'; target=root/'hevc-main10-1080p60.mkv'
def run(args):subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-nostdin','-y',*args],check=True)
run(['-f','lavfi','-i','testsrc2=size=1920x1080:rate=60:duration=6','-f','lavfi','-i','anullsrc=channel_layout=5.1:sample_rate=48000','-t','6','-c:v','libx265','-preset','ultrafast','-threads','2','-x265-params','pools=1:frame-threads=1:log-level=error:keyint=60:min-keyint=60:scenecut=0:open-gop=0','-crf','30','-pix_fmt','yuv420p10le','-c:a','aac','-ac','6',str(short)])
run(['-stream_loop','22','-i',str(short),'-t','135','-c','copy',str(target)])
(root/'performance-LICENSE.json').write_text(json.dumps({'license':'CC0-1.0','creator':'Legitimate LLC','note':'Repeating six-second mathematical signal; 135-second continuous timestamps; no decoder reload during soak.','sha256':hashlib.sha256(target.read_bytes()).hexdigest()},indent=2)+'\n')
