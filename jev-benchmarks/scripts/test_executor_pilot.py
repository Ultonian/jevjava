"""Fast pilot contract/audit tests. No measurement JVMs or external services."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import executor_pilot as pilot


class ExecutorPilotTest(unittest.TestCase):
    def fixture(self, root, *, runs=None, expected_cells=pilot.CELLS, group="async-executor"):
        if runs is None:
            runs = [{"id": f"triplet-{i}", "order": o} for i, o in enumerate(pilot.ORDERS, 1)]
        (root / "benchmarks.jar").write_bytes(b"jar")
        (root / "source.tar.gz").write_bytes(b"source")
        plan = {"sourceCommit": "commit", "jarSha256": pilot.common.sha(root / "benchmarks.jar"),
                "sourceArchiveSha256": pilot.common.sha(root / "source.tar.gz"), "cpus": list(range(8)),
                "runs": runs}
        completed = []
        for row in plan["runs"]:
            folder = root / row["id"]
            folder.mkdir()
            cells = expected_cells if row["order"] == "forward" else expected_cells[::-1]
            trials = []
            for name in cells:
                cell = {"control": False, "submission": name.split("-")[1].upper(),
                        "concurrency": 8, "variant": name.split("-")[3].upper()}
                rate = 110 if name == expected_cells[1] else 100
                cohorts = {}
                for label, seconds in (("warmup", 10), ("measured", 30)):
                    n = rate * seconds
                    cohorts[label] = {
                        "windowNanos": seconds * 1_000_000_000, "successfulPerSecond": rate,
                        "admitted": n, "offered": n, "completed": n, "httpAttempts": n,
                        "completedInWindow": n, "drainCompletions": 0, "peakInFlight": 8,
                        "unfinished": 0, "retries": 0, "forcedCleanup": False,
                        "serverDrained": True, "observersDrained": True, "outcomes": {"SUCCESS": n},
                        "latencyByOutcome": {"SUCCESS": {"recorded": n, "overflow": 0,
                            "buckets": [{"count": n}], "p50Micros": 1, "p99Micros": 2}},
                        "serverBefore": {"rejectedTasks": 0, "ioFailures": 0},
                        "serverAfter": {"activeHandlers": 0, "protocol": "HTTP/1.1",
                                        "rejectedTasks": 0, "ioFailures": 0}}
                    if group == "immediate":
                        for key in ("serverBefore", "serverAfter", "serverDrained"):
                            cohorts[label].pop(key)
                        cohorts[label].update(transportKind="immediate-in-memory",
                                              expectedRequestBytesPerAttempt=100, requestBytes=100*n)
                r = dict(cohorts, cell=cell, status="complete", mode="pilot",
                         scheduler={"availableProcessors": 8, "parallelismOverride": "unset",
                                    "maxPoolSizeOverride": "unset"})
                directory = name + "-fork1"
                (folder / directory).mkdir()
                pilot.common.save(folder / directory / "results.json", r)
                trials.append({"cell": cell, "fork": 1, "directory": directory, "result": r,
                               "resultSha256": pilot.common.sha(folder / directory / "results.json")})
            pilot.common.save(folder / "results.json", {"trials": trials})
            m = {key: None for key in pilot.analysis.COMMON}
            m.update(status="complete", gitDirty=False, gitCommit="commit",
                     benchmarkJarSha256=plan["jarSha256"], availableProcessors=8,
                     javaVersion="21.0.11", vmOptions=pilot.VM,
                     resultSha256=pilot.common.sha(folder / "results.json"),
                     settings={"mode": "pilot", "group": group, "forks": 1,
                               "warmupMillis": 10000, "measurementMillis": 30000,
                               "order": row["order"], "cells": [t["cell"] for t in trials]})
            pilot.common.save(folder / "manifest.json", m)
            sample = {"platformProfile": "performance", "boost": "1", "pstateStatus": "active",
                      "powerOnline": {"AC": "1"}, "frequencyPolicies": {},
                      "processAffinities": {str(pid): list(range(8)) for pid in range(1, 5)}}
            (root / (row["id"] + "-host.jsonl")).write_text(json.dumps(sample) + "\n")
            completed.append({"id": row["id"], "resultSha256": m["resultSha256"],
                              "observedProcessIds": ["1", "2", "3", "4"]})
        pilot.common.save(root / "plan.json", plan)
        pilot.common.save(root / "study.json", {"status": "complete", "completed": completed,
                          "planSha256": pilot.common.sha(root / "plan.json")})
        return plan

    def test_full_audit_and_known_treatment_effect(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            result = pilot.audit(root)
            self.assertAlmostEqual(result["geometricMeanPlatformVsBasePercent"], 10)
            self.assertEqual(len(result["trials"]), 18)
            self.assertEqual([c["order"] for c in result["contrasts"]], list(pilot.ORDERS))

    def test_rejects_manifest_drift_missing_trials_and_corruption(self):
        for corruption in ("dirty", "jdk", "forks", "order", "jar", "result", "missing", "scheduler"):
            with self.subTest(corruption=corruption), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                plan = self.fixture(root)
                folder = root / "triplet-1"
                manifest = pilot.analysis.read(folder / "manifest.json")
                if corruption == "dirty":
                    manifest["gitDirty"] = True
                elif corruption == "jdk":
                    manifest["javaVersion"] = "25.0"
                elif corruption == "forks":
                    manifest["settings"]["forks"] = 3
                elif corruption == "order":
                    manifest["settings"]["cells"].reverse()
                elif corruption == "jar":
                    manifest["benchmarkJarSha256"] = "wrong"
                else:
                    data = pilot.analysis.read(folder / "results.json")
                    if corruption == "missing":
                        data["trials"].pop()
                    elif corruption == "result":
                        data["trials"][0]["result"]["measured"]["admitted"] += 1
                    else:
                        t = data["trials"][0]
                        t["result"]["scheduler"]["parallelismOverride"] = "8"
                        child = folder / t["directory"] / "results.json"
                        pilot.common.save(child, t["result"])
                        t["resultSha256"] = pilot.common.sha(child)
                    pilot.common.save(folder / "results.json", data)
                    manifest["resultSha256"] = pilot.common.sha(folder / "results.json")
                pilot.common.save(folder / "manifest.json", manifest)
                with self.assertRaises(ValueError):
                    pilot.validate_pass(folder, plan, "forward")

    def test_incomplete_study_and_changed_affinity_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            study = pilot.analysis.read(root / "study.json")
            broken = copy.deepcopy(study)
            broken["completed"].pop()
            pilot.common.save(root / "study.json", broken)
            with self.assertRaisesRegex(ValueError, "schedule"):
                pilot.audit(root)
            pilot.common.save(root / "study.json", study)
            host = root / "triplet-1-host.jsonl"
            host.write_text(host.read_text().replace('[0, 1, 2, 3, 4, 5, 6, 7]', '[0]'))
            with self.assertRaisesRegex(ValueError, "affinity"):
                pilot.audit(root)

    def test_invalid_affinity_fails_before_creating_output(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(pilot.os, "sched_getaffinity", return_value={0}):
            output = Path(tmp) / "absent"
            with self.assertRaisesRegex(ValueError, "eight distinct"):
                pilot.run(output, Path("java"), Path("jar"), [0] * 8)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
