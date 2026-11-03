#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Real HTTP/WS receipts for synthetic fixture servers. Exit nonzero on any failure.
Environment: JF_URL, JF_VERSION; credentials JF_CREDENTIALS JSON or vault entries.
No raw URLs, tokens, server paths or private IDs are emitted into captures.
"""
import argparse, datetime as dt, hashlib, json, os, pathlib, re, subprocess, tempfile, threading, time, urllib.parse, uuid
from api import API,vault,credential,close_result
from websockets.sync.client import connect
class Capture:
    def __init__(self): self.ids={}; self.checks=[]; self.events=[]; self.secrets=[]; self.lock=threading.RLock()
    def clean(self,x,key=''):
        with self.lock: return self._clean(x,key)
    def _clean(self,x,key=''):
        if isinstance(x,dict): return {k:self.clean(v,k) for k,v in x.items() if not any(s in k.lower() for s in ['token','apikey','api_key','path','url','endpoint','serveraddress','servername','imageblurhash'])}
        if isinstance(x,list): return [self.clean(v,key) for v in x]
        if isinstance(x,str):
            if any(s and s in x for s in self.secrets): return '<redacted>'
            if '://' in x or x.startswith(('/media/','/config/','/cache/','/recordings/')): return '<redacted-location>'
            if (key.lower().endswith('id') or re.fullmatch(r'[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}',x)) and x:
                try: identity=uuid.UUID(x).hex
                except ValueError: identity=x
                if identity not in self.ids: self.ids[identity]=f'id-{len(self.ids)+1:03d}'
                return self.ids[identity]
        return x
    def check(self,name,condition,details=None):
        self.checks.append({'name':name,'passed':bool(condition),'observed':self.clean(details)})
        print(name+(': PASS' if condition else ': FAIL'),flush=True)
        if not condition: print(json.dumps(self.clean(details)),flush=True)
    def event(self,source,data): self.events.append({'source':source,'envelope':self.clean(data)})
class Socket:
    def __init__(self,api,cap,name):
        self.cap=cap; self.name=name; self.messages=[]
        self.ws=connect(api.url.replace('http:','ws:').replace('https:','wss:')+'/socket?'+urllib.parse.urlencode({'ApiKey':api.token,'deviceId':api.device}),open_timeout=10)
        self.thread=threading.Thread(target=self.read,daemon=True); self.thread.start()
    def read(self):
        try:
            for raw in self.ws:
                msg=json.loads(raw); self.messages.append(msg)
                if msg.get('MessageType') in ['Play','Playstate','GeneralCommand','Sessions','SyncPlayCommand','SyncPlayGroupUpdate','UserDataChanged']: self.cap.event(self.name,msg)
        except Exception: pass
    def send(self,typ,data=None): self.ws.send(json.dumps({'MessageType':typ,**({'Data':data} if data is not None else {})}))
    def wait(self,predicate,timeout=8):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            match=next((m for m in self.messages if predicate(m)),None)
            if match is not None: return match
            time.sleep(.1)
        return None
    def close(self): self.ws.close(); self.thread.join(timeout=3)
def encoder_pids():
    container=os.environ.get('JF_CONTAINER'); host=os.environ.get('JF_DOCKER_HOST')
    if not container: return None
    command=['docker','top',container,'-eo','pid,comm']
    if host: command=['ssh',host,*command]
    output=subprocess.check_output(command,text=True)
    return sorted(int(line.split()[0]) for line in output.splitlines()[1:] if line.split()[-1].endswith('/ffmpeg') or line.split()[-1]=='ffmpeg')

PROFILE={'Name':'Scope H264 AAC','MaxStreamingBitrate':1500000,'DirectPlayProfiles':[], 'TranscodingProfiles':[{'Container':'ts','Type':'Video','VideoCodec':'h264','AudioCodec':'aac','Protocol':'hls','Context':'Streaming','MaxAudioChannels':'2','MinSegments':1,'SegmentLength':2,'BreakOnNonKeyFrames':False},{'Container':'mp3','Type':'Audio','AudioCodec':'mp3','Protocol':'http','Context':'Streaming'}]}
def main():
    p=argparse.ArgumentParser(); p.add_argument('--out',required=True,type=pathlib.Path); p.add_argument('--mode',choices=['transport','recordings'],default='transport'); a=p.parse_args()
    a.out.unlink(missing_ok=True)
    utc=dt.datetime.now(dt.timezone.utc)
    if 6<=utc.hour<10 or (utc.hour==5 and utc.minute>=50): p.error('fixture checks excluded 06:00–10:00Z; need ten minutes of safe runway')
    url=os.environ['JF_URL']; version=os.environ['JF_VERSION']; cap=Capture(); clients=[]; sockets=[]; live=[]; enc=[]; original_policy=None; vb=None; admin=None; completed=False
    def login(user,device):
        creds=json.loads(os.environ['JF_CREDENTIALS'])[user] if 'JF_CREDENTIALS' in os.environ else vault(credential(version,user))
        client=API(url,device+'-'+a.mode); client.login(creds); cap.secrets.extend([creds['password'],client.token]); clients.append(client); return client
    try:
        admin=login('scope-admin','scope-contract-admin'); va=login('scope-viewer-a','scope-contract-a'); vb=login('scope-viewer-b','scope-contract-b'); denied=login('scope-restricted','scope-contract-denied')
        info=va.ok('GET','/System/Info/Public'); cap.check('server-version',info['Version']==version or (version=='12.4' and info['Version']=='12.4.0'),{'Version':info['Version']})
        if a.mode=='transport':
            status,schema,headers=va.req('GET','/api-docs/openapi.json',raw=True); cap.check('served-schema',status==200,{'sha256':hashlib.sha256(schema).hexdigest()}); doc=json.loads(schema)
            cap.check('hidden-hls-schema-boundary',('/Videos/{itemId}/master.m3u8' in doc['paths'])==(version!='12.4'),{'masterInSchema':'/Videos/{itemId}/master.m3u8' in doc['paths'],'cleanupInSchema':'/Videos/ActiveEncodings' in doc['paths']})

        tracks=va.ok('GET','/Items',query={'userId':va.user,'recursive':'true','includeItemTypes':'Audio','fields':'MediaSources','sortBy':'ParentIndexNumber,IndexNumber,SortName'})['Items']
        cap.check('music-fixtures-scanned',len(tracks)>=11,{'count':len(tracks),'tracks':[{k:t.get(k) for k in ['Id','Name','Album','Artists','AlbumArtist','IndexNumber','ParentIndexNumber','Container']} for t in tracks]})
        discs=[t for t in tracks if t.get('Album')=='Two Discs']
        compilation=[t for t in tracks if t.get('Album')=='Compilation']
        cap.check('music-disc-and-compilation-metadata',[(t.get('ParentIndexNumber'),t.get('IndexNumber')) for t in discs]==[(1,1),(1,2),(2,1),(2,2)] and len(compilation)==3 and len({t['Id'] for t in compilation})==3 and all(t.get('Name')=='Shared Title' and t.get('AlbumArtist')=='Various Artists' for t in compilation) and sorted(t.get('IndexNumber') or 0 for t in compilation)==[0,1,1],{'discOrder':[[t.get('ParentIndexNumber'),t.get('IndexNumber')] for t in discs],'compilation':[{k:t.get(k) for k in ['Id','Name','AlbumArtist','Artists','IndexNumber']} for t in compilation]})
        track=tracks[0]['Id']
        videos=va.ok('GET','/Items',query={'userId':va.user,'recursive':'true','includeItemTypes':'Movie','fields':'MediaSources'})['Items']; video=next(v['Id'] for v in videos if v['Name']=='Scope Pattern'); hidden=next(v['Id'] for v in videos if v['Name']=='Restricted Pattern')
        # No media URL is captured; download bytes checked and decoded in a temporary file.
        code,media,h=va.req('GET',f'/Items/{track}/Download',raw=True)
        decoded=False
        if code==200:
            with tempfile.NamedTemporaryFile(suffix='.media') as f:
                f.write(media); f.flush(); decoded=subprocess.run(['ffmpeg','-v','error','-i',f.name,'-t','1','-f','null','-'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
        cap.check('original-download-real-decode',code==200 and len(media)>1000 and decoded,{'status':code,'bytes':len(media),'sha256':hashlib.sha256(media).hexdigest(),'decoded':decoded})
        code,ranged,h=va.req('GET',f'/Items/{track}/Download',raw=True,headers={'Range':'bytes=0-1023'})
        cap.check('original-range',code==206 and ranged==media[:1024],{'status':code,'bytes':len(ranged),'Content-Range':h.get('Content-Range')})
        playlists=va.ok('GET','/Items',query={'userId':va.user,'recursive':'true','includeItemTypes':'Playlist'})['Items']
        name='Scope duplicate receipt '+a.mode
        existing=next((p for p in playlists if p['Name']==name),None)
        if existing: playlist=existing['Id']
        else: playlist=va.ok('POST','/Playlists',{'Name':name,'Ids':[track],'UserId':va.user,'MediaType':'Audio'})['Id']
        before=va.ok('GET',f'/Playlists/{playlist}/Items',query={'userId':va.user})['Items']
        va.ok('POST',f'/Playlists/{playlist}/Items',query={'ids':track,'userId':va.user})
        entries=va.ok('GET',f'/Playlists/{playlist}/Items',query={'userId':va.user})['Items']
        cap.check('playlist-repeat-runtime-receipt',len(entries)>=1,{'repeatPreserved':len(entries)>len(before),'distinctEntryIds':len({t.get('PlaylistItemId') for t in entries})==len(entries),'beforeCount':len(before),'afterCount':len(entries),'entries':[{k:t.get(k) for k in ['Id','PlaylistItemId','Name']} for t in entries]})
        code,_=denied.req('GET',f'/Items/{track}/Download'); cap.check('denied-download',code in [401,403],{'status':code})
        code,_=vb.req('GET','/Items/'+hidden,query={'userId':vb.user}); cap.check('denied-library-item',code in [401,403,404],{'status':code})
        code,_=denied.req('POST','/LiveTv/Timers',{'Name':'must be denied'}); cap.check('denied-dvr-management',code in [401,403],{'status':code})
        if a.mode=='recordings':
            recording_receipt(va,admin,cap); completed=True; return
        # Authenticated HLS, a real segment, and two owned encodings to detect overly broad cleanup.
        sessions=[]; pidsets=[]; baseline=encoder_pids()
        for n in range(2):
            pi=va.ok('POST',f'/Items/{video}/PlaybackInfo',{'UserId':va.user,'DeviceProfile':PROFILE,'EnableDirectPlay':False,'EnableDirectStream':False,'EnableTranscoding':True,'MaxStreamingBitrate':500000})
            source=pi['MediaSources'][0]; sid=pi['PlaySessionId']; enc.append((va,sid))
            path=source.get('TranscodingUrl')
            if not path: raise RuntimeError('No HLS source from real profile')
            # Returned URL retained only in memory; auth also supplied through header.
            if path.startswith(url): path=path[len(url):]
            status,master,_=va.req('GET',path,raw=True)
            cap.check(f'hls-master-{n}',status==200 and b'#EXTM3U' in master,{'status':status,'bytes':len(master),'playSessionId':sid,'mediaSourceId':source['Id']})
            child=next(l for l in master.decode().splitlines() if l and not l.startswith('#'))
            childpath=urllib.parse.urljoin(path,child)
            status,variant,_=va.req('GET',childpath,raw=True)
            segment=next(l for l in variant.decode().splitlines() if l and not l.startswith('#'))
            segmentpath=urllib.parse.urljoin(childpath,segment)
            status,data,_=va.req('GET',segmentpath,raw=True)
            decode=False
            with tempfile.NamedTemporaryFile(suffix='.ts') as f:
                f.write(data); f.flush(); decode=subprocess.run(['ffmpeg','-v','error','-i',f.name,'-f','null','-'],stderr=subprocess.DEVNULL,stdout=subprocess.DEVNULL).returncode==0
            cap.check(f'hls-segment-decode-{n}',status==200 and len(data)>1000 and decode,{'status':status,'bytes':len(data),'decoded':decode})
            sessions.append({'sid':sid,'segment':segmentpath}); pidsets.append(encoder_pids())
        status,_=va.req('DELETE','/Videos/ActiveEncodings',query={'deviceId':va.device,'playSessionId':sessions[0]['sid']}); enc.remove((va,sessions[0]['sid']))
        time.sleep(.5); after_first=encoder_pids()
        status2,data,_=va.req('GET',sessions[1]['segment'],raw=True)
        cap.check('owned-encoding-cleanup-survivor',status==204 and status2==200 and len(data)>1000 and (after_first is None or (len(set(pidsets[1])-set(baseline))==2 and len(set(after_first)-set(baseline))==1 and set(after_first)-set(baseline)<=set(pidsets[1])-set(pidsets[0]))),{'cleanup':status,'otherOwnedSegment':status2,'processEvidence':{'baseline':baseline,'afterEachOpen':pidsets,'afterFirstCleanup':after_first},'processInspection':'observed' if after_first is not None else 'not requested; HTTP only'})
        status,_=va.req('DELETE','/Videos/ActiveEncodings',query={'deviceId':va.device,'playSessionId':sessions[1]['sid']}); enc.clear(); time.sleep(.5); final_pids=encoder_pids(); cap.check('owned-encoding-cleanup-final',status==204 and (final_pids is None or final_pids==baseline),{'status':status,'remainingPids':final_pids})
        channels=va.ok('GET','/LiveTv/Channels',query={'userId':va.user})['Items']; cap.check('two-live-channels',len(channels)==2,{'channels':[{k:c.get(k) for k in ['Id','Name','Number']} for c in channels]})
        guide=va.ok('GET','/LiveTv/GuideInfo'); programs=va.ok('GET','/LiveTv/Programs',query={'userId':va.user,'channelIds':channels[0]['Id'],'fields':'SeriesInfo'})['Items']; cap.check('guide-refresh-mapping',len(programs)>0 and any(p.get('IsSeries') for p in programs),{'guide':guide,'programCount':len(programs),'sample':programs[:2]})
        mapping=admin.ok('GET','/LiveTv/ChannelMappingOptions',query={'providerId':admin.ok('GET','/System/Configuration/livetv')['ListingProviders'][0]['Id']}); cap.check('channel-mapping-receipt',len(mapping.get('TunerChannels',[]))==2,mapping)
        channel=channels[0]['Id']
        for mode in ['json','query']:
            pi=va.ok('POST',f'/Items/{channel}/PlaybackInfo',{'UserId':va.user,'DeviceProfile':PROFILE,'AutoOpenLiveStream':False}); src=pi['MediaSources'][0]; sid=pi['PlaySessionId']
            fields={'OpenToken':src['OpenToken'],'ItemId':channel,'UserId':va.user,'PlaySessionId':sid,'MaxStreamingBitrate':700000,'MaxAudioChannels':2,'EnableDirectPlay':False,'EnableDirectStream':False}
            query={k[0].lower()+k[1:]:str(v).lower() if isinstance(v,bool) else v for k,v in fields.items()}
            status,opened=va.req('POST','/LiveStreams/Open',fields|{'DeviceProfile':PROFILE} if mode=='json' else {'DeviceProfile':PROFILE},None if mode=='json' else query)
            liveid=(opened or {}).get('MediaSource',{}).get('LiveStreamId')
            negotiated=(opened or {}).get('MediaSource',{})
            query_echo=urllib.parse.parse_qs(urllib.parse.urlsplit(negotiated.get('TranscodingUrl','')).query)
            public_query={k:v for k,v in query_echo.items() if k.lower() in ['playsessionid','deviceid','mediasourceid','liveStreamid'.lower(),'videobitrate','audiobitrate','maxaudiochannels','videocodec','audiocodec']}
            echo={k.lower():v[0] for k,v in public_query.items()}
            cap.check('live-open-'+mode,status==200 and bool(liveid),{'status':status,'requested':fields,'returnedTransportFields':public_query,'requestedSessionEchoed':echo.get('playsessionid')==sid,'directPlayDisabled':negotiated.get('SupportsDirectPlay') is False,'directStreamDisabled':negotiated.get('SupportsDirectStream') is False,'response':opened})
            if liveid:
                live.append((va,liveid)); source=opened['MediaSource']
                # Need actual live bytes in addition to the DTO receipt.
                stream=source.get('TranscodingUrl')
                if stream:
                    s,b,_=va.req('GET',stream,raw=True); cap.check('live-manifest-'+mode,s==200 and b'#EXTM3U' in b,{'status':s,'bytes':len(b)})
                va.ok('POST','/Sessions/Playing',{'ItemId':channel,'MediaSourceId':source['Id'],'PlaySessionId':sid,'LiveStreamId':liveid,'CanSeek':False,'PlayMethod':'Transcode'})
                va.ok('POST','/Sessions/Playing/Stopped',{'ItemId':channel,'MediaSourceId':source['Id'],'PlaySessionId':sid,'LiveStreamId':liveid,'PositionTicks':10000000})
                status,_=va.req('POST','/LiveStreams/Close',query={'liveStreamId':liveid}); cap.check('stopped-then-close-'+mode,close_result(status)!='cleanup-failed',{'status':status,'classification':close_result(status),'interpretation':'already closed' if status==404 else 'success; 204 alone does not prove a stream existed'})
                status2,_=va.req('POST','/LiveStreams/Close',query={'liveStreamId':liveid}); cap.check('repeat-close-'+mode,close_result(status2)!='cleanup-failed',{'status':status2,'classification':close_result(status2),'observation':'server returned actual status; 404 handler also exercised by local classification cases'}); live.remove((va,liveid))
                va.req('DELETE','/Videos/ActiveEncodings',query={'deviceId':va.device,'playSessionId':sid})
        # Remote target is an independent authenticated viewer/session/socket.
        for client in [va,vb]: client.ok('POST','/Sessions/Capabilities/Full',{'PlayableMediaTypes':['Video','Audio'],'SupportedCommands':['SetVolume','Mute','Unmute'],'SupportsMediaControl':True,'SupportsPersistentIdentifier':True,'DeviceProfile':PROFILE})
        sa=Socket(va,cap,'viewer-a'); sb=Socket(vb,cap,'viewer-b'); sockets.extend([sa,sb]); sa.send('SessionsStart','0,1000')
        sessions=admin.ok('GET','/Sessions'); target=next(s['Id'] for s in sessions if s.get('DeviceId')==vb.device)
        status,_=va.req('POST',f'/Sessions/{target}/Playing',query={'playCommand':'PlayNow','itemIds':track,'startPositionTicks':10000000})
        event=sb.wait(lambda m:m.get('MessageType')=='Play'); cap.check('session-play-query-envelope',status==204 and event is not None,{'status':status,'event':event})
        status,_=va.req('POST',f'/Sessions/{target}/Playing/Pause'); event=sb.wait(lambda m:m.get('MessageType')=='Playstate'); cap.check('session-pause-envelope',status==204 and event is not None,{'status':status,'event':event})
        status,_=va.req('POST',f'/Sessions/{target}/Command',{'Name':'SetVolume','Arguments':{'Volume':'35'}}); event=sb.wait(lambda m:m.get('MessageType')=='GeneralCommand'); cap.check('session-general-command-envelope',status==204 and event is not None,{'status':status,'event':event})
        cap.check('viewer-sessions-subscription-not-admin',sa.wait(lambda m:m.get('MessageType')=='Sessions',timeout=2) is None,{'note':'ordinary viewer receives no Sessions; use authorized GET /Sessions polling'})
        sad=Socket(admin,cap,'admin-envelope-only'); sockets.append(sad); sad.send('SessionsStart','0,1000'); time.sleep(.2)
        vb.ok('POST','/Sessions/Capabilities/Full',{'PlayableMediaTypes':['Audio','Video'],'SupportedCommands':['SetVolume'],'SupportsMediaControl':True})
        cap.check('admin-sessions-subscription-envelope',sad.wait(lambda m:m.get('MessageType')=='Sessions') is not None); sad.send('SessionsStop')
        sa.send('SessionsStop')
        # Policy denial can be HTTP or a semantic denied event; capture both.
        sd=Socket(denied,cap,'restricted'); sockets.append(sd)
        status,_=denied.req('POST','/SyncPlay/New',{'GroupName':'Denied'}); time.sleep(.3)
        cap.check('syncplay-none-policy',status in [401,403] or any('Denied' in json.dumps(m) for m in sd.messages),{'status':status,'events':sd.messages})
        va.ok('POST','/SyncPlay/New',{'GroupName':'Scope transport'})
        groups=va.ok('GET','/SyncPlay/List'); group=next(g['GroupId'] for g in groups if g['GroupName']=='Scope transport')
        status,groupinfo=va.req('GET','/SyncPlay/'+group); cap.check('group-get-version-boundary',status==(404 if version=='10.10.7' else 200),{'status':status,'groupInfo':groupinfo})
        policy=admin.ok('GET','/Users/'+vb.user)['Policy']; original_policy=dict(policy); policy['SyncPlayAccess']='JoinGroups'; admin.ok('POST','/Users/'+vb.user+'/Policy',policy)
        status,_=vb.req('POST','/SyncPlay/New',{'GroupName':'Join-only must be denied'}); time.sleep(.3)
        cap.check('syncplay-join-only-create-denied',status in [401,403] or any('Denied' in json.dumps(m) for m in sb.messages),{'status':status,'events':sb.messages})
        vb.ok('POST','/SyncPlay/Join',{'GroupId':group}); va.ok('POST','/SyncPlay/SetNewQueue',{'PlayingQueue':[track,track],'PlayingItemPosition':0,'StartPositionTicks':10000000})
        queue=sa.wait(lambda m:m.get('MessageType')=='SyncPlayGroupUpdate' and m.get('Data',{}).get('Type')=='PlayQueue' and len(m['Data']['Data'].get('Playlist',[]))==2)
        if queue is None: queue=sa.wait(lambda m:'PlaylistItemId' in json.dumps(m) and m.get('MessageType')=='SyncPlayGroupUpdate')
        entries=queue['Data']['Data'].get('Playlist',[]) if queue else []
        cap.check('syncplay-queue-envelope',len(entries)==2 and entries[0].get('ItemId')==entries[1].get('ItemId') and len({e.get('PlaylistItemId') for e in entries})==2,queue)
        entry=queue['Data']['Data']['Playlist'][0]['PlaylistItemId']
        for client in [va,vb]: client.ok('POST','/SyncPlay/Ready',{'When':dt.datetime.now(dt.timezone.utc).isoformat(),'PositionTicks':10000000,'IsPlaying':False,'PlaylistItemId':entry})
        def scheduled_unpause(message):
            data=message.get('Data',{})
            return message.get('MessageType')=='SyncPlayCommand' and data.get('Command')=='Unpause' and data.get('PlaylistItemId')==entry and data.get('GroupId')==group
        va.ok('POST','/SyncPlay/Unpause'); command=sa.wait(scheduled_unpause)
        future=False
        if command:
            data=command['Data']; future=dt.datetime.fromisoformat(data['When'].replace('Z','+00:00'))>dt.datetime.fromisoformat(data['EmittedAt'].replace('Z','+00:00'))
        cap.check('syncplay-command-envelope',command is not None and future,command)
        second=sb.wait(scheduled_unpause)
        cap.check('syncplay-second-viewer-events',second is not None and command is not None and second['Data']['When']==command['Data']['When'],second)
        clock=va.ok('GET','/GetUtcTime'); cap.check('clock-envelope',all(k in clock for k in ['RequestReceptionTime','ResponseTransmissionTime']),clock)
        vb.ok('POST','/SyncPlay/Leave'); va.ok('POST','/SyncPlay/Leave'); policy['SyncPlayAccess']='CreateAndJoinGroups'; admin.ok('POST','/Users/'+vb.user+'/Policy',policy); completed=True
    except Exception as e:
        cap.check('harness-completed',False,{'error':str(e) if isinstance(e,RuntimeError) else type(e).__name__})
    finally:
        def cleanup(name,operation):
            try: operation()
            except Exception as error: cap.check('cleanup-'+name,False,{'error':type(error).__name__})
        if original_policy is not None and admin is not None and vb is not None: cleanup('policy',lambda:admin.ok('POST','/Users/'+vb.user+'/Policy',original_policy))
        for client,liveid in live: cleanup('live',lambda c=client,i=liveid:c.req('POST','/LiveStreams/Close',query={'liveStreamId':i}))
        for client,sid in enc: cleanup('encoding',lambda c=client,i=sid:c.ok('DELETE','/Videos/ActiveEncodings',query={'deviceId':c.device,'playSessionId':i}))
        for client in clients:
            cleanup('group-leave',lambda c=client:c.req('POST','/SyncPlay/Leave'))
            cleanup('logout',lambda c=client:c.ok('POST','/Sessions/Logout'))
        for socket in sockets: cleanup('socket',socket.close)
        result={'completed':completed,'fixture':'original-CC0','version':version,'mode':a.mode,'capturedUtc':dt.datetime.now(dt.timezone.utc).isoformat(),'checks':cap.checks,'websocketEvents':cap.events,'scope':'HTTP/WS/media evidence only; no renderer, casting, offline package or drift qualification'}
        a.out.parent.mkdir(parents=True,exist_ok=True); a.out.write_text(json.dumps(result,indent=2)+'\n')
        if not completed or not cap.checks or any(not c['passed'] for c in cap.checks): raise SystemExit(1)

def delete_owned(client, paths, cap):
    """Try every owned cleanup, retaining status failures and transport exceptions."""
    results=[]
    for path in dict.fromkeys(paths):
        try:
            status,_=client.req('DELETE',path)
            results.append({'resource':path.rsplit('/',2)[-2],'status':status,'closed':close_result(status)!='cleanup-failed'})
        except Exception as error:
            results.append({'resource':path.rsplit('/',2)[-2],'closed':False,'error':type(error).__name__})
    cap.check('recording-owned-resource-cleanup',all(r['closed'] for r in results),results)

def recording_receipt(viewer,admin,cap):
    now=dt.datetime.now(dt.timezone.utc); start=now+dt.timedelta(seconds=12); end=start+dt.timedelta(seconds=120)
    channels=viewer.ok('GET','/LiveTv/Channels',query={'userId':viewer.user})['Items']
    timers=[]; observations=[]; recordings=[]; series=None; run_prefix='Scope conflict '+uuid.uuid4().hex[:8]
    try:
        # Real server series identity and rule lifecycle, separate from occurrence timers.
        programs=viewer.ok('GET','/LiveTv/Programs',query={'channelIds':channels[0]['Id'],'fields':'SeriesInfo'})['Items']
        program=next(p for p in programs if p.get('IsSeries') and dt.datetime.fromisoformat(p['StartDate'].replace('Z','+00:00'))>now+dt.timedelta(hours=1))
        defaults=viewer.ok('GET','/LiveTv/Timers/Defaults',query={'programId':program['Id']})
        defaults.update({'ProgramId':program['Id'],'Name':run_prefix+' series','RecordAnyTime':False,'RecordAnyChannel':False,'ChannelId':channels[0]['Id'],'RecordNewOnly':True})
        viewer.ok('POST','/LiveTv/SeriesTimers',defaults)
        rules=viewer.ok('GET','/LiveTv/SeriesTimers')['Items']; series=next(r['Id'] for r in rules if r.get('Name')==run_prefix+' series')
        container=os.environ.get('JF_CONTAINER'); host=os.environ.get('JF_DOCKER_HOST'); internal=[]
        if container:
            cmd=['docker','exec',container,'cat','/config/data/livetv/seriestimers.json']
            if host: cmd=['ssh',host,*cmd]
            rows=json.loads(subprocess.check_output(cmd,text=True))
            internal=[r.get('SeriesId') for r in rows if r.get('Name')==run_prefix+' series']
            cap.check('server-internal-series-identity',len(internal)==1 and bool(internal[0]),{'SeriesId':internal[0] if internal else None,'note':'internal recorder identity; not exposed by Program DTO'})
        viewer.ok('DELETE','/LiveTv/SeriesTimers/'+series); series=None
        cap.check('series-rule-create-cancel',True,{'programIsSeries':program['IsSeries'],'seriesIdExposedInDto':bool(program.get('SeriesId')),'remainingRules':viewer.ok('GET','/LiveTv/SeriesTimers')['TotalRecordCount']})
        # Keep series scheduling isolated from the simultaneous one-off receipts.
        now=dt.datetime.now(dt.timezone.utc)
        all_programs=[p for channel in channels for p in viewer.ok('GET','/LiveTv/Programs',query={'channelIds':channel['Id'],'fields':'SeriesInfo'})['Items']]
        def parse_time(value):return dt.datetime.fromisoformat(value.replace('Z','+00:00'))
        pairs=[p for p in all_programs if 5<(parse_time(p['StartDate'])-now).total_seconds()<120 and (parse_time(p['EndDate'])-parse_time(p['StartDate'])).total_seconds()==120]
        if len(pairs)!=2 or {p['ChannelId'] for p in pairs}!={c['Id'] for c in channels} or len({(parse_time(p['StartDate']),parse_time(p['EndDate'])) for p in pairs})!=1:raise RuntimeError('regenerate fixture guide and refresh it immediately before recording mode; need one simultaneous two-minute program per fixture channel')
        start=parse_time(pairs[0]['StartDate']);end=parse_time(pairs[0]['EndDate'])
        baseline_recordings={r['Id'] for r in viewer.ok('GET','/LiveTv/Recordings',query={'userId':viewer.user})['Items']}
        for i,ch in enumerate(channels):
            program=next(p for p in pairs if p['ChannelId']==ch['Id'])
            defaults=viewer.ok('GET','/LiveTv/Timers/Defaults',query={'programId':program['Id']})
            body={k:defaults[k] for k in ['ServiceName','ExternalChannelId','PrePaddingSeconds','PostPaddingSeconds','Priority','IsPrePaddingRequired','IsPostPaddingRequired'] if k in defaults}
            body.update({'ProgramId':program['Id'],'Name':f'{run_prefix} {i}','ChannelId':ch['Id'],'StartDate':start.isoformat(),'EndDate':end.isoformat(),'PrePaddingSeconds':0,'PostPaddingSeconds':0,'Priority':i})
            before_ids={t['Id'] for t in viewer.ok('GET','/LiveTv/Timers')['Items']}
            viewer.ok('POST','/LiveTv/Timers',body)
            created=viewer.ok('GET','/LiveTv/Timers')['Items']
            timers.extend(t['Id'] for t in created if t['Id'] not in before_ids and t['Id'] not in timers)
        current=viewer.ok('GET','/LiveTv/Timers')['Items']
        owned_timers=[t for t in current if t['Id'] in timers]
        honored=len(owned_timers)==2 and all(abs((parse_time(t['StartDate'])-start).total_seconds())<1 and abs((parse_time(t['EndDate'])-end).total_seconds())<1 for t in owned_timers)
        cap.check('two-overlapping-timers-created',honored,{'timers':owned_timers,'requestedStart':start.isoformat(),'requestedEnd':end.isoformat()})
        if not honored:raise RuntimeError('server changed requested recording window')
        deadline=time.monotonic()+(end-now).total_seconds()+60; last=None; growing=False; recording_scan=False; final_scan=False; recording_rights=False; negotiation_attempts=[]; seen_growing_ids=set(); growing_ids=set(); active=[]
        while time.monotonic()<deadline:
            current=viewer.ok('GET','/LiveTv/Timers')['Items']
            states=[{'Id':t['Id'],'Status':t.get('Status'),'Name':t.get('Name')} for t in current if t['Id'] in timers]
            if states!=last: observations.append({'relativeSeconds':round((dt.datetime.now(dt.timezone.utc)-now).total_seconds(),1),'timers':states}); last=states
            if not recording_scan and any(t.get('Status')=='InProgress' for t in states):
                admin.ok('POST','/Library/Refresh'); recording_scan=True
            if not final_scan and dt.datetime.now(dt.timezone.utc)>end+dt.timedelta(seconds=5):
                admin.ok('POST','/Library/Refresh'); final_scan=True
            if recording_scan and not recording_rights:
                folders=admin.ok('GET','/Library/VirtualFolders')
                ids=[f['ItemId'] for f in folders if f.get('Name')=='Recordings' and f.get('ItemId')]
                if ids:
                    for user in admin.ok('GET','/Users'):
                        if user['Name'] in ['scope-viewer-b','scope-restricted']:
                            policy=user['Policy']; policy['EnabledFolders']=list(dict.fromkeys(policy.get('EnabledFolders',[])+ids)); admin.ok('POST','/Users/'+user['Id']+'/Policy',policy)
                    recording_rights=True
            recordings=viewer.ok('GET','/LiveTv/Recordings',query={'userId':viewer.user})['Items']
            owned=[r for r in recordings if r['Id'] not in baseline_recordings]
            growing_items=viewer.ok('GET','/LiveTv/Recordings',query={'userId':viewer.user,'isInProgress':'true'})['Items']
            growing_ids={r['Id'] for r in growing_items}
            seen_growing_ids.update(r['Id'] for r in growing_items if r['Id'] not in baseline_recordings and (not r.get('TimerId') or r['TimerId'] in timers))
            active=[t for t in states if t['Status']=='InProgress']
            if growing_items and not growing and any(t.get('Status')=='InProgress' for t in states):
                r=next((r for r in growing_items if r['Id'] not in baseline_recordings),None)
                if r is None: time.sleep(3); continue
                st,pi=viewer.req('POST',f'/Items/{r["Id"]}/PlaybackInfo',{'UserId':viewer.user,'DeviceProfile':PROFILE,'IsPlayback':True,'EnableDirectPlay':False,'EnableDirectStream':False,'EnableTranscoding':True,'MaxStreamingBitrate':500000})
                negotiation_attempts.append({'relativeSeconds':round((dt.datetime.now(dt.timezone.utc)-now).total_seconds(),1),'status':st,'source':pi})
                growing=st==200 and bool(pi.get('MediaSources'))
            if dt.datetime.now(dt.timezone.utc)>end+dt.timedelta(seconds=10) and owned and not (growing_ids & seen_growing_ids) and not active: break
            time.sleep(3)
        cap.check('growing-recording-negotiation',growing,negotiation_attempts)
        cap.check('growing-recording-observed',bool(negotiation_attempts),{'note':'queried isInProgress=true; source negotiation recorded separately'})
        cap.check('recording-timer-transitions',any(any(t.get('Status')=='InProgress' for t in o['timers']) for o in observations),observations)
        completed=[r for r in recordings if r['Id'] in seen_growing_ids and r['Id'] not in growing_ids]
        cap.check('completed-recording-present',len(completed)==2 and not active and dt.datetime.now(dt.timezone.utc)>end,{'recordings':completed,'remainingActiveTimers':active,'remainingGrowingIds':list(growing_ids & seen_growing_ids),'overlapOutcome':observations})
        for i,r in enumerate(completed):
            code,media,_=viewer.req('GET',f'/Items/{r["Id"]}/Download',raw=True)
            with tempfile.NamedTemporaryFile(suffix='.ts') as f:
                f.write(media); f.flush()
                probe=subprocess.run(['ffprobe','-v','error','-show_entries','format=duration:stream=codec_name','-of','json',f.name],capture_output=True,text=True)
                decode=subprocess.run(['ffmpeg','-v','error','-i',f.name,'-t','2','-f','null','-'],stderr=subprocess.DEVNULL,stdout=subprocess.DEVNULL).returncode==0
            cap.check(f'recording-download-decode-{i}',code==200 and len(media)>1000 and probe.returncode==0 and decode and 110<=float(json.loads(probe.stdout)['format']['duration'])<=150,{'status':code,'bytes':len(media),'sha256':hashlib.sha256(media).hexdigest(),'probe':json.loads(probe.stdout) if probe.returncode==0 else None,'decoded':decode,'expectedNominalSeconds':120,'acceptedHlsDurationSeconds':[110,150]})
            original=viewer.ok('GET',f'/UserItems/{r["Id"]}/UserData',query={'userId':viewer.user})
            viewer.ok('POST',f'/UserItems/{r["Id"]}/UserData',original|{'PlaybackPositionTicks':10000000},query={'userId':viewer.user})
            resumed=viewer.ok('GET',f'/UserItems/{r["Id"]}/UserData',query={'userId':viewer.user})
            cap.check(f'recording-resume-{i}',resumed.get('PlaybackPositionTicks')==10000000,{'before':original,'after':resumed})
            viewer.ok('POST',f'/UserItems/{r["Id"]}/UserData',original,query={'userId':viewer.user})
    finally:
        paths=['/LiveTv/Timers/'+timer for timer in timers]
        if series: paths.append('/LiveTv/SeriesTimers/'+series)
        for route,match in [('/LiveTv/SeriesTimers',lambda r:r.get('Name')==run_prefix+' series'),('/LiveTv/Timers',lambda r:r['Id'] in timers or r.get('Name','').startswith(run_prefix))]:
            try: paths.extend(route+'/'+r['Id'] for r in viewer.ok('GET',route)['Items'] if match(r))
            except Exception as error: cap.check('recording-cleanup-discovery',False,{'resource':route.rsplit('/',1)[-1],'error':type(error).__name__})
        delete_owned(viewer,paths,cap)
    # Completed generated recordings intentionally retained for downstream legs.

if __name__=='__main__': main()
