#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Generate the same original CC0 testsrc2 signal at native 1080p60; 135 seconds physical duration."""
import subprocess,sys
from pathlib import Path
p=Path(sys.argv[1])
subprocess.run(['ffmpeg','-hide_banner','-nostdin','-y','-f','lavfi','-i','testsrc2=size=1920x1080:rate=60:duration=135','-f','lavfi','-i','anullsrc=channel_layout=5.1:sample_rate=48000','-map','0:v','-map','1:a','-t','135','-c:v','libx265','-preset','ultrafast','-threads','2','-x265-params','pools=1:frame-threads=1:log-level=error','-crf','32','-pix_fmt','yuv420p10le','-c:a','aac','-ac','6',str(p/'sustain-1080p60.mkv')],check=True)
