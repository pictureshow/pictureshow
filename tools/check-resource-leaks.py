#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Collect a debug VM leak checkpoint, or prove the detector with a real leaked file."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", default="adb", help="adb executable")
parser.add_argument("--serial", required=True, help="owned device serial")
parser.add_argument("--output", type=Path, required=True, help="private evidence directory")
parser.add_argument("--self-test", action="store_true", help="requires a fresh debug process; deliberately leaks one file")
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)


def adb(*command):
    try:
        process = subprocess.run(
            [args.adb, "-s", args.serial, *command], capture_output=True, text=True, timeout=30
        )
    except FileNotFoundError:
        raise SystemExit("adb was not found; provide its executable with --adb") from None
    except subprocess.TimeoutExpired:
        raise SystemExit("adb timed out; check that the owned device is responsive before repeating this checkpoint") from None
    if process.returncode:
        raise SystemExit("adb failed; check the serial, connection and that a debuggable app is running: " + process.stderr.strip())
    return process.stdout


def checkpoint(method):
    result = adb("shell", "content", "call", "--uri", "content://app.pictureshow.resourceleaks", "--method", method)
    match = re.search(r"violations=(\d+)", result)
    if not match:
        raise SystemExit("No leak counter returned. Use adb shell with DUMP and a running debug APK; "
                         "release/deviceTest exclude this provider. Provider output: " + result.strip())
    return int(match.group(1))


sdk = adb("shell", "getprop", "ro.build.version.sdk").strip()
if not sdk.isdecimal() or int(sdk) < 28:
    raise SystemExit("Counted checkpoints require API 28+; on older debug devices inspect StrictMode logcat directly.")

if args.self_test:
    before = checkpoint("collect")
    if before:
        raise SystemExit("Self-test needs a fresh debug process with zero prior violations; existing leaks must be investigated first.")
    closed = checkpoint("closed-canary")
    leaked = checkpoint("self-test")
    deadline = time.monotonic() + 5
    while leaked == 0 and time.monotonic() < deadline:
        time.sleep(0.25)
        leaked = checkpoint("collect")
    result = {"closed_file_violations": closed, "unclosed_file_violations": leaked}
    passed = closed == 0 and leaked >= 1
else:
    count = checkpoint("collect")
    # Allow in-flight finalizer callbacks; delayed OkHttp pool reports need a later idle checkpoint.
    time.sleep(1)
    count = checkpoint("counts")
    result = {"violations": count}
    passed = count == 0

pid = adb("shell", "pidof", "app.pictureshow").strip()
if not pid.isdecimal():
    raise SystemExit("Expected exactly one running app process")
result["pid"] = int(pid)
result["host_time_epoch"] = time.time()
meminfo = adb("shell", "dumpsys", "meminfo", pid)
status = adb("shell", "run-as", "app.pictureshow", "cat", f"/proc/{pid}/status")
(args.output / "meminfo.txt").write_text(meminfo)
(args.output / "status.txt").write_text(status)
pss = re.search(r"TOTAL PSS:\s+(\d+)", meminfo)
heap = re.search(r"^\s+Native Heap\s+([\d ]+)$", meminfo, re.MULTILINE)
threads = re.search(r"^Threads:\s+(\d+)", status, re.MULTILINE)
if pss:
    result["pss_kb"] = int(pss.group(1))
result["native_heap_alloc_kb"] = None
if heap and len(heap.group(1).split()) == 8:
    result["native_heap_alloc_kb"] = int(heap.group(1).split()[6])
else:
    result["native_heap_parse_note"] = "Unrecognized meminfo layout; inspect meminfo.txt"
if threads:
    result["threads"] = int(threads.group(1))
fds = adb("shell", "run-as", "app.pictureshow", "ls", f"/proc/{pid}/fd").split()
result["file_descriptors"] = len(fds)
(args.output / "fds.txt").write_text("\n".join(fds) + "\n")
# Counts alone cannot distinguish idle pools from unreleased players or growing native handles.
# A task can exit during enumeration; skip that task rather than losing the whole checkpoint.
thread_script = (
    f'for path in /proc/{pid}/task/*/comm; do '
    'if IFS= read -r name < "$path"; then '
    'task=${path%/comm}; printf "%s %s\\n" "${task##*/}" "$name"; fi; done 2>/dev/null'
)
(args.output / "threads.txt").write_text(adb("shell", "run-as app.pictureshow sh -c " + shlex.quote(thread_script)))
(args.output / "fd-targets.txt").write_text(adb("shell", "run-as", "app.pictureshow", "ls", "-l", f"/proc/{pid}/fd"))
try:
    (args.output / "java-threads.txt").write_text(
        adb("shell", "content", "call", "--uri", "content://app.pictureshow.resourceleaks", "--method", "threads")
    )
except SystemExit as error:
    # The counted checkpoint and native task/FD samples remain useful if the optional
    # Java dump exceeds Binder limits or is unavailable in an older diagnostic APK.
    result["java_threads_note"] = str(error)
logcat = adb("logcat", "-d", "--pid=" + pid, "-t", "5000", "-v", "threadtime")
result["retained_log_violation_lines"] = len(re.findall(r"PictureshowLeaks.*VM leak violation #", logcat))
(args.output / "logcat.txt").write_text(logcat)
(args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result))
raise SystemExit(0 if passed else 1)
