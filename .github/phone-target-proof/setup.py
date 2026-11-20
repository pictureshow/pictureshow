import json, os, pathlib, secrets, sys, time, uuid
sys.path.insert(0, str(pathlib.Path.cwd() / "tools/launch-fixtures"))
from api import API

root = pathlib.Path("build-out/phone-fixture")
url = "http://127.0.0.1:18097"
fixture = "Pictureshow Remote 172 Disposable"
password = secrets.token_urlsafe(24)
admin = API(url, "fixture-admin")
admin.ok("POST", "/Startup/Configuration", {"UICulture": "en-US", "MetadataCountryCode": "US", "PreferredMetadataLanguage": "en"})
admin.ok("GET", "/Startup/User")
admin_password = secrets.token_urlsafe(24)
admin.ok("POST", "/Startup/User", {"Name": "fixture-admin", "Password": admin_password})
# The local startup user needs its generated password for authenticated fixture setup.
admin.ok("POST", "/Startup/RemoteAccess", {"EnableRemoteAccess": True, "EnableAutomaticPortMapping": False})
admin.ok("POST", "/Startup/Complete")
admin.login({"username": "fixture-admin", "password": admin_password})
settings = admin.ok("GET", "/System/Configuration")
settings.update({"ServerName": fixture, "QuickConnectAvailable": True, "EnableUPnP": False})
admin.ok("POST", "/System/Configuration", settings)
admin.ok("POST", "/Library/VirtualFolders", {"LibraryOptions": {
    "EnableRealtimeMonitor": False, "EnableInternetProviders": False,
    "PathInfos": [{"Path": "/media"}],
    "TypeOptions": [{"Type": "Movie", "MetadataFetchers": [], "ImageFetchers": []}],
}}, {"name": "Remote Test", "collectionType": "movies", "refreshLibrary": "true"})
viewer = admin.ok("POST", "/Users/New", {"Name": "remote-pilot", "Password": password})
policy = viewer["Policy"]
policy.update({"IsAdministrator": False, "SyncPlayAccess": "CreateAndJoinGroups"})
admin.ok("POST", f"/Users/{viewer['Id']}/Policy", policy)
admin.ok("POST", "/Sessions/Logout")
desktop = API(url, "pictureshow-172-remote-desktop-" + uuid.uuid4().hex)
desktop.login({"username": "remote-pilot", "password": password})
info = desktop.ok("GET", "/System/Info/Public")
datadir = root / "desktop-data"
datadir.mkdir(exist_ok=True)
os.chmod(datadir, 0o700)
server = {"id": info["Id"], "name": info["ServerName"], "url": url, "version": info["Version"]}
account = {"server": server, "user": {"id": desktop.user, "name": "remote-pilot"}, "accessToken": desktop.token, "deviceId": desktop.device}
(datadir / "device-id").write_text(desktop.device)
(datadir / "accounts.json").write_text(json.dumps({"accounts": [account], "currentKey": info["Id"] + ":" + desktop.user, "lastServer": server}))
os.chmod(datadir / "accounts.json", 0o600)
for attempt in range(60):
    rows = desktop.ok("GET", f"/Users/{desktop.user}/Items", query={"Recursive": "true", "IncludeItemTypes": "Movie", "Fields": "RunTimeTicks"})["Items"]
    if rows and rows[0].get("RunTimeTicks", 0) > 1_500_000_000:
        print(json.dumps({"version": info["Version"], "server": info["ServerName"], "title": rows[0]["Name"], "runtimeTicks": rows[0]["RunTimeTicks"]}))
        (root / "item-id").write_text(rows[0]["Id"])
        break
    time.sleep(1)
else:
    raise SystemExit("Disposable fixture has no playable video")
# Random fixture-only account; supply only to a named test process, never write its password.
