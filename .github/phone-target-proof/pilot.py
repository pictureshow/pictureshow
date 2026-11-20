import argparse
import json
import os
import pathlib
import runpy
import sys
import time
import uuid

root = pathlib.Path(__file__).resolve().parent
tools = root.parent.parent / "tools/launch-fixtures"
sys.path.insert(0, str(tools))
from api import API

account = json.loads((root / "desktop-data/accounts.json").read_text())["accounts"][0]
url, name = account["server"]["url"], account["server"]["name"]
desktop = API(url, account["deviceId"], account["accessToken"])
desktop.user = account["user"]["id"]
assert name == "Pictureshow Remote 172 Disposable"
assert desktop.ok("GET", "/System/Info/Public")["ServerName"] == name

p = argparse.ArgumentParser()
p.add_argument("action", choices=["approve", "sessions", "drive", "play", "pause", "seek", "stop"])
p.add_argument("value", nargs="?")
args = p.parse_args()
if args.action == "approve":
    assert desktop.ok("POST", "/QuickConnect/Authorize", query={"Code": args.value})
    print("Quick Connect approved for fixture viewer")
    raise SystemExit()

def rows():
    return desktop.ok("GET", "/Sessions", query={"controllableByUserId": desktop.user})

item = (root / "item-id").read_text().strip()
if args.action == "sessions":
    for s in rows():
        now = s.get("NowPlayingItem") or {}
        state = s.get("PlayState") or {}
        print(json.dumps({
            "client": s.get("Client"),
            "name": s.get("DeviceName"),
            "fixtureViewer": s.get("UserId") == desktop.user,
            "playing": now.get("Name"),
            "position": state.get("PositionTicks", 0) / 10_000_000,
            "paused": state.get("IsPaused"),
            "canSeek": state.get("CanSeek"),
            "remote": s.get("SupportsRemoteControl"),
            "commands": len(s.get("SupportedCommands") or []),
        }))
    raise SystemExit()

targets = [s for s in rows() if s.get("UserId") == desktop.user and s.get("SupportsRemoteControl")
           and s.get("DeviceId") != desktop.device and s.get("DeviceName") == args.value]
assert len(targets) == 1, "Specify the exact owned target's unique fixture device name"
target = targets[0]
if args.action == "drive":
    caller = API(url, "phone-target-probe-" + uuid.uuid4().hex)
    qc = caller.ok("POST", "/QuickConnect/Initiate")
    desktop.ok("POST", "/QuickConnect/Authorize", query={"Code": qc["Code"]})
    auth = caller.ok("POST", "/Users/AuthenticateWithQuickConnect", body={"Secret": qc["Secret"]})
    os.environ["SECRET"] = json.dumps({"device": caller.device, "token": auth["AccessToken"]})
    sys.argv = ["phone-target.py", "--url", url, "--server-name", name, "--device", target["DeviceId"], "--item", item]
    runpy.run_path(str(tools / "phone-target.py"), run_name="__main__")
    raise SystemExit()

path = f"/Sessions/{target['Id']}/Playing"
if args.action == "play":
    desktop.ok("POST", path, query={"PlayCommand": "PlayNow", "ItemIds": item})
elif args.action == "seek":
    desktop.ok("POST", path + "/Seek", query={"SeekPositionTicks": 450_000_000})
else:
    desktop.ok("POST", path + "/" + args.action.capitalize())
print(f"{args.action} request accepted by fixture server")
