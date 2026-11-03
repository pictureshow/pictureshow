#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Run one discovered fixture library/guide task and prove a new successful result."""
import argparse,json,os,time,datetime as dt,urllib.parse,uuid
from api import API,vault,credential

def refresh(client,key,timeout=180):
    task=next(t for t in client.ok('GET','/ScheduledTasks') if t['Key']==key)
    deadline=time.monotonic()+timeout
    # Do not attribute an already-running execution to this invocation.
    while task['State']!='Idle':
        if time.monotonic()>=deadline: raise RuntimeError('existing fixture refresh did not finish')
        time.sleep(1); task=client.ok('GET','/ScheduledTasks/'+task['Id'])
    baseline=task.get('LastExecutionResult')
    client.ok('POST','/ScheduledTasks/Running/'+task['Id'])
    while time.monotonic()<deadline:
        task=client.ok('GET','/ScheduledTasks/'+task['Id'])
        result=task.get('LastExecutionResult')
        if task['State']=='Idle' and result and result!=baseline:
            if result.get('Status')!='Completed': raise RuntimeError('fixture refresh completed unsuccessfully: '+str(result.get('Status')))
            return {'task':key,'state':task['State'],'lastResultStatus':result['Status'],'startUtc':result.get('StartTimeUtc'),'endUtc':result.get('EndTimeUtc')}
        time.sleep(1)
    raise RuntimeError('no new completed fixture refresh result within deadline')

def main():
    p=argparse.ArgumentParser();p.add_argument('task',choices=['guide','library']);a=p.parse_args()
    utc=dt.datetime.now(dt.timezone.utc)
    if 6<=utc.hour<10 or (utc.hour==5 and utc.minute>=50):p.error('outside fixture load window')
    client=API(os.environ['JF_URL'],'scope-refresh-'+a.task)
    creds=json.loads(os.environ['JF_CREDENTIALS'])['scope-admin'] if 'JF_CREDENTIALS' in os.environ else vault(credential(os.environ['JF_VERSION'],'scope-admin'))
    client.login(creds)
    try:
        busted=False
        if a.task=='guide':
            # Jellyfin caches XMLTV HTTP source content independently of task runs.
            config=client.ok('GET','/System/Configuration/livetv')
            providers=[p for p in config.get('ListingProviders',[]) if p.get('Type')=='xmltv' and urllib.parse.urlsplit(p.get('Path','')).hostname=='origin' and urllib.parse.urlsplit(p.get('Path','')).path=='/guide.xml']
            if len(providers)!=1:raise RuntimeError('guide refresh requires exactly one fixture XMLTV provider at origin/guide.xml')
            provider=dict(providers[0]);source=urllib.parse.urlsplit(provider['Path'])
            provider['Path']=urllib.parse.urlunsplit(source._replace(query=urllib.parse.urlencode({'fixture':uuid.uuid4().hex})))
            client.ok('POST','/LiveTv/ListingProviders',provider,query={'validateListings':'true','validateLogin':'false'});busted=True
        result=refresh(client,'RefreshGuide' if a.task=='guide' else 'RefreshLibrary')
        if a.task=='guide':result['sourceCacheBusted']=busted
        if a.task=='guide':result['channels']=client.ok('GET','/LiveTv/Channels')['TotalRecordCount']
        print(json.dumps(result))
    finally:client.ok('POST','/Sessions/Logout')
if __name__=='__main__':main()
