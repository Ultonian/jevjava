#!/usr/bin/env python3
"""Validate and summarise the fixed A/A protocol (five A passes, two B passes/JDK).

The statistical unit is a complete three-fork pass. This deliberately does not set
regression thresholds, pool JDKs, or treat individual calls as independent trials.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import statistics

CELLS = ("control-async-8-base", "sdk-sync-8-base", "sdk-async-8-base")
COMMON = ("settings", "configurationSha256", "vmOptions", "fixtures", "fixtureProvenance",
          "javaVersion", "javaVendor", "vmVersion", "os", "osVersion", "architecture",
          "availableProcessors", "cpuModel", "cgroupV2")


def read(path):
    return json.loads(path.read_text())


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def describe(values):
    require(len(values) >= 2 and all(math.isfinite(x) and x > 0 for x in values),
            "Need at least two finite positive pass rates")
    mean = statistics.mean(values)
    sd = statistics.stdev(values)
    return {"n": len(values), "passRates": values, "mean": mean, "sampleSd": sd,
            "cvPercent": 100 * sd / mean, "min": min(values), "max": max(values)}


def validate_cohort(c, nanos, *, immediate=False):
    require(c["windowNanos"] == nanos, "Wrong measurement duration")
    n = c["admitted"]
    require(n > 0 and n == c["offered"] == c["completed"] == c["httpAttempts"],
            "Cohort accounting mismatch")
    require(c["completedInWindow"] + c["drainCompletions"] == n, "Drain accounting mismatch")
    require(c["unfinished"] == c["retries"] == 0 and not c["forcedCleanup"]
            and (immediate or c["serverDrained"]) and c["observersDrained"], "Incomplete cohort")
    require(c["outcomes"]["SUCCESS"] == n and sum(c["outcomes"].values()) == n,
            "Unexpected outcome")
    h = c["latencyByOutcome"]["SUCCESS"]
    require(h["recorded"] == n and h["overflow"] == 0
            and sum(b["count"] for b in h["buckets"]) == n, "Latency accounting mismatch")
    expected_rate = c["completedInWindow"] * 1e9 / nanos
    require(math.isclose(expected_rate, c["successfulPerSecond"], rel_tol=1e-12),
            "Incorrect in-window rate")
    if immediate:
        require(c["transportKind"] == "immediate-in-memory" and "serverAfter" not in c
                and c["expectedRequestBytesPerAttempt"] > 0
                and c["requestBytes"] == n * c["expectedRequestBytesPerAttempt"],
                "Synthetic byte/transport accounting mismatch")
        return
    require(c["serverAfter"]["activeHandlers"] == 0
            and c["serverAfter"]["protocol"] == "HTTP/1.1", "Server not drained/HTTP1.1")
    for key in ("rejectedTasks", "ioFailures"):
        require(c["serverAfter"][key] == c["serverBefore"][key], "Server error")


def analyse(root):
    study, plan = read(root / "study.json"), read(root / "plan.json")
    require(study["status"] == "complete", "Study is incomplete")
    require(study["planSha256"] == sha(root / "plan.json"), "Plan hash mismatch")
    ids = [r["id"] for r in plan["runs"]]
    require(len(ids) == len(set(ids)) == 14, "Expected fourteen unique passes")
    require([r["id"] for r in study["completed"]] == ids, "Incomplete/reordered schedule")
    expected = [(jdk, arm, rep) for jdk in (21, 25)
                for arm, rep in (("A", 1), ("B", 1), ("B", 2), ("A", 2),
                                 ("A", 3), ("A", 4), ("A", 5))]
    require([(r["jdk"], r["arm"], r["replicate"]) for r in plan["runs"]] == expected,
            "Unexpected study design")
    reference, rates, rows, provenance = {}, {}, [], {}
    host_conditions = None
    for run, completed in zip(plan["runs"], study["completed"]):
        folder = root / run["id"]
        m = read(folder / "manifest.json")
        require(m["status"] == "complete" and m["gitDirty"] is False
                and m["gitCommit"] == plan["sourceCommit"], "Unclean/incomplete source identity")
        require(m["benchmarkJarSha256"] == run["jarSha256"], "Jar identity mismatch")
        require(m["resultSha256"] == completed["resultSha256"] == sha(folder / "results.json"),
                "Result hash mismatch")
        require(m["availableProcessors"] == len(plan["cpus"]), "Processor count mismatch")
        require(m["javaVersion"].split(".")[0] == str(run["jdk"]), "Wrong JDK")
        signature = {k: m[k] for k in COMMON}
        if run["jdk"] in reference:
            require(signature == reference[run["jdk"]], "Unmatched runtime/configuration/fixtures")
        else:
            reference[run["jdk"]] = signature
        s = m["settings"]
        require(s["mode"] == "baseline" and s["group"] == "representative"
                and s["forks"] == 3 and s["warmupMillis"] == 10000
                and s["measurementMillis"] == 30000, "Wrong workload settings")
        samples = [json.loads(line) for line in (root / (run["id"] + "-host.jsonl")).read_text().splitlines()]
        observed = set()
        for sample in samples:
            conditions = {k: sample[k] for k in ("platformProfile", "boost", "pstateStatus")}
            conditions["frequencyPolicies"] = {
                cpu: {k: v for k, v in policy.items() if k != "scaling_cur_freq"}
                for cpu, policy in sample["frequencyPolicies"].items()}
            if host_conditions is None:
                host_conditions = conditions
            require(conditions == host_conditions, "Host governor/power conditions changed")
            for pid, mask in sample["processAffinities"].items():
                require(mask == sorted(plan["cpus"]), "Observed affinity mismatch")
                observed.add(pid)
        require(len(observed) >= 10 and sorted(observed) == completed["observedProcessIds"],
                "Missing affinity observations")
        provenance[run["id"]] = {"manifestSha256": sha(folder / "manifest.json"),
                                 "resultSha256": sha(folder / "results.json"),
                                 "hostSha256": sha(root / (run["id"] + "-host.jsonl")),
                                 "launchSha256": sha(root / (run["id"] + "-launch.json"))}
        trials = read(folder / "results.json")["trials"]
        require(len(trials) == 9, "Expected nine trials")
        seen = set()
        for t in trials:
            c = t["cell"]
            cell = f"{'control' if c['control'] else 'sdk'}-{c['submission'].lower()}-{c['concurrency']}-{c['variant'].lower()}"
            key = (cell, t["fork"])
            require(cell in CELLS and t["fork"] in (1, 2, 3) and key not in seen,
                    "Duplicate/unexpected trial")
            seen.add(key)
            require(t["directory"] == f"{cell}-fork{t['fork']}", "Unexpected trial path")
            raw = folder / t["directory"] / "results.json"
            require(sha(raw) == t["resultSha256"] and read(raw) == t["result"], "Trial hash mismatch")
            r = t["result"]
            require(r["status"] == "complete" and r["cell"] == c and r["mode"] == "baseline",
                    "Incomplete/mismatched trial")
            validate_cohort(r["warmup"], 10_000_000_000)
            validate_cohort(r["measured"], 30_000_000_000)
            measured = r["measured"]
            rate = measured["successfulPerSecond"]
            rates.setdefault((run["jdk"], run["arm"], run["replicate"], cell), []).append(rate)
            h = measured["latencyByOutcome"]["SUCCESS"]
            server = measured["serverMeasurement"]
            rows.append({"run": run["id"], "jdk": run["jdk"], "arm": run["arm"],
                         "replicate": run["replicate"], "cell": cell, "fork": t["fork"],
                         "callsPerSecond": rate, "p50Micros": h["p50Micros"], "p99Micros": h["p99Micros"],
                         "queueMeanMicros": server["executorQueueNanos"] / server["executorTasks"] / 1000})
    means = {key: statistics.mean(v) for key, v in rates.items()}
    summaries = []
    for jdk in (21, 25):
        for cell in CELLS:
            baseline = [means[(jdk, "A", i, cell)] for i in range(1, 6)]
            comparisons = [100 * (means[(jdk, "B", i, cell)] / means[(jdk, "A", i, cell)] - 1)
                           for i in (1, 2)]
            summaries.append({"jdk": jdk, "cell": cell, **describe(baseline),
                              "aaPairedChangePercent": comparisons})
    return {"unit": "mean of three fresh forks per pass; five A passes per JDK/cell",
            "baseline": summaries, "conditionsByJdk": reference,
            "hostConditions": host_conditions, "provenance": provenance}, rows


def write_outputs(root):
    summary, rows = analyse(root)
    (root / "noise-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    with (root / "forks.csv").open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    lines = ["# Repeatability and A/A results", "",
             "Rates are calls/s, averaged across three forks per pass. CV uses sample SD across five A passes.",
             "A/A pairs show B/A - 1; positive means B was faster despite identical SDK sources.", "",
             "| JDK | Cell | Five A pass rates | Mean | SD | CV | A/A AB | A/A BA |",
             "|---|---|---|---|---|---|---|---|"]
    for r in summary["baseline"]:
        passes = ", ".join(f"{x:,.0f}" for x in r["passRates"])
        ab, ba = r["aaPairedChangePercent"]
        lines.append(f"| {r['jdk']} | {r['cell']} | {passes} | {r['mean']:,.0f} | "
                     f"{r['sampleSd']:,.0f} | {r['cvPercent']:.2f}% | {ab:+.2f}% | {ba:+.2f}% |")
    lines += ["", "Descriptive developer-machine evidence only: five passes are a small sample; "
              "temporal correlation, shared server costs and unreserved CPUs remain. "
              "No regression threshold or optimisation claim follows from this A/A study.", ""]
    (root / "noise-summary.md").write_text("\n".join(lines))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("study", type=Path)
    write_outputs(parser.parse_args().study)
