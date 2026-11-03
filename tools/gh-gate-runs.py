#!/usr/bin/env python3
"""Wait for every workflow run on one gated push and summarize its test artifacts."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path


REPOSITORY = "pictureshow/pictureshow"
RUN_FIELDS = "databaseId,workflowName,name,status,conclusion,url,createdAt,startedAt,updatedAt,headBranch,headSha"
TERMINAL = {"completed"}


def ghx(*args: str, capture: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["ghx", *args],
        check=False,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )


def fetch_runs(sha: str, branch: str) -> list[dict[str, object]]:
    result = ghx(
        "run",
        "list",
        "-R",
        REPOSITORY,
        "--commit",
        sha,
        "--branch",
        branch,
        "-L",
        "100",
        "--json",
        RUN_FIELDS,
    )
    if result.returncode:
        raise RuntimeError((result.stderr or "ghx run list failed").strip())
    runs = json.loads(result.stdout or "[]")
    return [
        run
        for run in runs
        if run.get("headSha") == sha and run.get("headBranch") == branch
    ]


def wait_for_runs(sha: str, branch: str) -> list[dict[str, object]]:
    runs: list[dict[str, object]] = []
    saw_run = False
    empty_polls = 0
    previous_ids: tuple[int, ...] | None = None
    stable_complete_polls = 0
    reported: set[int] = set()

    while True:
        runs = fetch_runs(sha, branch)
        runs.sort(key=lambda run: (str(run.get("workflowName") or run.get("name") or ""), int(run["databaseId"])))
        if not runs:
            if saw_run:
                previous_ids = None
                stable_complete_polls = 0
                time.sleep(10)
                continue
            empty_polls += 1
            if empty_polls >= 30:
                print(f"No workflow run appeared for {branch} @ {sha[:7]} after 5 minutes.", flush=True)
                return []
            time.sleep(10)
            continue

        saw_run = True
        empty_polls = 0
        ids = tuple(sorted(int(run["databaseId"]) for run in runs))
        for run in runs:
            run_id = int(run["databaseId"])
            if run_id not in reported:
                label = run.get("workflowName") or run.get("name") or "(unnamed workflow)"
                print(f"Found {label}: {run.get('url', '')} ({run.get('status', 'unknown')})", flush=True)
                reported.add(run_id)

        all_complete = all(run.get("status") in TERMINAL for run in runs)
        if all_complete and ids == previous_ids:
            stable_complete_polls += 1
        else:
            stable_complete_polls = 0
        if stable_complete_polls >= 2:
            return runs

        previous_ids = ids
        time.sleep(10)


def parse_time(value: object) -> dt.datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def wall_duration(run: dict[str, object]) -> str:
    start = parse_time(run.get("startedAt")) or parse_time(run.get("createdAt"))
    end = parse_time(run.get("updatedAt"))
    if start is None or end is None:
        return "unknown"
    seconds = max(0, int((end - start).total_seconds()))
    return f"{seconds // 60}m{seconds % 60:02d}s"


def read_counts(run_dir: Path) -> tuple[dict[str, int], str]:
    for path in sorted(run_dir.rglob("test-counts.json")):
        try:
            payload = json.loads(path.read_text())
        except (OSError, json.JSONDecodeError):
            continue
        if isinstance(payload, dict):
            counts = {
                "tests": int(payload.get("tests", 0)),
                "failures": int(payload.get("failures", 0)),
                "errors": int(payload.get("errors", 0)),
                "skipped": int(payload.get("skipped", 0)),
            }
            return counts, f"{path.relative_to(run_dir)}"

    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    files = 0
    for path in sorted(run_dir.rglob("*.xml")):
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            continue
        if not root.get("tests"):
            continue
        files += 1
        totals["tests"] += int(root.get("tests", 0))
        totals["failures"] += int(root.get("failures", 0))
        totals["errors"] += int(root.get("errors", 0))
        totals["skipped"] += int(root.get("skipped", 0))
    return totals, f"{files} JUnit XML file(s)" if files else "no test report artifact"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sha", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--output", default="build-out/gh-gate")
    args = parser.parse_args()

    try:
        runs = wait_for_runs(args.sha, args.branch)
    except (RuntimeError, json.JSONDecodeError) as error:
        print(f"Could not list workflow runs: {error}", file=sys.stderr)
        return 1
    if not runs:
        return 3

    output = Path(args.output)
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)

    any_failure = False
    saved_runs: list[dict[str, object]] = []
    for run in runs:
        run_id = int(run["databaseId"])
        run_dir = output / str(run_id)
        run_dir.mkdir(parents=True)
        download = ghx("run", "download", str(run_id), "-R", REPOSITORY, "-D", str(run_dir))
        if download.returncode:
            note = (download.stderr or "no artifacts available").strip().splitlines()
            print(f"Artifact download for run {run_id}: {note[-1] if note else 'unavailable'}")

        counts, source = read_counts(run_dir)
        conclusion = str(run.get("conclusion") or "unknown")
        workflow = str(run.get("workflowName") or run.get("name") or "(unnamed workflow)")
        print(
            f"{workflow}: conclusion={conclusion} wall={wall_duration(run)} "
            f"tests={counts['tests']} failures={counts['failures']} errors={counts['errors']} "
            f"skipped={counts['skipped']} counts_from={source}"
        )
        print(f"  run: {run.get('url', '')}")
        if conclusion not in {"success", "skipped"}:
            any_failure = True

        saved_runs.append(
            {
                "id": run_id,
                "workflow": workflow,
                "conclusion": conclusion,
                "wall": wall_duration(run),
                "url": run.get("url"),
                "counts": counts,
                "counts_from": source,
                "createdAt": run.get("createdAt"),
                "startedAt": run.get("startedAt"),
                "updatedAt": run.get("updatedAt"),
            }
        )

    (output / "runs.json").write_text(json.dumps(saved_runs, indent=2) + "\n")
    return 1 if any_failure else 0


if __name__ == "__main__":
    sys.exit(main())
