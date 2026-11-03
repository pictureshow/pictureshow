# SPDX-License-Identifier: MPL-2.0
"""Small HTTP client. Errors never include bodies, headers or credential URLs."""
import gzip, json, os, subprocess, urllib.request, urllib.parse, urllib.error
def request_url(base,path):
    """Resolve returned media URLs without forwarding fixture auth off-origin."""
    parsed=urllib.parse.urlsplit(path)
    if parsed.scheme or parsed.netloc:
        origin=urllib.parse.urlsplit(base)
        def identity(value):return (value.scheme.lower(),value.hostname,value.port or (443 if value.scheme.lower()=='https' else 80))
        if parsed.username or parsed.password or identity(parsed)!=identity(origin):raise RuntimeError('returned media URL has a different origin')
        return path
    return base.rstrip('/')+'/'+path.lstrip('/')

class API:
    def __init__(self,url,device,token=None): self.url=url.rstrip('/'); self.device=device; self.token=token; self.user=None
    def req(self,method,path,body=None,query=None,raw=False,headers=None):
        url=request_url(self.url,path)+('?' + urllib.parse.urlencode(query,doseq=True) if query else '')
        h={'Accept':'application/json; profile="PascalCase"','Authorization':f'MediaBrowser Client="Pictureshow Fixture", Device="Contract", DeviceId="{self.device}", Version="1"'+(f', Token="{self.token}"' if self.token else '')}
        if body is not None: h['Content-Type']='application/json'
        if path.startswith('/api-docs/'): h['Accept-Encoding']='gzip'
        h.update(headers or {})
        r=urllib.request.Request(url,data=json.dumps(body).encode() if body is not None else (b'' if method=='POST' else None),headers=h,method=method)
        try:
            with urllib.request.urlopen(r,timeout=180 if path.startswith('/api-docs/') else 45) as response: code=response.status; data=response.read(); rh=dict(response.headers)
        except urllib.error.HTTPError as e: code=e.code; data=e.read(); rh=dict(e.headers)
        except Exception as error: raise RuntimeError(f'{method} {path.split("?")[0]} transport failure ({type(error).__name__})') from None
        if rh.get('Content-Encoding','').lower()=='gzip': data=gzip.decompress(data)
        if raw: return code,data,rh
        try: data=json.loads(data) if data else None
        except (ValueError,UnicodeDecodeError): data=None
        return code,data
    def ok(self,method,path,body=None,query=None):
        code,data=self.req(method,path,body,query)
        if code>=300: raise RuntimeError(f'{method} {path} HTTP {code}')
        return data
    def login(self,cred):
        data=self.ok('POST','/Users/AuthenticateByName',{'Username':cred['username'],'Pw':cred['password']})
        self.token=data['AccessToken']; self.user=data['User']['Id']; return data

def vault(name):
    result=subprocess.run(['secret','get',name],capture_output=True,text=True,check=True)
    return json.loads(result.stdout)
def put(name,value):
    subprocess.run(['secret','put',name,'--tier','t2'],input=json.dumps(value),text=True,stdout=subprocess.DEVNULL,check=True)
def credential(version,user): return f'Pictureshow fixture Jellyfin {version} ({user})'

def close_result(status):
    """404 is already closed; other errors stay visible cleanup failures."""
    if status in (200,204): return 'closed'
    if status==404: return 'already-closed'
    return 'cleanup-failed'
