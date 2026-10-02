#!/usr/bin/env python3
"""Run or audit the fixed six-triplet JDK 21 executor pilot. No general A/B support."""
import argparse
import importlib.util
import json
import math
import os
import re
from pathlib import Path
import shutil
import signal
import statistics
import subprocess
import time


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


common = load("pilot_common", "run-study.py")
analysis = load("pilot_analysis", "analyse-study.py")
require = analysis.require
CELLS = ("sdk-async-8-base", "sdk-async-8-platform", "sdk-sync-8-base")
ORDERS = ("forward", "reverse") * 3
VM = ["-Xms512m", "-Xmx512m", "-XX:+UseG1GC",
      "-Dsun.net.httpserver.nodelay=true", "-Djdk.httpserver.maxConnections=512"]


def cell_id(cell):
    return ("control" if cell["control"] else "sdk") + "-" + cell["submission"].lower() + \
        "-" + str(cell["concurrency"]) + "-" + cell["variant"].lower()


def validate_pass(folder, plan, order, *, group="async-executor", expected_cells=CELLS,
                  java_feature=21):
    m = analysis.read(folder / "manifest.json")
    require(m["status"] == "complete" and m["gitDirty"] is False
            and m["gitCommit"] == plan["sourceCommit"], "Incomplete/dirty/wrong source")
    require(m["benchmarkJarSha256"] == plan["jarSha256"]
            and m["resultSha256"] == common.sha(folder / "results.json"), "Artifact mismatch")
    require(m["availableProcessors"] == len(plan["cpus"])
            and (m["javaVersion"] == str(java_feature)
                 or m["javaVersion"].startswith(str(java_feature) + "."))
            and m["vmOptions"] == VM, "Runtime mismatch")
    s = m["settings"]
    cells = expected_cells if order == "forward" else expected_cells[::-1]
    require(s["mode"] == "pilot" and s["group"] == group and s["forks"] == 1
            and s["warmupMillis"] == 10000 and s["measurementMillis"] == 30000
            and s["order"] == order and tuple(map(cell_id, s["cells"])) == cells,
            "Pilot settings/order mismatch")
    trials = analysis.read(folder / "results.json")["trials"]
    require(tuple(cell_id(t["cell"]) for t in trials) == cells, "Missing/reordered trial")
    for t in trials:
        directory = cell_id(t["cell"]) + "-fork1"
        require(t["fork"] == 1 and t["directory"] == directory, "Wrong fork")
        path = folder / directory / "results.json"
        require(t["resultSha256"] == common.sha(path)
                and t["result"] == analysis.read(path), "Trial artifact mismatch")
        r = t["result"]
        require(r["status"] == "complete" and r["mode"] == "pilot"
                and r["cell"] == t["cell"], "Incomplete/mismatched trial")
        require(r["scheduler"] == {"availableProcessors": len(plan["cpus"]),
                "parallelismOverride": "unset", "maxPoolSizeOverride": "unset"},
                "Unexpected scheduler configuration")
        for cohort, duration in (("warmup", 10_000_000_000), ("measured", 30_000_000_000)):
            analysis.validate_cohort(r[cohort], duration, immediate=group == "immediate")
            require(0 < r[cohort]["peakInFlight"] <= 8, "Concurrency exceeded")
    return m, trials


def summarise(triplets):
    rows, contrasts = [], []
    for number, trials in enumerate(triplets, 1):
        rates = {}
        for t in trials:
            cell = cell_id(t["cell"])
            c = t["result"]["measured"]
            rates[cell] = c["successfulPerSecond"]
            rows.append({"triplet": number, "cell": cell, "rate": rates[cell],
                         "p50Micros": c["latencyByOutcome"]["SUCCESS"]["p50Micros"],
                         "p99Micros": c["latencyByOutcome"]["SUCCESS"]["p99Micros"]})
        b, p, s = (rates[cell] for cell in CELLS)
        contrasts.append({"triplet": number, "order": ORDERS[number - 1],
                          "platformVsBasePercent": 100 * (p / b - 1),
                          "baseVsSyncPercent": 100 * (b / s - 1),
                          "platformVsSyncPercent": 100 * (p / s - 1)})
    summary = {}
    for cell in CELLS:
        values = [r for r in rows if r["cell"] == cell]
        summary[cell] = dict(analysis.describe([r["rate"] for r in values]),
                            meanTrialP50Micros=statistics.mean(r["p50Micros"] for r in values),
                            meanTrialP99Micros=statistics.mean(r["p99Micros"] for r in values))
    geometric = 100 * math.expm1(statistics.mean(
        math.log1p(c["platformVsBasePercent"] / 100) for c in contrasts))
    return {"interpretation": "exploratory configuration pilot; no headroom or regression verdict",
            "cells": summary, "trials": rows, "contrasts": contrasts,
            "geometricMeanPlatformVsBasePercent": geometric}


