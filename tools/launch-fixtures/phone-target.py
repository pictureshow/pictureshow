#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Drive a signed-in phone or TV on a disposable Jellyfin fixture and verify its session reports."""

import argparse
import json
import os
import time
import urllib.parse
import uuid

from api import API


def wait_for(description, read, matches, timeout=20):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = read()
        if matches(value):
            print(f"PASS {description}", flush=True)
            return value
        time.sleep(0.5)
    raise AssertionError(f"Timed out waiting for {description}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="Loopback URL of the disposable Jellyfin fixture")
    parser.add_argument("--server-name", required=True, help="Exact disposable fixture name to verify before commands")
    parser.add_argument("--device", required=True, help="Exact DeviceId of the test target on the fixture")
    parser.add_argument("--item", required=True, help="ID of a seekable fixture video at least 60 seconds long")
    args = parser.parse_args()
    origin = urllib.parse.urlsplit(args.url)
    if origin.hostname not in {"localhost", "127.0.0.1", "::1"}:
        parser.error("use a loopback fixture URL")
    api = API(args.url, "phone-target-test-" + uuid.uuid4().hex)
    if api.ok("GET", "/System/Info/Public")["ServerName"] != args.server_name:
        parser.error("fixture server name did not match")
    try:
        credential = json.loads(os.environ["SECRET"])
        if "token" in credential:
            api.device = credential["device"]
            api.token = credential["token"]
            api.user = api.ok("GET", "/Users/Me")["Id"]
        else:
            api.login({"username": credential["username"], "password": credential["password"]})
    except (KeyError, ValueError):
        parser.error("inject a fixture username/password or issued device/token JSON in SECRET")

    def row():
        sessions = api.ok("GET", "/Sessions", query={"controllableByUserId": api.user})
        return next((s for s in sessions if s["DeviceId"] == args.device and s.get("UserId") == api.user), {})

    def item(session):
        return (session.get("NowPlayingItem") or {}).get("Id")

    def state(session):
        return session.get("PlayState") or {}

    dispatch = None
    try:
        target = wait_for(
            "test device is ready and advertised",
            row,
            lambda session: session.get("SupportsRemoteControl") and "Video" in session.get("PlayableMediaTypes", []),
        )
        if item(target):
            raise AssertionError("Start the fixture target idle before sending PlayNow")
        dispatch = f"/Sessions/{target['Id']}/Playing"
        api.ok("POST", dispatch, query={"playCommand": "PlayNow", "itemIds": args.item, "startPositionTicks": 120_000_000})
        wait_for(
            "PlayNow opened the requested title from the requested position",
            row,
            lambda s: item(s) == args.item and state(s).get("CanSeek") and state(s).get("PositionTicks", 0) >= 110_000_000
                and not state(s).get("IsPaused"),
        )
        api.ok("POST", f"{dispatch}/Pause")
        wait_for("Pause reached the same viewing", row, lambda s: item(s) == args.item and state(s).get("IsPaused"))
        api.ok("POST", f"{dispatch}/Seek", query={"seekPositionTicks": 450_000_000})
        wait_for(
            "Seek moved the paused viewing to 45 seconds",
            row,
            lambda s: item(s) == args.item and state(s).get("IsPaused") and abs(state(s).get("PositionTicks", 0) - 450_000_000) <= 10_000_000,
        )
        api.ok("POST", f"{dispatch}/Stop")
        wait_for("Stop cleared the target viewing", row, lambda s: bool(s) and item(s) is None)
    finally:
        if dispatch is not None and item(row()) == args.item:
            api.ok("POST", f"{dispatch}/Stop")
        api.ok("POST", "/Sessions/Logout")


if __name__ == "__main__":
    main()
