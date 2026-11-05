#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Downloads and offline receipts: validated range, finite conversion, sidecars, UserData and download-set tokens.
Isolated fixture only. Set JF_URL and JF_VERSION; credentials come from the vault. IDs, names and tokens are not recorded."""
import argparse, hashlib, json, os, pathlib, shutil, struct, subprocess, tempfile, time, urllib.error, urllib.parse, urllib.request, uuid
from api import API, vault, credential

parser = argparse.ArgumentParser(); parser.add_argument('--out', type=pathlib.Path, required=True); parser.add_argument('--skip-transcode', action='store_true')
args = parser.parse_args()
url, version = os.environ['JF_URL'], os.environ['JF_VERSION']
checks = []
def check(name, passed, observed=None):
    checks.append({'name': name, 'passed': bool(passed), 'observed': observed})
    print(('PASS ' if passed else 'FAIL ') + name + (f' {observed}' if observed is not None else ''), flush=True)

def client(user, device=None):
    api = API(url, device or f'offline-contract-{uuid.uuid4().hex[:8]}')
    api.login(json.loads(vault(credential(version, user))))
    return api

def boxes(path):
    out, at, size = [], 0, os.path.getsize(path)
    with open(path, 'rb') as f:
        while at + 8 <= size:
            f.seek(at); head = f.read(16)
            n = struct.unpack('>I', head[:4])[0]; kind = head[4:8].decode('latin1')
            if n == 1: n = struct.unpack('>Q', head[8:16])[0]
            if n == 0: n = size - at
            if n < 8: return out + [('bad', at)]
            out.append((kind, n)); at += n
    return out

def fetch(api, path, query=None, headers=None, dest=None, timeout=900):
    q = '?' + urllib.parse.urlencode(query) if query else ''
    h = {'Authorization': f'MediaBrowser Client="Pictureshow Fixture", Device="fixture", DeviceId="{api.device}", Version="1", Token="{api.token}"'}
    h.update(headers or {})
    r = urllib.request.Request(api.url + path + q, headers=h)
    try:
        response = urllib.request.urlopen(r, timeout=timeout)
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), 0
    total = 0
    with response:
        if dest:
            with open(dest, 'wb') as f: shutil.copyfileobj(response, f, 1024 * 1024)
            total = os.path.getsize(dest)
        else:
            total = len(response.read())
        return response.status, dict(response.headers), total

def probe(path):
    data = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'stream=codec_type,codec_name,height:stream_tags=language:format=duration', '-of', 'json', path], capture_output=True, text=True)
    return json.loads(data.stdout or '{}')

def decodes(path):
    run = subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-i', path, '-f', 'null', '-'], capture_output=True, text=True)
    return run.returncode == 0 and not run.stderr.strip()

def find(api, name):
    data = api.ok('GET', '/Items', query={'userId': api.user, 'recursive': 'true', 'searchTerm': name, 'fields': 'MediaSources,MediaStreams,CanDownload'})
    return next((i for i in data['Items'] if i['Name'] == name), None)

viewer = client('scope-viewer-a'); other = client('scope-viewer-b'); restricted = client('scope-restricted')
work = pathlib.Path(tempfile.mkdtemp(prefix='offline-contract-'))
try:
    item = find(viewer, 'Scope Offline'); convert = find(viewer, 'Scope Offline Convert')
    check('fixture-media-present', item is not None and convert is not None)
    source = item['MediaSources'][0]
    size = source['Size']
    check('item-reports-download-right', item.get('CanDownload') is True)
    check('source-reports-etag', bool(source.get('ETag')))

    # OFF-01/OFF-13: policy gates Original.
    code, _, _ = fetch(restricted, f"/Items/{item['Id']}/Download", headers={'Range': 'bytes=0-15'})
    check('restricted-viewer-denied-download', code in (401, 403, 404), code)
    me = restricted.ok('GET', '/Users/Me')
    check('restricted-policy-readable', me['Policy'].get('EnableContentDownloading') is False)

    # OFF-03d: exact range, resume, validators.
    whole = work / 'whole.mkv'
    code, h, got = fetch(viewer, f"/Items/{item['Id']}/Download", dest=whole)
    check('original-200-exact-length', code == 200 and got == size, {'code': code})
    check('original-advertises-ranges', h.get('Accept-Ranges') == 'bytes')
    check('original-has-a-validator', bool(h.get('Last-Modified') or h.get('ETag')), {'lastModified': bool(h.get('Last-Modified')), 'etag': bool(h.get('ETag'))})
    cut = size // 2
    code, h, got = fetch(viewer, f"/Items/{item['Id']}/Download", headers={'Range': f'bytes={cut}-'}, dest=work / 'tail')
    check('range-206-with-content-range', code == 206 and h.get('Content-Range') == f'bytes {cut}-{size-1}/{size}', code)
    with open(whole, 'rb') as a, open(work / 'tail', 'rb') as b:
        a.seek(cut); same = hashlib.sha256(a.read()).digest() == hashlib.sha256(b.read()).digest()
    check('resumed-tail-is-byte-identical', same)
    code, h, _ = fetch(viewer, f"/Items/{item['Id']}/Download", headers={'Range': f'bytes={size}-'})
    check('range-past-end-416-with-total', code == 416 and h.get('Content-Range') == f'bytes */{size}', code)
    code, _, _ = fetch(viewer, f"/Items/{item['Id']}/Download", headers={'Range': 'bytes=0-15', 'If-Range': '"not-the-validator"'})
    check('if-range-is-not-relied-on', code in (200, 206), {'status': code, 'note': 'client compares validators itself'})

    # OFF-05: captions saved separately, both external and embedded text.
    for stream in [s for s in source['MediaStreams'] if s['Type'] == 'Subtitle']:
        code, h, got = fetch(viewer, f"/Videos/{item['Id']}/{source['Id']}/Subtitles/{stream['Index']}/0/Stream.vtt", dest=work / f"sub{stream['Index']}.vtt")
        text = (work / f"sub{stream['Index']}.vtt").read_text(errors='replace')
        check(f"subtitle-{stream.get('Language')}-{'ext' if stream.get('IsExternal') else 'emb'}-vtt", code == 200 and text.startswith('WEBVTT') and '-->' in text, code)

    # OFF-02: every compatible preset is finite, independently playable and made of what the player can index.
    target = convert['MediaSources'][0]
    audios = [s for s in target['MediaStreams'] if s['Type'] == 'Audio']
    check('convert-fixture-has-two-languages', len(audios) >= 2 and audios[0].get('Language') != audios[1].get('Language'))
    results = {}
    presets = [] if args.skip_transcode else [('video720', 720, 4000000), ('video1080', 1080, 8000000)]
    for name, height, rate in presets:
        session = uuid.uuid4().hex
        query = {'mediaSourceId': target['Id'], 'playSessionId': session, 'deviceId': viewer.device, 'videoCodec': 'h264', 'audioCodec': 'aac', 'maxHeight': height,
                 'videoBitRate': rate, 'audioBitRate': 128000, 'maxAudioChannels': 2, 'audioStreamIndex': audios[0]['Index'], 'subtitleMethod': 'Drop', 'static': 'false',
                 'enableAutoStreamCopy': 'false', 'allowVideoStreamCopy': 'false', 'allowAudioStreamCopy': 'false', 'container': 'mp4'}
        dest = work / f'{name}.mp4'; started = time.time()
        code, h, got = fetch(viewer, f"/Videos/{convert['Id']}/stream.mp4", query, dest=dest)
        kinds = [b[0] for b in boxes(dest)] if code == 200 else []
        info = probe(dest) if code == 200 else {}
        duration = float(info.get('format', {}).get('duration') or 0)
        expected = target['RunTimeTicks'] / 1e7
        video = next((s for s in info.get('streams', []) if s['codec_type'] == 'video'), {})
        audio = next((s for s in info.get('streams', []) if s['codec_type'] == 'audio'), {})
        ok = (code == 200 and kinds[:2] == ['ftyp', 'moov'] and 'moof' in kinds and abs(duration - expected) < 3 and video.get('codec_name') == 'h264'
              and audio.get('codec_name') == 'aac' and decodes(str(dest)))
        check(f'{name}-finite-playable', ok, {'status': code, 'seconds': round(time.time() - started), 'bytes': got, 'duration': round(duration, 1), 'height': video.get('height'), 'head': kinds[:3], 'contentLength': h.get('Content-Length'), 'range': h.get('Accept-Ranges')})
        results[name] = ok
        code, _ = viewer.req('DELETE', '/Videos/ActiveEncodings', query={'deviceId': viewer.device, 'playSessionId': session})
        check(f'{name}-cleanup', code < 300, code)
    if presets:
        # Same request stopped midway: an interruption restarts from zero and cleanup frees the encoder.
        session = uuid.uuid4().hex
        query = {'mediaSourceId': target['Id'], 'playSessionId': session, 'deviceId': viewer.device, 'videoCodec': 'h264', 'audioCodec': 'aac', 'maxHeight': 720, 'videoBitRate': 4000000,
                 'audioBitRate': 128000, 'maxAudioChannels': 2, 'audioStreamIndex': audios[0]['Index'], 'subtitleMethod': 'Drop', 'static': 'false', 'enableAutoStreamCopy': 'false',
                 'allowVideoStreamCopy': 'false', 'allowAudioStreamCopy': 'false', 'container': 'mp4'}
        code, h, _ = fetch(viewer, f"/Videos/{convert['Id']}/stream.mp4", query, headers={'Range': 'bytes=1000-'}, dest=work / 'r.mp4')
        check('conversion-resume-is-not-offered', h.get('Accept-Ranges') in (None, 'none') or code == 200, {'status': code, 'range': h.get('Accept-Ranges')})
        viewer.req('DELETE', '/Videos/ActiveEncodings', query={'deviceId': viewer.device, 'playSessionId': session})
        # Second language as a sidecar.
        session = uuid.uuid4().hex
        query = {'mediaSourceId': target['Id'], 'playSessionId': session, 'deviceId': viewer.device, 'audioCodec': 'aac', 'audioBitRate': 128000, 'maxAudioChannels': 2,
                 'audioStreamIndex': audios[1]['Index'], 'static': 'false', 'enableAutoStreamCopy': 'false', 'allowAudioStreamCopy': 'false', 'container': 'm4a'}
        dest = work / 'side.m4a'
        code, h, got = fetch(viewer, f"/Audio/{convert['Id']}/stream.m4a", query, dest=dest)
        info = probe(dest) if code == 200 else {}
        duration = float(info.get('format', {}).get('duration') or 0)
        check('sidecar-audio-finite-playable', code == 200 and abs(duration - target['RunTimeTicks'] / 1e7) < 3 and decodes(str(dest)), {'status': code, 'bytes': got, 'duration': round(duration, 1), 'boxes': [b[0] for b in boxes(dest)][:4]})
        viewer.req('DELETE', '/Videos/ActiveEncodings', query={'deviceId': viewer.device, 'playSessionId': session})
        # A sidecar for an unconverted item: audio presets for music arrive in leg 88 but are qualified here.
        for name, rate in [('audio128', 128000), ('audio256', 256000)]:
            session = uuid.uuid4().hex
            query = {'mediaSourceId': source['Id'], 'playSessionId': session, 'deviceId': viewer.device, 'audioCodec': 'aac', 'audioBitRate': rate, 'maxAudioChannels': 2, 'static': 'false', 'enableAutoStreamCopy': 'false', 'allowAudioStreamCopy': 'false', 'container': 'm4a'}
            code, _, got = fetch(viewer, f"/Audio/{item['Id']}/stream.m4a", query, dest=work / f'{name}.m4a')
            check(f'{name}-finite-playable', code == 200 and decodes(str(work / f'{name}.m4a')), {'status': code, 'bytes': got})
            viewer.req('DELETE', '/Videos/ActiveEncodings', query={'deviceId': viewer.device, 'playSessionId': session})

    # OFF-07/OFF-08: the outbox's only write path, and the PlayCount promise.
    data = viewer.ok('GET', f"/UserItems/{item['Id']}/UserData", query={'userId': viewer.user})
    check('userdata-readable-at-versioned-route', 'PlaybackPositionTicks' in data)
    base = data.get('PlayCount', 0)
    target_ticks = 600000000
    code, _ = viewer.req('POST', f"/UserItems/{item['Id']}/UserData", {'PlaybackPositionTicks': target_ticks}, query={'userId': viewer.user})
    after = viewer.ok('GET', f"/UserItems/{item['Id']}/UserData", query={'userId': viewer.user})
    check('position-write-holds-exactly', code < 300 and after['PlaybackPositionTicks'] == target_ticks, code)
    check('position-write-no-playcount-rise', after.get('PlayCount', 0) == base, {'before': base, 'after': after.get('PlayCount', 0)})
    viewer.req('POST', f"/UserItems/{item['Id']}/UserData", {'PlaybackPositionTicks': target_ticks}, query={'userId': viewer.user})
    again = viewer.ok('GET', f"/UserItems/{item['Id']}/UserData", query={'userId': viewer.user})
    check('same-value-replay-no-playcount-rise', again.get('PlayCount', 0) == base)
    code, _ = viewer.req('POST', f"/UserItems/{item['Id']}/UserData", {'Played': True}, query={'userId': viewer.user})
    played = viewer.ok('GET', f"/UserItems/{item['Id']}/UserData", query={'userId': viewer.user})
    check('played-write-holds', code < 300 and played.get('Played') is True, {'playCount': played.get('PlayCount', 0)})
    viewer.req('POST', f"/UserItems/{item['Id']}/UserData", {'Played': True}, query={'userId': viewer.user})
    twice = viewer.ok('GET', f"/UserItems/{item['Id']}/UserData", query={'userId': viewer.user})
    # The reducer never sends a second write when current equals desired; this records what a repeat would cost.
    check('repeat-played-observed', True, {'firstPlayCount': played.get('PlayCount', 0), 'secondPlayCount': twice.get('PlayCount', 0)})
    code, _ = viewer.req('POST', f"/UserItems/{item['Id']}/UserData", {'Played': False, 'PlaybackPositionTicks': 0}, query={'userId': viewer.user})
    reset = viewer.ok('GET', f"/UserItems/{item['Id']}/UserData", query={'userId': viewer.user})
    check('explicit-unplayed-holds', reset.get('Played') is False and reset.get('PlaybackPositionTicks') == 0)
    code, _ = other.req('POST', f"/UserItems/{item['Id']}/UserData", {'PlaybackPositionTicks': 5}, query={'userId': viewer.user})
    check('other-viewer-cannot-write', code in (401, 403, 404), code)

    # OFF-10 and CTRL-11: a dedicated token per download set, revocable without touching the sign-in.
    enabled = API(url, 'offline-qc').ok('GET', '/QuickConnect/Enabled')
    check('quick-connect-state-readable', isinstance(enabled, bool), enabled)
    set_device = 'pictureshow-dl-' + uuid.uuid4().hex
    anon = API(url, set_device)
    start = anon.ok('POST', '/QuickConnect/Initiate')
    code, _ = viewer.req('POST', '/QuickConnect/Authorize', query={'code': start['Code']})
    check('viewer-session-authorizes-own-code', code < 300, code)
    exchange = anon.ok('POST', '/Users/AuthenticateWithQuickConnect', {'Secret': start['Secret']})
    token = exchange['AccessToken']
    check('issued-token-is-same-viewer', exchange['User']['Id'].replace('-', '') == viewer.user.replace('-', ''))
    check('issued-token-is-not-the-sign-in', token != viewer.token)
    dl = API(url, set_device, token)
    code, _, _ = fetch(dl, f"/Items/{item['Id']}/Download", headers={'Range': 'bytes=0-15'})
    check('set-token-downloads', code == 206, code)
    code, _ = viewer.req('GET', '/Users/Me')
    check('sign-in-survives-issue', code == 200, code)
    code, _ = dl.req('POST', '/Sessions/Logout')
    check('set-logout-accepted', code < 300, code)
    code, _, _ = fetch(dl, f"/Items/{item['Id']}/Download", headers={'Range': 'bytes=0-15'})
    check('revoked-set-token-denied', code == 401, code)
    code, _ = viewer.req('GET', '/Users/Me')
    check('sign-in-survives-revocation', code == 200, code)
    # Reissue on the same device id: the replaced token dies, there's only ever one per set.
    anon = API(url, set_device); start = anon.ok('POST', '/QuickConnect/Initiate'); viewer.req('POST', '/QuickConnect/Authorize', query={'code': start['Code']})
    first = anon.ok('POST', '/Users/AuthenticateWithQuickConnect', {'Secret': start['Secret']})['AccessToken']
    start = anon.ok('POST', '/QuickConnect/Initiate'); viewer.req('POST', '/QuickConnect/Authorize', query={'code': start['Code']})
    second = anon.ok('POST', '/Users/AuthenticateWithQuickConnect', {'Secret': start['Secret']})['AccessToken']
    code, _ = API(url, set_device, first).req('GET', '/Users/Me')
    check('reissue-replaces-prior-token', code == 401, code)
    API(url, set_device, second).req('POST', '/Sessions/Logout')
    code, _ = viewer.req('POST', '/QuickConnect/Authorize', query={'code': 'NOTACODE'})
    check('bad-code-refused', code >= 400, code)
    # A viewer without the right is denied even with their own set token.
    anon = API(url, set_device); start = anon.ok('POST', '/QuickConnect/Initiate'); restricted.req('POST', '/QuickConnect/Authorize', query={'code': start['Code']})
    rest = anon.req('POST', '/Users/AuthenticateWithQuickConnect', {'Secret': start['Secret']})
    if rest[0] < 300:
        rdl = API(url, set_device, rest[1]['AccessToken']); code, _, _ = fetch(rdl, f"/Items/{item['Id']}/Download", headers={'Range': 'bytes=0-15'})
        check('restricted-set-token-denied', code in (401, 403, 404), code); rdl.req('POST', '/Sessions/Logout')
    else:
        check('restricted-set-token-denied', True, {'issue': rest[0]})
    # Season expansion for OFF-01.
    series = find(viewer, 'Scope Serial')
    if series:
        seasons = viewer.ok('GET', f"/Shows/{series['Id']}/Seasons", query={'userId': viewer.user})['Items']
        episodes = viewer.ok('GET', f"/Shows/{series['Id']}/Episodes", query={'userId': viewer.user, 'seasonId': seasons[0]['Id'], 'fields': 'MediaSources,MediaStreams,CanDownload'})['Items']
        check('season-expands-with-sources', len(episodes) == 2 and all(e.get('MediaSources') and e.get('CanDownload') is True for e in episodes), len(episodes))
    else:
        check('season-expands-with-sources', False, 'Scope Serial not found')
finally:
    shutil.rmtree(work, ignore_errors=True)
    for api in [viewer, other, restricted]:
        try: api.req('POST', '/Sessions/Logout')
        except Exception: pass
result = {'version': version, 'checks': checks, 'passed': all(c['passed'] for c in checks)}
args.out.parent.mkdir(parents=True, exist_ok=True); args.out.write_text(json.dumps(result, indent=2) + '\n')
print('RESULT', result['passed'])
raise SystemExit(0 if result['passed'] else 1)
