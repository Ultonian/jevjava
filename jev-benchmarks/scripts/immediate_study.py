#!/usr/bin/env python3
"""Fixed six-pair SDK sync/async experiment with immediate in-memory transport."""
import argparse
import math
import signal
import statistics
from pathlib import Path

import executor_pilot as pilot

CELLS = ("sdk-sync-8-immediate", "sdk-async-8-immediate")
RUNS = [{"id": f"pair-{n}", "order": order, "mode": "pilot", "group": "immediate"}
        for n, order in enumerate(pilot.ORDERS, 1)]


def validate(folder, plan, order):
    feature = plan.get("javaFeature", 21)
    pilot.require(feature in (21, 25, 27), "Expected JDK 21, 25 or 27")
    return pilot.validate_pass(folder, plan, order, group="immediate", expected_cells=CELLS,
                               java_feature=feature)


def summarise(passes):
    rows, contrasts = [], []
    for run, trials in zip(RUNS, passes):
        rates = {}
        for t in trials:
            cell = pilot.cell_id(t["cell"])
            c = t["result"]["measured"]
            rates[cell] = c["successfulPerSecond"]
            rows.append({"pair": run["id"], "cell": cell, "rate": rates[cell],
                         "p50Micros": c["latencyByOutcome"]["SUCCESS"]["p50Micros"],
                         "p99Micros": c["latencyByOutcome"]["SUCCESS"]["p99Micros"]})
        contrasts.append({"pair": run["id"], "order": run["order"],
                          "asyncVsSyncPercent": 100 * (rates[CELLS[1]] / rates[CELLS[0]] - 1)})
    cells = {}
    for cell in CELLS:
        values = [r for r in rows if r["cell"] == cell]
        cells[cell] = dict(pilot.analysis.describe([r["rate"] for r in values]),
                          meanTrialP50Micros=statistics.mean(r["p50Micros"] for r in values),
                          meanTrialP99Micros=statistics.mean(r["p99Micros"] for r in values))
    return {"interpretation": "synthetic lifecycle experiment; no network or optimisation verdict",
            "cells": cells, "trials": rows, "contrasts": contrasts,
            "geometricAsyncVsSyncPercent": 100 * math.expm1(statistics.mean(
                math.log1p(c["asyncVsSyncPercent"] / 100) for c in contrasts))}


def audit(root):
    return pilot.audit(root, runs=RUNS, validate=validate, summarise_passes=summarise)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("run", "audit"), help="Measure a new study or audit an existing archive")
    parser.add_argument("output", type=Path, help="New output directory for run, existing archive for audit")
    parser.add_argument("--java", type=Path, help="JDK executable; required for run")
    parser.add_argument("--jar", type=Path, help="Verified benchmarks.jar; required for run")
    parser.add_argument("--jdk", type=int, choices=(21, 25, 27), default=21)
    parser.add_argument("--cpus", type=pilot.common.parse_cpus, help="Eight distinct CPU IDs, comma separated; required for run")
    args = parser.parse_args()
    if args.action == "audit":
        print(pilot.json.dumps(audit(args.output.resolve()), indent=2))
    else:
        if args.java is None or args.jar is None or args.cpus is None:
            parser.error("run requires --java, --jar and --cpus")
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, pilot.common.interrupted)
        pilot.run(args.output.resolve(), args.java.resolve(), args.jar.resolve(),
                  args.cpus,
                  kind="synthetic immediate transport", runs=RUNS,
                  validate=lambda folder, plan, row: validate(folder, plan, row["order"]),
                  analyse=audit, java_feature=args.jdk)


if __name__ == "__main__":
    main()
