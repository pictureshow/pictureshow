#!/usr/bin/env bash
# Check that a minified, non-debuggable Pictureshow APK carries a baseline profile and that an emulator compiles it.
# usage: tools/check-baseline-profile.sh <apk> <mapping.txt> <output dir>
# Needs one booted device on adb (API 34+, for `pm dump-profiles`). Installs the APK the way a sideload does
# (`adb install`, then androidx.profileinstaller writes the profile), compiles speed-profile, dumps the reference
# profile and maps it back through R8's mapping. Exits 1 when a required class has no profiled method.
set -euo pipefail
APK="${1:?apk}"; MAP="${2:?mapping.txt}"; OUT="${3:?output dir}"
PKG=app.pictureshow
mkdir -p "$OUT"

unzip -l "$APK" | grep -E 'assets/dexopt/baseline\.prof' | tee "$OUT/apk-profile-assets.txt"
grep -q 'assets/dexopt/baseline.prof$' "$OUT/apk-profile-assets.txt" || { echo "::error::APK has no assets/dexopt/baseline.prof"; exit 1; }

adb install -r "$APK"
# Same path as a sideloaded TV: profileinstaller copies the APK's profile into ART's current profile.
# Its receiver needs the DUMP permission, which the shell has; it answers with a result code (1 = written).
adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE "$PKG/androidx.profileinstaller.ProfileInstallReceiver" \
  | tee "$OUT/profileinstaller.txt"
adb shell cmd package compile -f -m speed-profile "$PKG" | tee "$OUT/compile.txt"
adb shell dumpsys package dexopt > "$OUT/dexopt-all.txt" 2>&1 || true
grep -A6 "\[$PKG\]" "$OUT/dexopt-all.txt" | tee "$OUT/dexopt.txt" || true
adb shell pm art dump "$PKG" > "$OUT/art-dump.txt" 2>&1 || true
adb shell pm dump-profiles --dump-classes-and-methods "$PKG" | tee "$OUT/dump-profiles.txt"
# The dump lands in profman's directory; the emulator's google_apis image allows root if the shell can't read it.
adb shell cat "/data/misc/profman/$PKG-primary.prof.txt" > "$OUT/reference-profile.txt" 2>/dev/null || true
if ! grep -q -- '->' "$OUT/reference-profile.txt"; then
  adb root >/dev/null 2>&1 || true; adb wait-for-device
  adb shell cat "/data/misc/profman/$PKG-primary.prof.txt" > "$OUT/reference-profile.txt"
fi

python3 - "$MAP" "$OUT/reference-profile.txt" "$OUT/summary.txt" <<'PY'
import re, sys
mapping, profile, summary = sys.argv[1:4]
required = [
    "app.pictureshow.PlaybackExitFocusDelegate",
    "androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat",
]
watched = required + [
    "app.pictureshow.MainActivity",
    "androidx.collection.Entries",
    "androidx.collection.Entries$iterator$1",
    "kotlin.coroutines.jvm.internal.RestrictedSuspendLambda",
]
names = {}
for line in open(mapping, encoding="utf-8"):
    m = re.match(r"^(\S+) -> (\S+):$", line)
    if m:
        names[m.group(1)] = m.group(2)
lines = [l.strip() for l in open(profile, encoding="utf-8") if l.strip()]
methods = [l for l in lines if "->" in l]
out = [f"reference profile: {len(lines)} rules, {len(methods)} methods"]
own = [l for l in methods if l.lstrip("HSP").startswith("Lapp/pictureshow/")]
out.append(f"methods in classes that keep an app/pictureshow name: {len(own)}")
missing = []
for cls in watched:
    obf = names.get(cls)
    if obf is None:
        out.append(f"{cls}: not in mapping (removed or merged by R8)")
        if cls in required:
            missing.append(cls)
        continue
    desc = "L" + obf.replace(".", "/") + ";"
    hits = [l for l in methods if l.lstrip("HSP").startswith(desc + "->")]
    out.append(f"{cls} -> {obf}: {len(hits)} profiled methods" + (f", e.g. {hits[0]}" if hits else ""))
    if cls in required and not hits:
        missing.append(cls)
text = "\n".join(out)
print(text)
open(summary, "w").write(text + "\n")
if missing:
    print("::error::no profiled methods for " + ", ".join(missing))
    sys.exit(1)
PY
