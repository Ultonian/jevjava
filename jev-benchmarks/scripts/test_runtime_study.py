"""Fixed interleaved schedule, runtime attribution and historical audit contract tests."""
import collections
from pathlib import Path
import tempfile
import unittest

import test_executor_pilot as fixtures
from test_study import load

study = load("runtime_study", "runtime-study.py")


class RuntimeStudyTest(unittest.TestCase):
    def fixture(self, root):
        plan = fixtures.ExecutorPilotTest().fixture(
            root, runs=study.RUNS, expected_cells=study.immediate.CELLS, group="immediate")
        plan["runtimes"] = {str(f): {"feature": f, "expectedManifest": {
            "javaVersion": str(f) if f == 27 else f"{f}.0.1",
            "vmVersion": str(f) + "+1", "javaVendor": "Eclipse Adoptium"}}
            for f in study.FEATURES}
        state = study.pilot.analysis.read(root / "study.json")
        for run, completed in zip(study.RUNS, state["completed"]):
            folder = root / run["id"]
            data = study.pilot.analysis.read(folder / "results.json")
            # Known +10%/+20% runtime effects on each style; retain +10% async/sync.
            factor = {21: 1, 25: 1.1, 27: 1.2}[run["javaFeature"]]
            for t in data["trials"]:
                for label in ("warmup", "measured"):
                    c = t["result"][label]
                    n = round(c["admitted"] * factor)
                    for key in ("offered", "admitted", "completed", "httpAttempts", "completedInWindow"):
                        c[key] = n
                    c["outcomes"]["SUCCESS"] = n
                    c["latencyByOutcome"]["SUCCESS"]["recorded"] = n
                    c["latencyByOutcome"]["SUCCESS"]["buckets"] = [{"count": n}]
                    c["requestBytes"] = n * 100
                    c["successfulPerSecond"] *= factor
                path = folder / t["directory"] / "results.json"
                study.pilot.common.save(path, t["result"])
                t["resultSha256"] = study.pilot.common.sha(path)
            study.pilot.common.save(folder / "results.json", data)
            m = study.pilot.analysis.read(folder / "manifest.json")
            m.update(plan["runtimes"][str(run["javaFeature"])]["expectedManifest"])
            m["settings"]["protocol"] = "immediate-v1"
            m["resultSha256"] = study.pilot.common.sha(folder / "results.json")
            completed["resultSha256"] = m["resultSha256"]
            study.pilot.common.save(folder / "manifest.json", m)
        study.pilot.common.save(root / "plan.json", plan)
        state["planSha256"] = study.pilot.common.sha(root / "plan.json")
        study.pilot.common.save(root / "study.json", state)
        return plan

    def test_schedule_balances_runtime_positions_and_style_order(self):
        self.assertEqual(len(study.RUNS), 18)
        self.assertEqual(len(set(study.ORDERS)), 6)
        for f in study.FEATURES:
            self.assertEqual(collections.Counter(order.index(f) for order in study.ORDERS),
                             {0: 2, 1: 2, 2: 2})
            self.assertEqual(collections.Counter(r["order"] for r in study.RUNS
                                                 if r["javaFeature"] == f),
                             {"forward": 3, "reverse": 3})

            positions_and_styles = collections.Counter(
                (order.index(f), study.pilot.ORDERS[n]) for n, order in enumerate(study.ORDERS))
            self.assertEqual(set(positions_and_styles.values()), {1})

    def test_full_audit_and_known_within_round_comparisons(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            s = study.audit(root)
            for r in s["runtimes"].values():
                self.assertEqual(len(r["trials"]), 12)
                self.assertAlmostEqual(r["geometricAsyncVsSyncPercent"], 10)
            for c in s["sameRoundComparisons"]:
                self.assertEqual(len(c["rounds"]), 6)
                self.assertAlmostEqual(c["geometricPercent"], {25: 10, 27: 20}[c["jdk"]])

    def test_wrong_vendor_patch_or_fixture_cannot_pass_as_runtime_effect(self):
        for key, value in (("javaVendor", "Different Vendor"), ("javaVersion", "25.0.99"),
                           ("vmVersion", "25+999"), ("fixtures", {"changed": True})):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                self.fixture(root)
                path = root / "round-1-jdk25/manifest.json"
                m = study.pilot.analysis.read(path)
                m[key] = value
                study.pilot.common.save(path, m)
                with self.assertRaises(ValueError):
                    study.audit(root)

    def test_incomplete_round_and_reordered_schedule_are_rejected(self):
        for action in ("missing", "reordered"):
            with self.subTest(action=action), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                p = self.fixture(root)
                s = study.pilot.analysis.read(root / "study.json")
                if action == "missing":
                    p["runs"] = p["runs"][:-1]
                    s["completed"].pop()
                else:
                    p["runs"] = list(reversed(p["runs"]))
                    s["completed"].reverse()
                study.pilot.common.save(root / "plan.json", p)
                s["planSha256"] = study.pilot.common.sha(root / "plan.json")
                study.pilot.common.save(root / "study.json", s)
                with self.assertRaisesRegex(ValueError, "schedule"):
                    study.audit(root)

    def test_runtime_file_changes_are_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            (home / "bin").mkdir()
            java = home / "bin/java"
            java.write_text("original")
            runtimes = {"21": {"java": str(java),
                               "runtimeFiles": {"bin/java": study.pilot.common.sha(java)}}}
            study.verify_runtime_files(runtimes)
            java.write_text("changed")
            with self.assertRaisesRegex(ValueError, "changed"):
                study.verify_runtime_files(runtimes)
