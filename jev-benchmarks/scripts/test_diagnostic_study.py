"""Synthetic study validation only; ordinary tests never launch JFR workloads."""
import copy
import tempfile
import unittest
import zipfile
from pathlib import Path

import diagnostic_study as study
import test_executor_pilot as fixtures


class DiagnosticStudyTest(unittest.TestCase):
    def fixture(self, root):
        plan = fixtures.ExecutorPilotTest().fixture(root)
        config = Path(__file__).parent.parent / "src/main/resources/diagnostics/restricted-long.jfc"
        with zipfile.ZipFile(root / "benchmarks.jar", "w") as jar:
            jar.write(config, "diagnostics/restricted-long.jfc")
        config_hash, settings = study.profile(root)
        plan["jarSha256"] = study.common.sha(root / "benchmarks.jar")
        plan["runs"] = study.RUNS
        completed = []
        for i, run in enumerate(study.RUNS, 1):
            folder = root / run["id"]
            (root / f"triplet-{i}").rename(folder)
            (root / f"triplet-{i}-host.jsonl").rename(root / (run["id"] + "-host.jsonl"))
            trials = study.analysis.read(folder / "results.json")["trials"]
            trials.sort(key=lambda t: (t["cell"]["variant"] != "PLATFORM", t["cell"]["submission"] != "SYNC"))
            for t in trials:
                old = folder / t["directory"]
                cell = t["cell"]
                if cell["variant"] == "PLATFORM":
                    cell.update(control=True, variant="BASE")
                t["directory"] = study.pilot.cell_id(cell) + "-fork1"
                if old != folder / t["directory"]:
                    old.rename(folder / t["directory"])
                r = t["result"]
                r.update(cell=cell, mode=run["mode"])
                r["measured"].update(windowStart="2026-10-01T00:00:00Z", windowEnd="2026-10-01T00:00:30Z")
                if run["mode"] == "diagnostic-long":
                    jfr = folder / t["directory"] / "diagnostics.jfr"
                    jfr.write_bytes(b"synthetic recording hash fixture, not a JFR parser test")
                    r["diagnostics"] = {
                        "configurationSha256": config_hash, "settings": settings,
                        "recordingEventCounts": {"jdk.ExecutionSample": 1},
                        "eventCounts": {"jdk.ExecutionSample": 1},
                        "recordingSha256": study.common.sha(jfr), "recordingBytes": jfr.stat().st_size,
                        "nearRetentionLimit": False, "recordingDataLossBytes": 0,
                        "windowStart": r["measured"]["windowStart"], "windowEnd": r["measured"]["windowEnd"],
                        "executionSamples": {"countsByRole": {"jdk.ExecutionSample/sdk": 1},
                            "countsByThreadKind": {"jdk.ExecutionSample/virtual": 1},
                            "retainedStacks": [{"count": 1}], "unretainedStackSamples": 0}}
                child = folder / t["directory"] / "results.json"
                study.common.save(child, r)
                t["resultSha256"] = study.common.sha(child)
            study.common.save(folder / "results.json", {"trials": trials})
            m = study.analysis.read(folder / "manifest.json")
            m.update(benchmarkJarSha256=plan["jarSha256"], resultSha256=study.common.sha(folder / "results.json"))
            m["settings"].update(mode=run["mode"], group="representative", cells=[t["cell"] for t in trials])
            study.common.save(folder / "manifest.json", m)
            completed.append({"id": run["id"], "resultSha256": m["resultSha256"],
                              "observedProcessIds": ["1", "2", "3", "4"]})
        study.common.save(root / "plan.json", plan)
        study.common.save(root / "study.json", {"status": "complete", "completed": completed,
                          "planSha256": study.common.sha(root / "plan.json")})
        return plan

    def test_complete_matched_study(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            result = study.audit(root)
            self.assertEqual(len(result["trials"]), 18)
            self.assertEqual(len(result["contrasts"]), 9)
            self.assertEqual(set(result["geometricRecordedVsUnrecordedPercent"].values()), {0})

    def test_rejects_recording_loss_window_and_event_contamination(self):
        for corruption in ("loss", "cap", "window", "event", "samples", "control"):
            with self.subTest(corruption=corruption), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                plan = self.fixture(root)
                run = study.RUNS[1 if corruption == "control" else 0]
                folder = root / run["id"]
                data = study.analysis.read(folder / "results.json")
                t = data["trials"][0]
                d = t["result"].get("diagnostics", {})
                if corruption == "loss":
                    d["recordingDataLossBytes"] = 12
                elif corruption == "cap":
                    d["nearRetentionLimit"] = True
                elif corruption == "window":
                    d["windowStart"] = "wrong"
                elif corruption == "event":
                    d["recordingEventCounts"]["jdk.InitialEnvironmentVariable"] = 1
                elif corruption == "samples":
                    d["executionSamples"]["unretainedStackSamples"] = 1
                else:
                    t["result"]["diagnostics"] = copy.deepcopy(d)
                child = folder / t["directory"] / "results.json"
                study.common.save(child, t["result"])
                t["resultSha256"] = study.common.sha(child)
                study.common.save(folder / "results.json", data)
                m = study.analysis.read(folder / "manifest.json")
                m["resultSha256"] = study.common.sha(folder / "results.json")
                study.common.save(folder / "manifest.json", m)
                with self.assertRaises(ValueError):
                    study.validate_pass(folder, plan, run)


if __name__ == "__main__":
    unittest.main()
