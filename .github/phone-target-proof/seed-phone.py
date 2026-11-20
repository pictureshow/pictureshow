import importlib.util, json, pathlib, subprocess, uuid
root = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("desktop_auth", root / "desktop-auth.py")
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)
snapshot = json.loads((root / "desktop-data/accounts.json").read_text())
account = snapshot["accounts"][0]
url = account["server"]["url"]
approver = local.API(url, account["deviceId"], account["accessToken"])
assert approver.ok("GET", "/System/Info/Public")["ServerName"] == "Pictureshow Remote 172 Disposable"
def read(*args):
    return subprocess.run(["adb", "-s", "emulator-5554", "shell", *args], check=True, capture_output=True, text=True, timeout=15).stdout.strip()
device = read("settings", "get", "global", "device_name")
if not device or device == "null":
    device = read("getprop", "ro.product.model")
identifier = "ps172-test-phone-" + uuid.uuid4().hex
actor = local.NamedAPI(url, identifier, "Pictureshow", device)
auth = local.enroll(approver, actor)
assert auth["User"]["Id"] == account["user"]["id"]
account.update(deviceId=actor.device, accessToken=actor.token)
def varint(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result) + bytes([value])
def field(number, value):
    return varint((number << 3) | 2) + varint(len(value)) + value
def entry(key, value):
    return field(1, field(1, key.encode()) + field(2, field(5, value.encode())))
prefs = entry("device_id", identifier) + entry("accounts_v1", json.dumps(snapshot))
adb = ["adb", "-s", "emulator-5554"]
def call(*args, **options):
    return subprocess.run(adb + list(args), check=True, timeout=40, **options)
name = call("emu", "avd", "name", capture_output=True, text=True).stdout.splitlines()[0]
assert name == "ps172-remote-phone" or name.startswith("test"), "Use only the disposable phone AVD"
call("shell", "am", "force-stop", "app.pictureshow")
call("shell", "run-as", "app.pictureshow", "mkdir", "-p", "files/datastore")
call("exec-in", "run-as app.pictureshow sh -c 'cat > files/datastore/pictureshow.preferences_pb'", input=prefs)
call("shell", "am", "start", "-n", "app.pictureshow/.MainActivity")
(root / "phone-device-id").write_text(identifier)
(root / "phone-device-name").write_text(device)
print("Fixture viewer provisioned on disposable phone", flush=True)
