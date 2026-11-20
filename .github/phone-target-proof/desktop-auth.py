import json, os, pathlib, sys, urllib.parse, uuid
root = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(root.parent.parent / "tools/launch-fixtures"))
from api import API
class NamedAPI(API):
    def __init__(self, url, device, client, name, token=None):
        super().__init__(url, device, token)
        self.client, self.name = client, name
    def headers(self):
        values = {"Client": self.client, "Device": self.name, "DeviceId": self.device, "Version": "0.1.0"}
        if self.token:
            values["Token"] = self.token
        return {"Authorization":"MediaBrowser " + ", ".join(f'{k}="{urllib.parse.quote(v, safe="")}"' for k,v in values.items())}
    def req(self, method, path, body=None, query=None, raw=False, headers=None):
        return super().req(method, path, body, query, raw, self.headers() | (headers or {}))
def enroll(approver, actor):
    qc = actor.ok("POST", "/QuickConnect/Initiate")
    assert approver.ok("POST", "/QuickConnect/Authorize", query={"Code": qc["Code"]})
    auth = actor.ok("POST", "/Users/AuthenticateWithQuickConnect", {"Secret": qc["Secret"]})
    actor.token = auth["AccessToken"]
    return auth
if __name__ == "__main__":
    config = root / "desktop-data/accounts.json"
    payload = json.loads(config.read_text())
    account = payload["accounts"][0]
    url = account["server"]["url"]
    approver = API(url, account["deviceId"], account["accessToken"])
    assert approver.ok("GET", "/System/Info/Public")["ServerName"] == "Pictureshow Remote 172 Disposable"
    actor = NamedAPI(url, "ps172-desktop-native-" + uuid.uuid4().hex, "Pictureshow", "Pictureshow Desktop")
    auth = enroll(approver, actor)
    assert auth["User"]["Id"] == account["user"]["id"]
    account.update(deviceId=actor.device, accessToken=actor.token)
    tmp = config.with_suffix(".tmp")
    with os.fdopen(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as out:
        json.dump(payload, out)
    tmp.replace(config)
    print("Desktop signed in with native client identity")
