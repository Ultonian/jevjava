#!/usr/bin/env python3
"""Fixed same-vendor, interleaved OpenJDK 21/25/27 synthetic study. No SDK treatment."""
import argparse
import json
import math
from pathlib import Path
import re
import signal
import statistics
import subprocess

from importlib.util import module_from_spec, spec_from_file_location

spec = spec_from_file_location("runtime_immediate", Path(__file__).with_name("immediate-study.py"))
immediate = module_from_spec(spec)
spec.loader.exec_module(immediate)
pilot = immediate.pilot
require = pilot.require
FEATURES = (21, 25, 27)
ORDERS = ((21, 25, 27), (21, 27, 25), (25, 27, 21),
          (25, 21, 27), (27, 21, 25), (27, 25, 21))
RUNS = [{"id": f"round-{n}-jdk{feature}", "round": n, "javaFeature": feature,
         "mode": "pilot", "group": "immediate", "order": pilot.ORDERS[n - 1]}
        for n, order in enumerate(ORDERS, 1) for feature in order]
VARIED = ("javaVersion", "vmVersion")
IDENTITY = ("bin/java", "lib/modules", "lib/server/libjvm.so", "release")


def prepare(metadata):
    """Pin verified official archives and selected runtime properties before creating output."""
    require(set(metadata) == set(map(str, FEATURES)), "Expected exactly JDK 21, 25, 27")
    runtimes = {}
    env = {"LANG": "C.UTF-8"}
    for feature in FEATURES:
        r = dict(metadata[str(feature)])
        require(r["feature"] == feature and pilot.common.sha(Path(r["archive"])) == r["archiveSha256"],
                "Runtime archive mismatch")
        java = Path(r["java"]).resolve()
        require(pilot.common.sha(java) == r["javaSha256"], "Runtime executable mismatch")
        output = subprocess.check_output([str(java), "-XshowSettings:properties", "-version"],
                                         stderr=subprocess.STDOUT, text=True, env=env)
        def prop(key):
            match = re.search(r"^\s*" + re.escape(key) + r" = (.+)$", output, re.MULTILINE)
            require(match is not None, "Missing runtime property " + key)
            return match.group(1).strip()
        expected = {"javaVersion": prop("java.version"), "javaVendor": prop("java.vendor"),
                    "vmVersion": prop("java.vm.version")}
        require(expected["javaVendor"] == "Eclipse Adoptium"
                and re.fullmatch(str(feature) + r"(?:\.\d+)*", expected["javaVersion"])
                and "-ea" not in expected["vmVersion"], "Expected same-vendor GA Temurin runtime")
        r["java"] = str(java)
        r["expectedManifest"] = expected
        r["runtimeFiles"] = {name: pilot.common.sha(java.parent.parent / name) for name in IDENTITY}
        runtimes[str(feature)] = r
    return runtimes


def verify_runtime_files(runtimes):
    for r in runtimes.values():
        home = Path(r["java"]).parent.parent
        for name, digest in r["runtimeFiles"].items():
            require(pilot.common.sha(home / name) == digest, "Installed runtime changed")


def validate(folder, plan, order):
    run = next(r for r in RUNS if r["id"] == folder.name)
    feature = run["javaFeature"]
    m, trials = immediate.validate(folder, dict(plan, javaFeature=feature), order)
    expected = plan["runtimes"][str(feature)]["expectedManifest"]
    require(m["javaVendor"] == "Eclipse Adoptium" and
            all(m[key] == value for key, value in expected.items()), "Wrong vendor/runtime identity")
    require(m["settings"]["protocol"] == "immediate-v1", "Wrong synthetic protocol")
    return m, trials


def summarise(passes):
    grouped = {str(f): [] for f in FEATURES}
    rates = {}
    for run, trials in zip(RUNS, passes):
        grouped[str(run["javaFeature"])].append(trials)
        for t in trials:
            rates[run["round"], run["javaFeature"], pilot.cell_id(t["cell"])] = \
                t["result"]["measured"]["successfulPerSecond"]
    comparisons = []
    for cell in immediate.CELLS:
        for feature in FEATURES[1:]:
            # Each runtime has distinct cell IDs only in the containing run, not the trial ID.
            values = []
            for round_id in range(1, 7):
                base = rates[round_id, 21, cell]
                candidate = rates[round_id, feature, cell]
                values.append({"round": round_id, "baseRate": base, "runtimeRate": candidate,
                               "percent": 100 * (candidate / base - 1)})
            comparisons.append({"cell": cell, "jdk": feature, "referenceJdk": 21,
                                "rounds": values, "geometricPercent": 100 * math.expm1(
                                    statistics.mean(math.log(v["runtimeRate"] / v["baseRate"])
                                                    for v in values))})
    return {"interpretation": "interleaved same-vendor runtime comparison; descriptive, no SDK treatment",
            "runtimes": {f: immediate.summarise(rows) for f, rows in grouped.items()},
            "sameRoundComparisons": comparisons}


def audit(root):
    plan = pilot.analysis.read(root / "plan.json")
    require(set(plan["runtimes"]) == set(map(str, FEATURES)), "Missing runtime condition")
    for feature in FEATURES:
        r = plan["runtimes"][str(feature)]
        expected = r["expectedManifest"]
        require(r["feature"] == feature and expected["javaVendor"] == "Eclipse Adoptium"
                and re.fullmatch(str(feature) + r"(?:\.\d+)*", expected["javaVersion"])
                and "-ea" not in expected["vmVersion"], "Invalid GA runtime condition")
    # Archived manifests contain the pinned versions; reanalysis does not require installed JDKs.
    return pilot.audit(root, runs=RUNS, validate=validate, summarise_passes=summarise,
                       varied_runtime_keys=VARIED)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("run", "audit"))
    parser.add_argument("output", type=Path)
    parser.add_argument("--runtimes", type=Path, help="Verified download metadata for all three JDKs")
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--cpus", default="8,9,10,11,12,13,14,15")
    args = parser.parse_args()
    if args.action == "audit":
        print(json.dumps(audit(args.output.resolve()), indent=2))
    else:
        if args.runtimes is None or args.jar is None:
            parser.error("run requires --runtimes and --jar")
        runtimes = prepare(pilot.analysis.read(args.runtimes))
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, pilot.common.interrupted)
        def final_audit(root):
            verify_runtime_files(runtimes)
            return audit(root)
        pilot.run(args.output.resolve(), Path(runtimes["21"]["java"]), args.jar.resolve(),
                  [int(cpu) for cpu in args.cpus.split(",")], kind="interleaved Temurin 21/25/27",
                  runs=RUNS, runtimes=runtimes,
                  validate=lambda folder, plan, row: validate(folder, plan, row["order"]),
                  analyse=final_audit)