def audit(root, *, runs=None, validate=None, summarise_passes=None, varied_runtime_keys=()):
    if runs is None:
        runs = [{"id": f"triplet-{n}", "order": order} for n, order in enumerate(ORDERS, 1)]
    if validate is None:
        validate = validate_pass
    if summarise_passes is None:
        summarise_passes = summarise
    plan, study = analysis.read(root / "plan.json"), analysis.read(root / "study.json")
    require(study["status"] == "complete" and study["planSha256"] == common.sha(root / "plan.json"),
            "Incomplete/changed study")
    require(plan["runs"] == runs
            and [r["id"] for r in study["completed"]] == [r["id"] for r in plan["runs"]],
            "Unexpected schedule")
    require(common.sha(root / "benchmarks.jar") == plan["jarSha256"]
            and common.sha(root / "source.tar.gz") == plan["sourceArchiveSha256"],
            "Archived artifacts changed")
    triplets, signatures, host_settings = [], {}, None
    for run, completed in zip(plan["runs"], study["completed"]):
        m, trials = validate(root / run["id"], plan, run["order"])
        require(completed["resultSha256"] == m["resultSha256"], "Completed result changed")
        # Settings and their hash intentionally differ between forward and reverse.
        signature = {k: m[k] for k in analysis.COMMON if k not in ("settings", "configurationSha256", *varied_runtime_keys)}
        require(not signatures or signatures == signature, "Runtime/fixture drift")
        signatures = signature
        observed = set()
        for line in (root / (run["id"] + "-host.jsonl")).read_text().splitlines():
            sample = json.loads(line)
            static = {k: sample[k] for k in ("platformProfile", "boost", "pstateStatus", "powerOnline")}
            static["frequencyPolicies"] = {
                cpu: {k: v for k, v in policy.items() if k != "scaling_cur_freq"}
                for cpu, policy in sample["frequencyPolicies"].items()}
            require(host_settings is None or host_settings == static, "Host policy changed")
            host_settings = static
            for pid, mask in sample["processAffinities"].items():
                require(mask == sorted(plan["cpus"]), "Observed affinity mismatch")
                observed.add(pid)
        require(len(observed) >= len(trials) + 1 and sorted(observed) == completed["observedProcessIds"],
                "Missing parent/child affinity observations")
        triplets.append(trials)
    return summarise_passes(triplets)


