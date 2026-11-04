#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Reset ONLY the named fixture viewer's playback position/played fields."""
import argparse, os
from api import API,vault,credential
p=argparse.ArgumentParser();p.add_argument('--user',choices=['scope-viewer-a','scope-viewer-b','scope-restricted'],required=True);a=p.parse_args()
client=API(os.environ['JF_URL'],'scope-reset-'+a.user); auth=client.login(vault(credential(os.environ['JF_VERSION'],a.user)))
try:
    if auth['User']['Name']!=a.user: raise SystemExit('viewer identity mismatch')
    items=client.ok('GET','/Items',query={'userId':client.user,'recursive':'true','includeItemTypes':'Audio,Movie,Episode,Video'})['Items']
    for item in items:
        current=client.ok('GET',f'/UserItems/{item["Id"]}/UserData',query={'userId':client.user})
        client.ok('POST',f'/UserItems/{item["Id"]}/UserData',current|{'PlaybackPositionTicks':0,'Played':False},query={'userId':client.user})
    print(f'Reset position/played on {len(items)} visible fixture items; play counts and favorites preserved')
finally: client.ok('POST','/Sessions/Logout')
