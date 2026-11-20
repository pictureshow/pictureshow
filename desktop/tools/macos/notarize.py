#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Notarize a Developer ID disk image using the broker-injected SECRET."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def main(dmg, evidence):
    try:
        entry = json.loads(os.environ.pop("SECRET"))
        key_id, key = entry["username"], entry["password"]
        issuer = re.search(r"\b[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}\b", entry["note"], re.IGNORECASE)[0]
    except (KeyError, TypeError, ValueError):
        raise SystemExit("Run through the App Store Connect secrets broker entry with an issuer UUID in its note.") from None
    with tempfile.TemporaryDirectory(prefix="pictureshow-notary-") as folder:
        keyfile = Path(folder) / "notary.p8"
        keyfile.touch(mode=0o600)
        keyfile.write_text(key)
        credentials = ["--key", str(keyfile), "--key-id", key_id, "--issuer", issuer]
        result = subprocess.run(
            ["xcrun", "notarytool", "submit", str(dmg), *credentials, "--wait", "--output-format", "json"],
            stdout=subprocess.PIPE, check=False,
        )
        (evidence / "notary-submission.json").write_bytes(result.stdout)
        submission = json.loads(result.stdout)
        if submission.get("id"):
            subprocess.run(
                ["xcrun", "notarytool", "log", submission["id"], *credentials, str(evidence / "notary-log.json")],
                check=True,
            )
        if result.returncode or submission.get("status") != "Accepted":
            raise SystemExit(f"Notarization did not accept the disk image. See {evidence / 'notary-submission.json'}.")
        print(f"Notarization accepted {submission['id']}")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
