# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Own a finite hosted-only virtual display around the disposable app smoke."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def smoke_origin(receipt):
    if (receipt.get("phase") != "virtual" or not receipt.get("online") or
            receipt.get("mirrored") or receipt.get("main") or
            receipt.get("width", 0) < 3840 or receipt.get("height", 0) < 2160):
        raise RuntimeError("Smoke needs its own online, unmirrored 3840x2160 virtual display")
    return int(receipt["x"]), int(receipt["y"])


def main():
    if sys.platform != "darwin" or os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("Virtual-display wrapper is only for an isolated macOS GitHub runner")
    evidence = Path("desktop-evidence/display")
    evidence.mkdir(parents=True, exist_ok=True)
    helper = Path("desktop/build/app-native/macos-smoke-display").resolve()
    subprocess.run(["clang", "-O2", "-fobjc-arc", "desktop/tools/hdr/macos_virtual.m",
                    "-framework", "Cocoa", "-o", str(helper)], check=True)
    with (evidence / "virtual.jsonl").open("w") as output, (evidence / "helper-errors.log").open("w") as errors:
        process = subprocess.Popen([str(helper), "--create", "0", "900", "3840", "2160"],
                                   stdout=output, stderr=errors)
        try:
            deadline = time.monotonic() + 20
            receipt = None
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f"Virtual display helper exited before readiness: {process.returncode}")
                for line in (evidence / "virtual.jsonl").read_text().splitlines():
                    try:
                        candidate = json.loads(line)
                    except json.JSONDecodeError:
                        continue  # The helper may still be writing this last line.
                    if candidate.get("phase") == "virtual":
                        receipt = candidate
                if receipt:
                    break
                time.sleep(.1)
            if not receipt:
                raise RuntimeError("Virtual display did not become ready within 20 seconds")
            x, y = smoke_origin(receipt)
            env = dict(os.environ, PICTURESHOW_SMOKE_WINDOW_X=str(x), PICTURESHOW_SMOKE_WINDOW_Y=str(y),
                       PICTURESHOW_SMOKE_4K="1")
            (evidence / "smoke-screen.json").write_text(json.dumps(receipt, indent=2) + "\n")
            subprocess.run([sys.executable, "desktop/tools/app-smoke/run.py", *sys.argv[1:]],
                           env=env, check=True, timeout=840)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            (evidence / "cleanup.json").write_text(json.dumps({"helperStopped": process.poll() is not None}) + "\n")


if __name__ == "__main__":
    main()
