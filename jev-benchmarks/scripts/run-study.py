#!/usr/bin/env python3
"""Linux-only, sequential repeatability orchestration around the RunLoad CLI.

Supply an explicit plan with snapshots, runtimes, jars, hashes, affinity and run order.
No builds, JVM tuning, remote endpoints, or SDK changes are performed by this script.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time
from datetime import datetime, timezone


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def stamp():
    return datetime.now(timezone.utc).isoformat()


def read_optional(path):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return "unavailable"


def host(cpus):
    policies = {}
    for cpu in cpus:
        root = Path(f"/sys/devices/system/cpu/cpu{cpu}/cpufreq")
        policies[str(cpu)] = {name: read_optional(root / name) for name in (
            "scaling_driver", "scaling_governor", "scaling_min_freq", "scaling_max_freq",
            "scaling_cur_freq", "energy_performance_preference")}
    counters = {}
    for line in Path("/proc/stat").read_text().splitlines():
        fields = line.split()
        if fields and fields[0].startswith("cpu"):
            # Exclude guest columns, which are already included in user/nice.
            counters[fields[0]] = [int(x) for x in fields[1:9]]
    return {"at": stamp(), "loadAverage": os.getloadavg(), "cpuCounters": counters,
            "frequencyPolicies": policies,
            "platformProfile": read_optional("/sys/firmware/acpi/platform_profile"),
            "boost": read_optional("/sys/devices/system/cpu/cpufreq/boost"),
            "pstateStatus": read_optional("/sys/devices/system/cpu/amd_pstate/status"),
            "thermalMilliC": {str(p.parent.name): read_optional(p)
                              for p in Path("/sys/class/thermal").glob("thermal_zone*/temp")},
            "powerOnline": {p.parent.name: read_optional(p)
                            for p in Path("/sys/class/power_supply").glob("*/online")}}


def process_affinities(parent):
    pending, seen, observed = [parent], set(), {}
    while pending:
        pid = pending.pop()
        if pid in seen:
            continue
        seen.add(pid)
        try:
            observed[str(pid)] = sorted(os.sched_getaffinity(pid))
            for task in Path(f"/proc/{pid}/task").glob("*/children"):
                try:
                    pending.extend(int(p) for p in task.read_text().split())
                except FileNotFoundError:
                    pass
        except ProcessLookupError:
            pass
    return observed


def stop(process):
    if process is None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)
    except ProcessLookupError:
        pass
    # Also remove descendants left behind by a failed parent.
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def interrupted(signum, frame):
    raise InterruptedError(f"Study interrupted by signal {signum}")


def run(plan_file, output):
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


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plan", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, interrupted)
    run(args.plan, args.output)
