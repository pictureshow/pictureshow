#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Scale/interpolate Leg97 CC0 signal to 1080p60; 135 seconds physical duration."""
import subprocess,sys
from pathlib import Path
p=Path(sys.argv[1])
subprocess.run(['ffmpeg','-hide_banner','-nostdin','-y','-stream_loop','-1','-i',str(p/'hevc-main10.mkv'),'-map','0:v','-map','0:a','-vf','scale=1920:1080,fps=60','-t','135','-c:v','libx265','-preset','ultrafast','-threads','2','-x265-params','pools=1:frame-threads=1:log-level=error','-crf','32','-pix_fmt','yuv420p10le','-c:a','copy',str(p/'sustain-1080p60.mkv')],check=True)