def run(root, java, jar, cpus, *, kind="exploratory executor pilot", runs=None,
        validate=None, analyse=None, java_feature=None, runtimes=None):
    # Shared launch mechanics for fixed protocols, not a general A/B plan interpreter.
    if runs is None:
        runs = [{"id": f"triplet-{n}", "order": order} for n, order in enumerate(ORDERS, 1)]
    if validate is None:
        validate = lambda folder, plan, row: validate_pass(folder, plan, row["order"])
    if analyse is None:
        analyse = audit
    require(len(cpus) == len(set(cpus)) == 8 and set(cpus) <= os.sched_getaffinity(0),
            "Pilot requires eight distinct available CPUs")
    taskset = shutil.which("taskset")
    require(taskset is not None and java.is_file() and jar.is_file(), "Missing taskset/java/JAR")
    if java_feature is not None:
        require(java_feature in (21, 25, 27), "Expected JDK 21, 25 or 27")
        version = subprocess.check_output([str(java), "-version"], stderr=subprocess.STDOUT, text=True)
        match = re.search(r'version "(\d+)(?:\.|")', version)
        require(match is not None and int(match.group(1)) == java_feature, "Selected JDK mismatch")
    snapshot = Path.cwd().resolve()
    require(not subprocess.check_output(["git", "status", "--porcelain"]).strip(), "Dirty source")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    root.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(jar, root / "benchmarks.jar")
    subprocess.run(["git", "archive", "--format=tar.gz", "--output=" + str(root / "source.tar.gz"),
                    commit], check=True)
    plan = {"kind": kind, "sourceCommit": commit,
            "sourceArchiveSha256": common.sha(root / "source.tar.gz"),
            "jarSha256": common.sha(root / "benchmarks.jar"), "cpus": cpus,
            "java": str(java), "snapshot": str(snapshot),
            "runs": runs}
    if java_feature is not None:
        plan["javaFeature"] = java_feature
    if runtimes is not None:
        plan["runtimes"] = runtimes
    common.save(root / "plan.json", plan)
    state = {"status": "running", "startedAt": common.stamp(),
             "planSha256": common.sha(root / "plan.json"), "completed": []}
    common.save(root / "study.json", state)
    env = {k: os.environ[k] for k in ("PATH", "SystemRoot", "WINDIR", "TEMP", "TMP", "TMPDIR")
           if k in os.environ}
    env["LANG"] = "C.UTF-8"
    process = None
    try:
        for row in plan["runs"]:
            require(common.sha(root / "benchmarks.jar") == plan["jarSha256"], "JAR changed")
            selected_java = java
            if runtimes is not None:
                runtime = runtimes[str(row["javaFeature"])]
                selected_java = Path(runtime["java"])
                require(common.sha(selected_java) == runtime["javaSha256"], "Runtime binary changed")
            command = [taskset, "-c", ",".join(map(str, cpus)), str(selected_java), "-cp",
                       str(root / "benchmarks.jar"), "net.codefinch.jev.benchmarks.reporting.RunLoad",
                       str(root / row["id"]), row.get("mode", "pilot"),
                       row.get("group", "async-executor"), row["order"]]
            common.save(root / (row["id"] + "-launch.json"),
                        {"command": command, "cwd": str(snapshot), "hostBefore": common.host(cpus),
                         "environmentPolicy": "OS/path/temp allowlist; fixed locale; no JVM injection"})
            print(f"{common.stamp()} Starting {row['id']} {row['order']}", flush=True)
            began, observed = time.monotonic(), set()
            with (root / (row["id"] + "-launcher.log")).open("w") as console, \
                    (root / (row["id"] + "-host.jsonl")).open("w") as samples:
                process = subprocess.Popen(command, cwd=snapshot, env=env, stdout=console,
                                           stderr=subprocess.STDOUT, start_new_session=True)
                while True:
                    try:
                        code = process.wait(timeout=10)
                        break
                    except subprocess.TimeoutExpired:
                        require(time.monotonic() - began <= 420, "Triplet watchdog expired")
                        affinities = common.process_affinities(process.pid)
                        require(all(mask == sorted(cpus) for mask in affinities.values()),
                                "Parent/child affinity mismatch")
                        observed.update(affinities)
                        sample = common.host(cpus)
                        sample["processAffinities"] = affinities
                        samples.write(json.dumps(sample) + "\n")
                        samples.flush()
                require(code == 0, f"Trial failed; see {row['id']}-launcher.log")
            common.stop(process)
            process = None
            manifest, _ = validate(root / row["id"], plan, row)
            state["completed"].append({"id": row["id"], "seconds": time.monotonic() - began,
                                       "resultSha256": manifest["resultSha256"],
                                       "observedProcessIds": sorted(observed)})
            common.save(root / "study.json", state)
            print(f"{common.stamp()} Completed {row['id']}", flush=True)
        state["status"] = "complete"
    finally:
        common.stop(process)
        if state["status"] != "complete":
            state["status"] = "failed"
        state["finishedAt"] = common.stamp()
        common.save(root / "study.json", state)
    try:
        summary = analyse(root)
    except Exception:
        state["status"] = "failed-audit"
        common.save(root / "study.json", state)
        raise
    common.save(root / "summary.json", summary)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("run", "audit"))
    parser.add_argument("output", type=Path)
    parser.add_argument("--java", type=Path)
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--cpus", default="8,9,10,11,12,13,14,15")
    args = parser.parse_args()
    if args.action == "audit":
        print(json.dumps(audit(args.output.resolve()), indent=2))
    else:
        if args.java is None or args.jar is None:
            parser.error("run requires --java and --jar")
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, common.interrupted)
        run(args.output.resolve(), args.java.resolve(), args.jar.resolve(),
            [int(cpu) for cpu in args.cpus.split(",")])
