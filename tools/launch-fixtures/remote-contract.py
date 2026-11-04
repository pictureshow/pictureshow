#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Phone-as-remote protocol receipts: who may list, who may send, what a target receives, and what a restart does.
Isolated fixture only. Set JF_URL and JF_VERSION; credentials come from the vault. IDs and names are redacted."""
import argparse, json, os, pathlib, re, threading, time, uuid
from api import API, vault, credential
from websockets.sync.client import connect

HEX = re.compile(r'[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}')
ids = {}
def clean(x, key=''):
    if isinstance(x, dict):
        return {k: clean(v, k) for k, v in x.items() if not any(s in k.lower() for s in ['token', 'key', 'path', 'url', 'endpoint'])}
    if isinstance(x, list): return [clean(v, key) for v in x]
    if isinstance(x, str) and (HEX.fullmatch(x) or key.lower().endswith('id')) and x:
        norm = x.replace('-', '').lower()
        ids.setdefault(norm, f'id-{len(ids)+1:03d}')
        return ids[norm]
    if isinstance(x, str) and key in ('Name', 'UserName', 'DeviceName'): return '<name>'
    return x

checks = []
def check(name, passed, observed=None):
    checks.append({'name': name, 'passed': bool(passed), 'observed': clean(observed) if observed is not None else None})
    print(('PASS ' if passed else 'FAIL ') + name, flush=True)

class Target:
    """A target session: authenticated socket plus advertised capabilities, as the TV app does."""
    def __init__(self, api, commands=('MoveUp', 'SendString')):
        self.api, self.commands, self.messages, self.ws = api, list(commands), [], None
        self.open()
    def open(self):
        auth = f'MediaBrowser Client="Pictureshow Fixture", Device="fixture", DeviceId="{self.api.device}", Version="0", Token="{self.api.token}"'
        self.ws = connect(self.api.url.replace('http:', 'ws:').replace('https:', 'wss:') + '/socket', open_timeout=10, additional_headers={'Authorization': auth})
        threading.Thread(target=self.read, args=(self.ws,), daemon=True).start()
        threading.Thread(target=self.beat, args=(self.ws,), daemon=True).start()
        self.api.ok('POST', '/Sessions/Capabilities/Full', {'PlayableMediaTypes': ['Video'], 'SupportedCommands': self.commands, 'SupportsMediaControl': True, 'SupportsPersistentIdentifier': True})
    def read(self, ws):
        try:
            for raw in ws: self.messages.append(json.loads(raw))
        except Exception: pass
    def beat(self, ws):
        # The server closes a socket that stops answering ForceKeepAlive.
        try:
            while True:
                ws.send(json.dumps({'MessageType': 'KeepAlive'})); time.sleep(10)
        except Exception: pass
    def wait(self, kind, after=0, timeout=4):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for m in self.messages[after:]:
                if m.get('MessageType') == kind: return m
            time.sleep(.05)
    def close(self): self.ws.close()

def listed(api, active=60):
    query = {'controllableByUserId': api.user}
    if active: query['activeWithinSeconds'] = active
    code, rows = api.req('GET', '/Sessions', query=query)
    return code, rows or []
def find(rows, device): return next((r for r in rows if r.get('DeviceId') == device), None)
def login(version, user, base):
    api = API(os.environ['JF_URL'], f'{base}-{uuid.uuid4().hex[:8]}')
    api.login(vault(credential(version, user)))
    return api
def policy(admin, user, **changes):
    current = admin.ok('GET', '/Users/' + user)['Policy']
    admin.ok('POST', f'/Users/{user}/Policy', {**current, **changes})
    return {k: current.get(k) for k in changes}

parser = argparse.ArgumentParser(); parser.add_argument('--out', type=pathlib.Path, required=True); parser.add_argument('--idle', type=int, default=75)
args = parser.parse_args(); version = os.environ['JF_VERSION']
admin = login(version, 'scope-admin', 'remote-admin')
tv, phone, other, restricted = (login(version, u, b) for u, b in [('scope-viewer-a', 'remote-tv'), ('scope-viewer-a', 'remote-phone'), ('scope-viewer-b', 'remote-other'), ('scope-restricted', 'remote-restricted')])
saved = {}
sockets = []
apikey = None
try:
    saved = {u: policy(admin, u, EnableRemoteControlOfOtherUsers=False, EnableSharedDeviceControl=False) for u in [other.user, phone.user, restricted.user]}
    target = Target(tv); sockets.append(target)
    time.sleep(.3)
    forced = target.wait('ForceKeepAlive')
    check('socket-header-auth-keepalive', forced is not None, forced)
    code, rows = listed(phone); row = find(rows, tv.device)
    check('same-viewer-lists-target', code == 200 and row is not None and row.get('SupportsRemoteControl') is True, {k: row.get(k) for k in ['SupportsRemoteControl', 'SupportsMediaControl', 'PlayableMediaTypes', 'SupportedCommands', 'UserName']} if row else rows)
    check('controller-without-capabilities-not-listed', find(rows, phone.device) is None)
    session = row['Id']
    # Items for the wire and access checks.
    items = phone.ok('GET', f'/Users/{phone.user}/Items', query={'Recursive': 'true', 'IncludeItemTypes': 'Movie,Episode', 'Limit': 200})['Items']
    visible = {i['Id'] for i in restricted.ok('GET', f'/Users/{restricted.user}/Items', query={'Recursive': 'true', 'IncludeItemTypes': 'Movie,Episode', 'Limit': 200})['Items']}
    allowed = next(i for i in items if i['Id'] in visible)['Id']
    denied = next((i['Id'] for i in items if i['Id'] not in visible), None)
    check('fixture-has-inaccessible-title', denied is not None)
    # Wire shapes.
    mark = len(target.messages); status, _ = phone.req('POST', f'/Sessions/{session}/Playing', query={'playCommand': 'PlayNow', 'itemIds': allowed})
    play = target.wait('Play', mark); check('play-query-delivered', status == 204 and play is not None, {'status': status, 'event': play})
    check('play-message-id-and-controller', bool(play and play.get('MessageId')) and play['Data'].get('ControllingUserId', '').replace('-', '') == phone.user.replace('-', ''), play)
    mark = len(target.messages); status, _ = phone.req('POST', f'/Sessions/{session}/Playing', {'ItemIds': [allowed], 'PlayCommand': 'PlayNow'})
    check('play-json-body-not-the-contract', status >= 400 or target.wait('Play', mark, 1.5) is None, {'status': status})
    for command, query in [('Pause', None), ('Unpause', None), ('PlayPause', None), ('Seek', {'seekPositionTicks': 600000000}), ('NextTrack', None), ('PreviousTrack', None), ('Stop', None)]:
        mark = len(target.messages); status, _ = phone.req('POST', f'/Sessions/{session}/Playing/{command}', query=query)
        event = target.wait('Playstate', mark); check('playstate-' + command, status == 204 and event and event['Data'].get('Command') == command, {'status': status, 'event': event})
    for name, arguments in [('MoveUp', {}), ('SendString', {'String': 'space film'}), ('SetAudioStreamIndex', {'Index': '1'})]:
        mark = len(target.messages); status, _ = phone.req('POST', f'/Sessions/{session}/Command', {'Name': name, 'Arguments': arguments})
        event = target.wait('GeneralCommand', mark, 1.5); check('general-relay-' + name, status == 204, {'status': status, 'delivered': event, 'advertised': name in target.commands})
    mark = len(target.messages); status, _ = phone.req('POST', f'/Sessions/{session}/Playing', query={'playCommand': 'PlayNow', 'itemIds': allowed, 'startPositionTicks': 100000000})
    event = target.wait('Play', mark); check('play-start-position-relayed', event and event['Data'].get('StartPositionTicks') == 100000000, event)
    # Observed state: only the target's reports change it, and only the target gets a viewing.
    report = {'ItemId': allowed, 'PositionTicks': 50000000, 'IsPaused': True, 'PlayMethod': 'DirectPlay', 'CanSeek': True, 'PlaySessionId': uuid.uuid4().hex}
    tv.ok('POST', '/Sessions/Playing', report)
    _, rows = listed(phone); row = find(rows, tv.device)
    check('observed-state-from-target-report', row and row.get('NowPlayingItem', {}).get('Id') == allowed and row['PlayState'].get('IsPaused') is True and row['PlayState'].get('PositionTicks') == 50000000, row and row.get('PlayState'))
    _, all_rows = admin.req('GET', '/Sessions')
    check('controller-session-has-no-viewing', not (find(all_rows, phone.device) or {}).get('NowPlayingItem'), find(all_rows, phone.device) and {'NowPlaying': bool(find(all_rows, phone.device).get('NowPlayingItem'))})
    tv.ok('POST', '/Sessions/Playing/Stopped', {'ItemId': allowed, 'PositionTicks': 50000000, 'PlaySessionId': report['PlaySessionId']})
    _, rows = listed(phone); row = find(rows, tv.device)
    check('stop-clears-observed-state', row and not row.get('NowPlayingItem'))
    phone.req('POST', '/UserItems/%s/UserData' % allowed, {'PlaybackPositionTicks': 0}, query={'userId': phone.user})
    # Rights.
    _, rows = listed(other); check('other-viewer-not-listed-without-right', find(rows, tv.device) is None)
    mark = len(target.messages); status, _ = other.req('POST', f'/Sessions/{session}/Playing/Pause')
    event = target.wait('Playstate', mark, 1.5)
    checks.append({'name': 'other-viewer-send-without-right', 'passed': True, 'observed': {'status': status, 'delivered': event is not None, 'controllerIsOther': bool(event) and event['Data'].get('ControllingUserId', '').replace('-', '') == other.user.replace('-', '')}})
    print('MEASURE other-viewer-send-without-right', status, event is not None, flush=True)
    policy(admin, other.user, EnableRemoteControlOfOtherUsers=True)
    _, rows = listed(other); row = find(rows, tv.device); check('other-viewer-listed-with-right', row is not None, row and {'title': bool(row.get('NowPlayingItem')), 'userName': bool(row.get('UserName'))})
    mark = len(target.messages); status, _ = other.req('POST', f'/Sessions/{session}/Playing/Pause'); event = target.wait('Playstate', mark, 2)
    check('other-viewer-send-with-right-names-controller', status == 204 and event and event['Data'].get('ControllingUserId', '').replace('-', '') == other.user.replace('-', ''), {'status': status})
    policy(admin, other.user, EnableRemoteControlOfOtherUsers=False)
    _, rows = listed(other); check('revoked-right-removes-target', find(rows, tv.device) is None)
    # Inaccessible title: relay is not an access check; the target's own token is.
    restricted_target = Target(restricted); sockets.append(restricted_target); time.sleep(.3)
    admin_rows = admin.ok('GET', '/Sessions'); restricted_session = find(admin_rows, restricted.device)['Id']
    policy(admin, phone.user, EnableRemoteControlOfOtherUsers=True)
    mark = len(restricted_target.messages); status, _ = phone.req('POST', f'/Sessions/{restricted_session}/Playing', query={'playCommand': 'PlayNow', 'itemIds': denied})
    event = restricted_target.wait('Play', mark, 2)
    policy(admin, phone.user, EnableRemoteControlOfOtherUsers=False)
    own_status, _ = restricted.req('GET', f'/Users/{restricted.user}/Items/{denied}')
    check('inaccessible-title-refused-by-target-token', own_status in (401, 403, 404), {'relayStatus': status, 'relayed': event is not None, 'targetLookup': own_status})
    code, _ = restricted.req('POST', f'/Sessions/{session}/Playing', query={'playCommand': 'PlayNow', 'itemIds': denied})
    checks.append({'name': 'restricted-controller-play-denied-title', 'passed': True, 'observed': {'status': code}}); print('MEASURE restricted-controller-play-denied-title', code, flush=True)
    restricted_target.close()
    # Shared device: a session with no user, made with a dedicated API key.
    admin.ok('POST', '/Auth/Keys', query={'app': 'remote-contract'})
    apikey = next(k for k in admin.ok('GET', '/Auth/Keys')['Items'] if k.get('AppName') == 'remote-contract')['AccessToken']
    shared = API(os.environ['JF_URL'], f'remote-shared-{uuid.uuid4().hex[:8]}', apikey)
    shared_target = Target(shared); sockets.append(shared_target); time.sleep(.3)
    shared_row = find(admin.ok('GET', '/Sessions'), shared.device)
    _, rows = listed(phone); denied_list = find(rows, shared.device) is None
    policy(admin, phone.user, EnableSharedDeviceControl=True)
    _, rows = listed(phone); row = find(rows, shared.device)
    policy(admin, phone.user, EnableSharedDeviceControl=False)
    check('shared-device-right-distinct', denied_list and row is not None, {'sessionHasUser': bool((shared_row or {}).get('UserId') and set((shared_row or {}).get('UserId')) - {'0', '-'}), 'listedWithout': not denied_list, 'listedWith': row is not None})
    shared_target.close()
    # Idle: a socket-only target against activeWithinSeconds.
    time.sleep(args.idle)
    _, rows60 = listed(phone); _, rowsAll = listed(phone, None)
    checks.append({'name': 'idle-target-active-window', 'passed': True, 'observed': {'idleSeconds': args.idle, 'listedWith60': find(rows60, tv.device) is not None, 'listedWithoutWindow': find(rowsAll, tv.device) is not None}})
    print('MEASURE idle', args.idle, find(rows60, tv.device) is not None, find(rowsAll, tv.device) is not None, flush=True)
    # Restart.
    target.close(); time.sleep(1.5)
    _, rows = listed(phone, None); gone = find(rows, tv.device)
    status, _ = phone.req('POST', f'/Sessions/{session}/Playing/Pause')
    checks.append({'name': 'closed-socket', 'passed': True, 'observed': {'stillListed': gone is not None, 'sameId': bool(gone) and gone['Id'] == session, 'supportsRemote': bool(gone) and gone.get('SupportsRemoteControl'), 'sendStatus': status}})
    print('MEASURE closed', gone is not None, status, flush=True)
    target.messages.clear(); target.open(); time.sleep(.5)
    _, rows = listed(phone); new = find(rows, tv.device)
    queued = [m for m in target.messages if m.get('MessageType') in ('Play', 'Playstate', 'GeneralCommand')]
    check('reconnect-delivers-no-queued-input', not queued, {'queued': len(queued)})
    checks.append({'name': 'reconnect-session-id', 'passed': True, 'observed': {'listed': new is not None, 'changed': bool(new) and new['Id'] != session}})
    print('MEASURE reconnect listed', new is not None, 'changed', bool(new) and new['Id'] != session, flush=True)
    target.close()
    tv.ok('POST', '/Sessions/Logout'); tv.token = None
    time.sleep(.5); _, rows = listed(phone, None); check('sign-out-removes-target', find(rows, tv.device) is None)
finally:
    for s in sockets:
        try: s.close()
        except Exception: pass
    for user, values in saved.items():
        try: policy(admin, user, **values)
        except Exception: pass
    if apikey:
        try: admin.req('DELETE', '/Auth/Keys/' + apikey)
        except Exception: pass
    for client in [phone, other, restricted, tv, admin]:
        if client.token:
            try: client.req('POST', '/Sessions/Logout')
            except Exception: pass
result = {'version': version, 'checks': checks, 'passed': all(c['passed'] for c in checks)}
args.out.parent.mkdir(parents=True, exist_ok=True); args.out.write_text(json.dumps(result, indent=2) + '\n')
print('RESULT', result['passed'])
raise SystemExit(0 if result['passed'] else 1)
