"""Synthetic transport study validation; no measurement processes."""
import tempfile
import unittest
from pathlib import Path

import immediate_study as study
import test_executor_pilot as fixtures


class ImmediateStudyTest(unittest.TestCase):
    def fixture(self, root):
        return fixtures.ExecutorPilotTest().fixture(
            root, runs=study.RUNS, expected_cells=study.CELLS, group="immediate")

    def test_full_audit_reproduces_known_pair_effect_and_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            result = study.audit(root)
            self.assertEqual(len(result["trials"]), 12)
            self.assertAlmostEqual(result["geometricAsyncVsSyncPercent"], 10)
            self.assertEqual([r["order"] for r in result["contrasts"]], list(study.pilot.ORDERS))

    def test_synthetic_accounting_must_not_hide_wrong_bytes_or_real_server(self):
        for corrupt in ("bytes", "server", "transport", "missing"):
            with self.subTest(corrupt=corrupt), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                plan = self.fixture(root)
                folder = root / "pair-1"
                data = study.pilot.analysis.read(folder / "results.json")
                t = data["trials"][0]
                c = t["result"]["measured"]
                if corrupt == "bytes":
                    c["requestBytes"] -= 1
                elif corrupt == "server":
                    c["serverAfter"] = {}
                elif corrupt == "transport":
                    c["transportKind"] = "HTTP"
                else:
                    data["trials"].pop()
                child = folder / t["directory"] / "results.json"
                study.pilot.common.save(child, t["result"])
                t["resultSha256"] = study.pilot.common.sha(child)
                study.pilot.common.save(folder / "results.json", data)
                m = study.pilot.analysis.read(folder / "manifest.json")
                m["resultSha256"] = study.pilot.common.sha(folder / "results.json")
                study.pilot.common.save(folder / "manifest.json", m)
                with self.assertRaises(ValueError):
                    study.validate(folder, plan, "forward")

    def test_explicit_jdk25_accepts_matching_runtime_and_rejects_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            plan = self.fixture(root)
            plan["javaFeature"] = 25
            study.pilot.common.save(root / "plan.json", plan)
            state = study.pilot.analysis.read(root / "study.json")
            state["planSha256"] = study.pilot.common.sha(root / "plan.json")
            study.pilot.common.save(root / "study.json", state)
            with self.assertRaisesRegex(ValueError, "Runtime mismatch"):
                study.audit(root)
            for run in study.RUNS:
                path = root / run["id"] / "manifest.json"
                manifest = study.pilot.analysis.read(path)
                manifest["javaVersion"] = "25.0.4.1"
                study.pilot.common.save(path, manifest)
            self.assertEqual(len(study.audit(root)["trials"]), 12)
            plan["javaFeature"] = 24
            with self.assertRaisesRegex(ValueError, "Expected JDK"):
                study.validate(root / "pair-1", plan, "forward")

    def test_truncated_schedule_is_not_a_completed_study(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            p = study.pilot.analysis.read(root / "plan.json")
            p["runs"].pop()
            study.pilot.common.save(root / "plan.json", p)
            s = study.pilot.analysis.read(root / "study.json")
            s["completed"].pop()
            s["planSha256"] = study.pilot.common.sha(root / "plan.json")
            study.pilot.common.save(root / "study.json", s)
            with self.assertRaisesRegex(ValueError, "schedule"):
                study.audit(root)
