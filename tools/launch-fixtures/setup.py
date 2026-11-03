#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Initialize ONLY a new disposable fixture. Credentials go directly to vault."""
import argparse, json, secrets, time
from api import API,put,credential,vault
p=argparse.ArgumentParser(); p.add_argument('--url',required=True); p.add_argument('--version',required=True); p.add_argument('--resume',action='store_true'); a=p.parse_args()
admin=API(a.url,'scope-setup')
for attempt in range(90):
    try:
        wizard_status,wizard=admin.req('GET','/Startup/Configuration')
        if wizard_status in (401,403) or (wizard_status==200 and isinstance(wizard,dict)): break
    except RuntimeError: pass
    time.sleep(2)
else: raise RuntimeError('fixture startup readiness timed out')
info=admin.ok('GET','/System/Info/Public')
completed=wizard_status in (401,403)
if completed and not a.resume: raise SystemExit('Already initialized; refusing to replace users or configuration')
for user in ['scope-admin','scope-viewer-a','scope-viewer-b','scope-restricted']:
    # Save before any mutation so a failed setup remains recoverable.
    try: vault(credential(a.version,user)); continue
    except Exception: pass
    put(credential(a.version,user),{'service':'Pictureshow fixture Jellyfin','username':user,'password':secrets.token_urlsafe(24),'url':a.url,'version':a.version,'identity':'Legitimate LLC','note':'84A disposable generated-media fixture only'})
cred=vault(credential(a.version,'scope-admin'))
if not completed:
    admin.ok('POST','/Startup/Configuration',{'UICulture':'en-US','MetadataCountryCode':'US','PreferredMetadataLanguage':'en'})
    admin.ok('GET','/Startup/User')
    admin.ok('POST','/Startup/User',{'Name':cred['username'],'Password':cred['password']})
    admin.ok('POST','/Startup/RemoteAccess',{'EnableRemoteAccess':True,'EnableAutomaticPortMapping':False})
    admin.ok('POST','/Startup/Complete')
admin.login(cred)
# Local/generated-only metadata; no external metadata providers.
existing={f['Name'] for f in admin.ok('GET','/Library/VirtualFolders')}
for name,kind,path in [('Scope Music','music','/media/music'),('Scope Video','movies','/media/video'),('Scope Restricted','movies','/media/restricted')]:
    if name in existing: continue
    admin.ok('POST','/Library/VirtualFolders',{'LibraryOptions':{'EnableRealtimeMonitor':False,'EnableInternetProviders':False,'EnableAutomaticSeriesGrouping':False,'PathInfos':[{'Path':path}],'MetadataSavers':[],'TypeOptions':[{'Type':t,'MetadataFetchers':[],'ImageFetchers':[]} for t in ['Movie','MusicArtist','MusicAlbum','Audio']]}}, {'name':name,'collectionType':kind,'refreshLibrary':'false'})
admin.ok('POST','/Library/Refresh')
for attempt in range(90):
    folders=admin.ok('GET','/Library/VirtualFolders')
    if len(folders)>=3 and all(f.get('ItemId') for f in folders): break
    time.sleep(2)
else: raise RuntimeError('Library scan did not assign root item IDs in 180 seconds')
permitted=[f['ItemId'] for f in folders if f['Name']!='Scope Restricted']
for user in ['scope-viewer-a','scope-viewer-b','scope-restricted']:
    cred=vault(credential(a.version,user)); users=admin.ok('GET','/Users'); u=next((u for u in users if u['Name']==user),None)
    if u is None: u=admin.ok('POST','/Users/New',{'Name':user,'Password':cred['password']})
    policy=u['Policy']; policy.update({'IsAdministrator':False,'EnableAllFolders':user=='scope-viewer-a','EnabledFolders':permitted,'EnableContentDownloading':user!='scope-restricted','EnableLiveTvAccess':True,'EnableLiveTvManagement':user!='scope-restricted','EnableRemoteControlOfOtherUsers':user!='scope-restricted','EnableSharedDeviceControl':True,'SyncPlayAccess':'None' if user=='scope-restricted' else 'CreateAndJoinGroups'})
    admin.ok('POST',f'/Users/{u["Id"]}/Policy',policy)
config=admin.ok('GET','/System/Configuration/livetv')
config.update({'RecordingPath':'/recordings','MovieRecordingPath':'/recordings/movies','SeriesRecordingPath':'/recordings/series','EnableRecordingSubfolders':False})
admin.ok('POST','/System/Configuration/livetv',config)
if not any(t.get('FriendlyName')=='Scope generated tuner' for t in config.get('TunerHosts',[])):
    admin.ok('POST','/LiveTv/TunerHosts',{'Type':'m3u','Url':'http://origin:8080/channels.m3u','FriendlyName':'Scope generated tuner','SimultaneousStreamLimit':1,'EnableStreamLooping':False,'AllowHWTranscoding':False,'ImportFavoritesOnly':False})
if not any(t.get('Path')=='http://origin:8080/guide.xml' for t in config.get('ListingProviders',[])):
    admin.ok('POST','/LiveTv/ListingProviders',{'Type':'xmltv','Path':'http://origin:8080/guide.xml','EnableAllTuners':True}, {'validateListings':'true','validateLogin':'false'})
admin.ok('POST','/Library/Refresh')
tasks=admin.ok('GET','/ScheduledTasks')
for task in tasks:
    if task.get('Key')=='RefreshGuide': admin.ok('POST','/ScheduledTasks/Running/'+task['Id'])
print(json.dumps({'version':info['Version'],'libraries':[f['Name'] for f in folders],'usersCreated':3,'tunerLimit':1,'guideTaskDiscovered':any(t.get('Key')=='RefreshGuide' for t in tasks)}))

admin.ok('POST','/Sessions/Logout')
