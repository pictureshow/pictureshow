#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Add the signed provisioning profile's app and team IDs to Store entitlements."""
from datetime import datetime, timezone
import plistlib
from pathlib import Path
import subprocess
import sys


def main(profile_path, bundle_id, template, output):
    profile = plistlib.loads(subprocess.check_output(["security", "cms", "-D", "-i", profile_path]))
    if profile["ExpirationDate"].replace(tzinfo=timezone.utc) <= datetime.now(timezone.utc):
        raise SystemExit("The Mac App Store provisioning profile has expired.")
    profile_entitlements = profile["Entitlements"]
    teams = profile["TeamIdentifier"]
    app_id = profile_entitlements.get("com.apple.application-identifier")
    if not app_id or len(teams) != 1 or not app_id.endswith(f".{bundle_id}") or "OSX" not in profile.get("Platform", []):
        raise SystemExit("The provisioning profile must belong to this app on macOS.")
    if profile_entitlements.get("com.apple.security.get-task-allow") or profile_entitlements.get("get-task-allow") or "ProvisionedDevices" in profile:
        raise SystemExit("Use the Mac App Store distribution provisioning profile.")
    entitlements = plistlib.loads(Path(template).read_bytes())
    entitlements.update({"com.apple.application-identifier": app_id, "com.apple.developer.team-identifier": teams[0]})
    Path(output).write_bytes(plistlib.dumps(entitlements))


if __name__ == "__main__":
    main(*sys.argv[1:])
