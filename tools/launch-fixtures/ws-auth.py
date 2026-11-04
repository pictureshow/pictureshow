#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Record safe raw WebSocket handshake statuses, including HTTP error responses."""
import argparse,base64,http.client,json,os,pathlib,secrets,urllib.parse
from api import API,vault,credential
p=argparse.ArgumentParser();p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args();version=os.environ['JF_VERSION'];client=API(os.environ['JF_URL'],'scope-ws-auth');rows=[]
cred=json.loads(os.environ['JF_CREDENTIALS'])['scope-viewer-a'] if 'JF_CREDENTIALS' in os.environ else vault(credential(version,'scope-viewer-a'));client.login(cred)
try:
    base=urllib.parse.urlsplit(client.url)
    for mode in ['api_key','ApiKey','header']:
        h={'Upgrade':'websocket','Connection':'Upgrade','Sec-WebSocket-Key':base64.b64encode(secrets.token_bytes(16)).decode(),'Sec-WebSocket-Version':'13','Origin':client.url}
        path=base.path.rstrip('/')+'/socket'
        if mode=='header': h['Authorization']=f'MediaBrowser Token="{client.token}", DeviceId="{client.device}"'
        else: path+='?'+urllib.parse.urlencode({mode:client.token,'deviceId':client.device})
        cls=http.client.HTTPSConnection if base.scheme=='https' else http.client.HTTPConnection
        connection=cls(base.hostname,base.port,timeout=15)
        try:
            connection.request('GET',path,headers=h);response=connection.getresponse()
            rows.append({'authForm':mode,'status':response.status,'upgrade':response.getheader('Upgrade'),'transferEncoding':response.getheader('Transfer-Encoding')})
        finally:connection.close()
finally:client.ok('POST','/Sessions/Logout')
result={'version':version,'handshakes':rows,'passed':all(r['status']==101 for r in rows if r['authForm'] in ['ApiKey','header']),'scope':'Raw handshake only. Native header and common ApiKey query required; legacy api_key result recorded without assuming acceptance.'}
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if not result['passed']:raise SystemExit(1)
