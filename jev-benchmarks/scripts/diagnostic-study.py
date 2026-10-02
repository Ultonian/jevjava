#!/usr/bin/env python3
"""Fixed JDK 21 long-window JFR study: three recorded/unrecorded pairs, no SDK changes."""
import argparse
import json
import math
from pathlib import Path
import signal
import statistics
import xml.etree.ElementTree as ET
import zipfile

import importlib.util

spec = importlib.util.spec_from_file_location("executor_pilot", Path(__file__).with_name("executor-pilot.py"))
pilot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pilot)
common, analysis, require = pilot.common, pilot.analysis, pilot.require
CELLS = ("control-async-8-base", "sdk-sync-8-base", "sdk-async-8-base")
MODES = ("diagnostic-long", "diagnostic-long-control", "diagnostic-long-control",
         "diagnostic-long", "diagnostic-long", "diagnostic-long-control")
RUNS = [{"id": f"pair-{i // 2 + 1}-{'on' if mode == 'diagnostic-long' else 'off'}",
         "pair": i // 2 + 1, "mode": mode, "group": "representative", "order": "forward"}
        for i, mode in enumerate(MODES)]


def profile(root):
    with zipfile.ZipFile(root / "benchmarks.jar") as jar:
        data = jar.read("diagnostics/restricted-long.jfc")
    settings = {e.attrib["name"] + "#" + s.attrib["name"]: s.text
                for e in ET.fromstring(data).findall("event") for s in e.findall("setting")}
    return analysis.hashlib.sha256(data).hexdigest(), settings


def validate_pass(folder, plan, run):
    m = analysis.read(folder / "manifest.json")
    require(m["status"] == "complete" and m["gitDirty"] is False
            and m["gitCommit"] == plan["sourceCommit"], "Incomplete/dirty/wrong source")
    require(m["benchmarkJarSha256"] == plan["jarSha256"]
            and m["resultSha256"] == common.sha(folder / "results.json"), "Artifact mismatch")
    require(m["availableProcessors"] == 8 and m["javaVersion"].startswith("21.")
            and m["vmOptions"] == pilot.VM, "Runtime mismatch or pin tracing enabled")
    s = m["settings"]
    require(s["mode"] == run["mode"] and s["group"] == "representative" and s["forks"] == 1
            and s["warmupMillis"] == 10000 and s["measurementMillis"] == 30000
            and tuple(map(pilot.cell_id, s["cells"])) == CELLS, "Long diagnostic settings mismatch")
    trials = analysis.read(folder / "results.json")["trials"]
    require(tuple(pilot.cell_id(t["cell"]) for t in trials) == CELLS, "Missing/reordered trial")
    config_hash, settings = profile(folder.parent)
    for t in trials:
        directory = pilot.cell_id(t["cell"]) + "-fork1"
        require(t["fork"] == 1 and t["directory"] == directory, "Wrong fork")
        path = folder / directory / "results.json"
        require(t["resultSha256"] == common.sha(path)
                and t["result"] == analysis.read(path), "Trial artifact mismatch")
        r = t["result"]
        require(r["status"] == "complete" and r["mode"] == run["mode"]
                and r["cell"] == t["cell"], "Trial metadata mismatch")
        require(r["scheduler"] == {"availableProcessors": 8, "parallelismOverride": "unset",
                                  "maxPoolSizeOverride": "unset"}, "Scheduler mismatch")
        for cohort, duration in (("warmup", 10_000_000_000), ("measured", 30_000_000_000)):
            analysis.validate_cohort(r[cohort], duration)
            require(0 < r[cohort]["peakInFlight"] <= 8, "Concurrency exceeded")
        jfr = folder / directory / "diagnostics.jfr"
        if run["mode"] == "diagnostic-long-control":
            require("diagnostics" not in r and not jfr.exists(), "Unrecorded control has JFR")
            continue
        d = r["diagnostics"]
        require(d["configurationSha256"] == config_hash
                and all(d["settings"].get(k) == v for k, v in settings.items()), "Profile mismatch")
        enabled = {k.removesuffix("#enabled") for k, v in d["settings"].items()
                   if k.endswith("#enabled") and v == "true"}
        expected = {k.removesuffix("#enabled") for k, v in settings.items()
                    if k.endswith("#enabled") and v == "true"}
        require(enabled == expected and set(d["recordingEventCounts"]) <= enabled,
                "Recording event outside allowlist")
        require(d["recordingSha256"] == common.sha(jfr) and d["recordingBytes"] == jfr.stat().st_size,
                "Recording artifact mismatch")
        require(not d["nearRetentionLimit"] and d["recordingBytes"] < 56 * 1024 * 1024
                and d["recordingDataLossBytes"] == 0
                and d["recordingEventCounts"].get("jdk.DataLoss", 0) == 0, "Recording loss/cap")
        require(d["windowStart"] == r["measured"]["windowStart"]
                and d["windowEnd"] == r["measured"]["windowEnd"], "Wrong diagnostic window")
        samples = d["executionSamples"]
        total = sum(d["eventCounts"].get(k, 0) for k in ("jdk.ExecutionSample", "jdk.NativeMethodSample"))
        require(sum(samples["countsByRole"].values()) == total
                and sum(samples["countsByThreadKind"].values()) == total
                and sum(x["count"] for x in samples["retainedStacks"])
                + samples["unretainedStackSamples"] == total, "Sample accounting mismatch")
    return m, trials


def summarise(passes):
    rows = []
    for run, trials in passes:
        for t in trials:
            r = t["result"]
            c = r["measured"]
            rows.append({"pair": run["pair"], "mode": run["mode"], "cell": pilot.cell_id(t["cell"]),
                         "rate": c["successfulPerSecond"],
                         "p50Micros": c["latencyByOutcome"]["SUCCESS"]["p50Micros"],
                         "p99Micros": c["latencyByOutcome"]["SUCCESS"]["p99Micros"],
                         "diagnostics": r.get("diagnostics")})
    contrasts = []
    for pair in (1, 2, 3):
        for cell in CELLS:
            selected = {r["mode"]: r for r in rows if r["pair"] == pair and r["cell"] == cell}
            on, off = (selected[k] for k in ("diagnostic-long", "diagnostic-long-control"))
            contrasts.append({"pair": pair, "cell": cell, "recordedRate": on["rate"],
                              "unrecordedRate": off["rate"],
                              "recordedVsUnrecordedPercent": 100 * (on["rate"] / off["rate"] - 1)})
    return {"interpretation": "diagnostic perturbation study; no SDK optimisation/regression verdict",
            "trials": rows, "contrasts": contrasts,
            "geometricRecordedVsUnrecordedPercent": {
                cell: 100 * math.expm1(statistics.mean(math.log(x["recordedRate"] / x["unrecordedRate"])
                                                      for x in contrasts if x["cell"] == cell))
                for cell in CELLS}}


def audit(root):
    plan, study = analysis.read(root / "plan.json"), analysis.read(root / "study.json")
    require(plan["runs"] == RUNS and len(plan["cpus"]) == len(set(plan["cpus"])) == 8,
            "Unexpected protocol")
    require(study["status"] == "complete" and study["planSha256"] == common.sha(root / "plan.json")
            and [x["id"] for x in study["completed"]] == [x["id"] for x in RUNS], "Incomplete study")
    require(common.sha(root / "benchmarks.jar") == plan["jarSha256"]
            and common.sha(root / "source.tar.gz") == plan["sourceArchiveSha256"], "Archive mismatch")
    passes, reference, host_policy = [], None, None
    for run, completed in zip(RUNS, study["completed"]):
        m, trials = validate_pass(root / run["id"], plan, run)
        require(completed["resultSha256"] == m["resultSha256"], "Completed result changed")
        signature = {k: m[k] for k in analysis.COMMON if k not in ("settings", "configurationSha256")}
        require(reference is None or reference == signature, "Runtime/fixture drift")
        reference = signature
        observed = set()
        for line in (root / (run["id"] + "-host.jsonl")).read_text().splitlines():
            sample = json.loads(line)
            policy = {k: sample[k] for k in ("platformProfile", "boost", "pstateStatus", "powerOnline")}
            policy["frequencyPolicies"] = {
                cpu: {k: v for k, v in p.items() if k != "scaling_cur_freq"}
                for cpu, p in sample["frequencyPolicies"].items()}
            require(host_policy is None or host_policy == policy, "Host policy drift")
            host_policy = policy
            for pid, mask in sample["processAffinities"].items():
                require(mask == sorted(plan["cpus"]), "Affinity mismatch")
                observed.add(pid)
        require(len(observed) >= 4 and sorted(observed) == completed["observedProcessIds"],
                "Missing process observations")
        passes.append((run, trials))
    return summarise(passes)


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
        pilot.run(args.output.resolve(), args.java.resolve(), args.jar.resolve(),
                  [int(cpu) for cpu in args.cpus.split(",")], kind="matched long-window JFR",
                  runs=RUNS, validate=validate_pass, analyse=audit)
