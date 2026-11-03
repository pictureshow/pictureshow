#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Disposable Docker lifecycle. Run ON the fixture host. No remote hosts embedded."""
import argparse, datetime as dt, json, pathlib, subprocess
HERE=pathlib.Path(__file__).resolve().parent
VERSIONS={'10.10.7':18107,'10.11.11':18117,'12.4':18124}
def docker(*args): return subprocess.check_output(['docker',*args],text=True).strip()
def main():
    p=argparse.ArgumentParser(); p.add_argument('action',choices=['init','up','down','live-up','live-down','guide','status','teardown']); p.add_argument('--root',required=True,type=pathlib.Path); p.add_argument('--version',choices=VERSIONS); p.add_argument('--confirm-root',type=pathlib.Path); a=p.parse_args(); root=a.root.resolve()
    if a.action in ('up','live-up','init') and 6<=dt.datetime.now(dt.timezone.utc).hour<10: p.error('fixture load excluded 06:00–10:00Z')
    if a.action=='init':
        root.mkdir(parents=True,exist_ok=True)
        if (root/'compose.json').exists(): p.error('already initialized; refusing to replace existing fixture configuration')
        services={}; images={}
        for version,port in VERSIONS.items():
            image='jellyfin/jellyfin:'+version
            inspect=json.loads(docker('image','inspect',image))[0]; digest=inspect['RepoDigests'][0]; images[version]={'tag':image,'digest':digest,'imageId':inspect['Id'],'port':port}
            base=root/version
            for d in ['config','cache','media','recordings']: (base/d).mkdir(parents=True,exist_ok=True)
            (base/'config/config').mkdir(exist_ok=True)
            (base/'config/config/logging.json').write_text(json.dumps({'Serilog':{'MinimumLevel':{'Default':'Fatal','Override':{name:'Fatal' for name in ['Microsoft','System','Microsoft.AspNetCore.DataProtection.KeyManagement.XmlKeyManager','Microsoft.AspNetCore.DataProtection.Repositories.EphemeralXmlRepository','Microsoft.AspNetCore.StaticFiles.StaticFileMiddleware']}}}},indent=2))
            services['jf-'+version.replace('.','-')]={'image':digest,'container_name':'ps84a-'+version,'cpus':1.0,'mem_limit':'1200m','restart':'no','ports':[f'127.0.0.1:{port}:8096'],'volumes':[f'{base}/config:/config',f'{base}/cache:/cache',f'{base}/media:/media',f'{base}/recordings:/recordings',f'{root}/origin:/origin:ro'],'environment':{'JELLYFIN_PublishedServerUrl':f'http://127.0.0.1:{port}'},'security_opt':['no-new-privileges:true']}
        (root/'origin').mkdir(exist_ok=True)
        services['origin']={'image':'busybox:1.37','container_name':'ps84a-origin','restart':'no','cpus':0.15,'mem_limit':'64m','ports':['127.0.0.1:18140:8080'],'volumes':[f'{root}/origin:/www:ro'],'command':['httpd','-f','-p','8080','-h','/www']}
        services['channels']={'image':images['10.11.11']['digest'],'container_name':'ps84a-channels','restart':'no','cpus':0.8,'mem_limit':'384m','healthcheck':{'disable':True},'entrypoint':['/bin/bash','/scripts/live.sh'],'volumes':[f'{root}/origin:/output',f'{HERE}:/scripts:ro']}
        (root/'compose.json').write_text(json.dumps({'name':'pictureshow84a','services':services},indent=2))
        (root/'images.json').write_text(json.dumps(images,indent=2)+'\n'); print(json.dumps(images,indent=2)); return
    if not (root/'compose.json').exists(): p.error('not an initialized fixture root')
    cmd=['docker','compose','-f',str(root/'compose.json')]
    if a.action=='guide':
        subprocess.run(['python3',str(HERE/'guide.py'),str(root/'origin')],check=True); return
    if a.action=='teardown':
        if a.confirm_root is None or a.confirm_root.resolve()!=root: p.error('teardown needs --confirm-root equal to --root; preserves media/config files')
        subprocess.run(cmd+['down'],check=True); return
    if a.action=='status': subprocess.run(cmd+['ps','-a'],check=True); return
    if a.action in ('up','down'):
        if not a.version: p.error('--version required')
        service='jf-'+a.version.replace('.','-')
        # One version active at a time on the small Docker VM; leave other fixtures stopped.
        if a.action=='up':
            others=['jf-'+v.replace('.','-') for v in VERSIONS if v!=a.version]
            subprocess.run(cmd+['stop',*others],check=True)
        subprocess.run(cmd+(['up','-d',service] if a.action=='up' else ['stop',service]),check=True)
        if a.action=='up':
            import time, urllib.request
            for attempt in range(90):
                try:
                    request=urllib.request.Request(f'http://127.0.0.1:{VERSIONS[a.version]}/System/Info/Public',headers={'Accept':'application/json; profile="PascalCase"'})
                    with urllib.request.urlopen(request,timeout=2) as response:
                        if response.status==200 and 'Version' in json.load(response): break
                except Exception: pass
                time.sleep(2)
            else: raise SystemExit('fixture did not become ready within 180 seconds')
    elif a.action=='live-up': subprocess.run(cmd+['up','-d','origin','channels'],check=True)
    elif a.action=='live-down': subprocess.run(cmd+['stop','channels','origin'],check=True)
if __name__=='__main__': main()
