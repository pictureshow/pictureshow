#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Portable local checks for privacy and the cleanup error-classification boundary."""
import json,pathlib,re
from contracts import Capture,recording_receipt,delete_owned

from api import close_result
assert [close_result(s) for s in [204,404,401,403,409,500]]==['closed','already-closed','cleanup-failed','cleanup-failed','cleanup-failed','cleanup-failed']
c=Capture();c.secrets=['canary-token','canary-password']
value=c.clean({'AccessToken':'canary-token','TranscodingUrl':'http://private/?api_key=canary-token','Path':'/private/location','Message':'canary-password','GroupId':'f'*32,'Data':'ffffffff-ffff-ffff-ffff-ffffffffffff'})
assert 'canary' not in json.dumps(value) and 'private' not in json.dumps(value)
assert value['GroupId'].startswith('id-') and value['Data'].startswith('id-')
root=pathlib.Path(__file__).resolve().parent
for file in root.rglob('*'):
    if not file.is_file() or '__pycache__' in file.parts: continue
    text=file.read_text()
    # Private addresses, home paths, credentials and machine identities cannot ship.
    assert not re.search(r'/'+r'Users/[a-z][a-z0-9_-]*/|10\.0\.\d+\.\d+|192\.168\.\d+\.\d+',text),file.name
    if file.parent.name=='captures' and file.suffix=='.json':
        data=json.loads(text)
        def walk(x):
            if isinstance(x,dict):
                for k,v in x.items():
                    assert not any(s in k.lower() for s in ['accesstoken','opentoken','transcodingurl','remoteendpoint']), (file.name,k)
                    walk(v)
            elif isinstance(x,list):
                for v in x: walk(v)
        walk(data)
print('PASS: close 204/404/error classification, redaction canaries, public-file privacy')

# A real failure-path regression: second mutation fails after the first succeeds.
class PartialTimerFailure:
    user='fixture-user'
    def __init__(self):
        import datetime as dt
        self.rows=[]; self.posts=0; self.deleted=[]; self.rules=[]; self.start=dt.datetime.now(dt.timezone.utc)+dt.timedelta(seconds=45)
    def ok(self,method,path,body=None,query=None):
        if path=='/LiveTv/Channels': return {'Items':[{'Id':'a'},{'Id':'b'}]}
        if path=='/LiveTv/Programs':
            import datetime as dt
            start=self.start;channel=query['channelIds']
            return {'Items':[{'Id':'future','IsSeries':True,'StartDate':'2099-01-01T00:00:00Z'}, {'Id':'program-'+channel,'ChannelId':channel,'StartDate':start.isoformat(),'EndDate':(start+dt.timedelta(seconds=120)).isoformat()}]}
        if path=='/LiveTv/Timers/Defaults': return {'ServiceName':'fixture-service'}
        if path=='/LiveTv/Timers' and method=='POST':
            self.posts+=1
            if self.posts==2: raise RuntimeError('second mutation failed')
            self.rows.append({'Id':'owned-first-timer','Name':body['Name']}); return None
        if path=='/LiveTv/Timers': return {'Items':self.rows}
        if path=='/LiveTv/Recordings': return {'Items':[]}
        if path=='/LiveTv/SeriesTimers' and method=='POST': self.rules=[{'Id':'owned-rule','Name':body['Name']}]; return None
        if path=='/LiveTv/SeriesTimers': return {'Items':self.rules,'TotalRecordCount':len(self.rules)}
        if method=='DELETE' and path=='/LiveTv/SeriesTimers/owned-rule': self.rules=[]; return None
        raise AssertionError((method,path))
    def req(self,method,path): self.deleted.append(path); return 204,None
fake=PartialTimerFailure()
try: recording_receipt(fake,None,Capture())
except RuntimeError as error: assert str(error)=='second mutation failed'
else: raise AssertionError('failure was not injected')
assert fake.deleted==['/LiveTv/Timers/owned-first-timer']
print('PASS: failed second timer creation cancels the first owned timer')

# One cleanup failure must fail the capture and still attempt the second resource.
class CleanupFailure:
    def __init__(self):self.calls=[]
    def req(self,method,path):
        self.calls.append(path)
        return (500 if len(self.calls)==1 else 204),None
fake=CleanupFailure();cap=Capture();delete_owned(fake,['/LiveTv/Timers/first','/LiveTv/Timers/second'],cap)
assert len(fake.calls)==2 and cap.checks[-1]['passed'] is False
print('PASS: cleanup HTTP500 is visible and remaining owned cleanup continues')

# Stale Idle is not completion; a new failed execution is never successful.
from refresh import refresh
from unittest.mock import patch
class RefreshSequence:
    def __init__(self,status):
        old={'Status':'Completed','StartTimeUtc':'2000-01-01T00:00:00Z','EndTimeUtc':'2000-01-01T00:00:01Z'}
        def task(state,result):return {'Id':'task','Key':'RefreshGuide','State':state,'LastExecutionResult':result}
        self.initial=task('Idle',old);self.reads=0
        self.states=[task('Idle',old),task('Running',old),task('Idle',old|{'Status':status,'EndTimeUtc':'2000-01-02T00:00:01Z'})]
    def ok(self,method,path):
        if method=='POST':return None
        if path=='/ScheduledTasks':return [self.initial]
        self.reads+=1;return self.states.pop(0)
with patch('time.sleep'):
    success=RefreshSequence('Completed');assert refresh(success,'RefreshGuide')['lastResultStatus']=='Completed' and success.reads==3
    try:refresh(RefreshSequence('Failed'),'RefreshGuide')
    except RuntimeError:pass
    else:raise AssertionError('failed refresh reported as success')
print('PASS: refresh waits past stale Idle and rejects a failed new execution')

from api import request_url
assert request_url('https://fixture.invalid/proxy','/Items')=='https://fixture.invalid/proxy/Items'
assert request_url('https://fixture.invalid/proxy','https://fixture.invalid/proxy/Videos/master.m3u8')=='https://fixture.invalid/proxy/Videos/master.m3u8'
try:request_url('https://fixture.invalid','https://other.invalid/master.m3u8')
except RuntimeError:pass
else:raise AssertionError('fixture authorization could cross origins')
print('PASS: relative/base-prefix and same-origin absolute media URLs; off-origin auth rejected')
