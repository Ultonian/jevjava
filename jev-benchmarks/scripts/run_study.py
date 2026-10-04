#!/usr/bin/env python3
"""Linux-only, sequential repeatability orchestration around the RunLoad CLI.

Supply an explicit plan with snapshots, runtimes, jars, hashes, affinity and run order.
No builds, JVM tuning, remote endpoints, or SDK changes are performed by this script.
"""
import argparse
import json
import os
import signal
import subprocess
import time
from pathlib import Path

from study_support import (
    host,
    interrupted,
    process_affinities,
    require_linux,
    save,
    sha,
    stamp,
    stop,
)


def run(plan_file, output):
    require_linux()
    plan = json.loads(plan_file.read_text())
    cpus = plan["cpus"]
    if not cpus or len(cpus) != len(set(cpus)) or not set(cpus) <= os.sched_getaffinity(0):
        raise ValueError("Invalid/unavailable affinity")
    if len({r['id'] for r in plan['runs']}) != len(plan['runs']):
        raise ValueError("Duplicate run IDs")
    for row in plan["runs"]:
        if not row["id"] or Path(row["id"]).name != row["id"] or row["id"] in (".", ".."):
            raise ValueError("Run ID must be a single filename")
        if sha(row["jar"]) != row["jarSha256"]:
            raise ValueError("Executable hash mismatch")
        dirty = subprocess.check_output(["git", "status", "--porcelain"], cwd=row["snapshot"])
        if dirty.strip():
            raise ValueError("Source snapshot must be clean")
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=row["snapshot"], text=True).strip()
        if commit != plan["sourceCommit"]:
            raise ValueError("Source snapshot commit mismatch")
    output.mkdir(parents=True, exist_ok=False)
    save(output / "plan.json", plan)
    state = {"status": "running", "startedAt": stamp(), "planSha256": sha(output / "plan.json"),
             "cpus": cpus, "completed": []}
    save(output / "study.json", state)
    env = {k: os.environ[k] for k in ("PATH", "SystemRoot", "WINDIR", "TEMP", "TMP", "TMPDIR")
           if k in os.environ}
    env["LANG"] = "C.UTF-8"
    process = None
    try:
        for row in plan["runs"]:
            run_output = output / row["id"]
            if sha(row["jar"]) != row["jarSha256"]:
                raise ValueError("Executable changed during study")
            command = [plan["taskset"], "-c", ",".join(map(str, cpus)), row["java"],
                       "-cp", row["jar"], "net.codefinch.jev.benchmarks.reporting.RunLoad",
                       str(run_output.resolve()), "baseline", "representative"]
            save(output / (row["id"] + "-launch.json"), {"command": command, "cwd": row["snapshot"],
                 "environmentPolicy": "OS/path/temp allowlist plus fixed locale; no JVM injection variables",
                 "hostBefore": host(cpus)})
            print(f"{stamp()} Starting {row['id']}", flush=True)
            began = time.monotonic()
            observed_pids = set()
            with (output / (row["id"] + "-launcher.log")).open("w") as console, \
                    (output / (row["id"] + "-host.jsonl")).open("w") as samples:
                process = subprocess.Popen(command, cwd=row["snapshot"], env=env,
                                           stdout=console, stderr=subprocess.STDOUT, start_new_session=True)
                while True:
                    try:
                        code = process.wait(timeout=10)
                        break
                    except subprocess.TimeoutExpired:
                        if time.monotonic() - began > 900:
                            raise TimeoutError("Study pass exceeded 900 seconds")
                        affinities = process_affinities(process.pid)
                        if any(mask != sorted(cpus) for mask in affinities.values()):
                            raise ValueError("Parent/child affinity mismatch")
                        observed_pids.update(affinities)
                        sample = host(cpus)
                        sample["processAffinities"] = affinities
                        samples.write(json.dumps(sample) + "\n")
                        samples.flush()
                if code != 0:
                    raise RuntimeError(f"{row['id']} failed with exit {code}")
            stop(process)
            process = None
            manifest = json.loads((run_output / "manifest.json").read_text())
            if (manifest["status"] != "complete" or manifest["gitDirty"] is not False
                    or manifest["gitCommit"] != plan["sourceCommit"]
                    or manifest["benchmarkJarSha256"] != row["jarSha256"]
                    or manifest["availableProcessors"] != len(cpus)):
                raise ValueError("Invalid completed run provenance")
            if len(observed_pids) < 10:
                raise ValueError("Did not observe parent and all nine fork JVMs")
            state["completed"].append({"id": row["id"], "seconds": time.monotonic() - began,
                                       "resultSha256": sha(run_output / "results.json"),
                                       "observedProcessIds": sorted(observed_pids),
                                       "hostAfter": host(cpus)})
            save(output / "study.json", state)
            print(f"{stamp()} Completed {row['id']}", flush=True)
        state["status"] = "complete"
    finally:
        stop(process)
        if state["status"] != "complete":
            state["status"] = "failed"
        state["finishedAt"] = stamp()
        save(output / "study.json", state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plan", type=Path, help="Predeclared JSON plan with source/runtime identities and CPU affinity")
    parser.add_argument("output", type=Path, help="New study output directory; must not exist")
    args = parser.parse_args()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, interrupted)
    run(args.plan, args.output)


if __name__ == "__main__":
    main()
