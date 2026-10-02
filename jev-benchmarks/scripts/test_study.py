"""Fast stdlib tests; no benchmark JVMs, builds or network requests."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


analysis = load("analysis", "analyse-study.py")
runner = load("runner", "run-study.py")


class StudyTest(unittest.TestCase):
    def make_study(self, root):
        cpus = [0]
        plan = {"sourceCommit": "source", "cpus": cpus, "runs": []}
        completed = []
        for jdk in (21, 25):
            for arm, rep in (("A", 1), ("B", 1), ("B", 2), ("A", 2), ("A", 3), ("A", 4), ("A", 5)):
                rid = f"jdk{jdk}-{arm}{rep}"
                plan["runs"].append({"id": rid, "jdk": jdk, "arm": arm,
                                     "replicate": rep, "jarSha256": "jar"})
                folder = root / rid
                folder.mkdir()
                trials = []
                for cell in analysis.CELLS:
                    c = {"control": cell.startswith("control"), "submission": cell.split("-")[1].upper(),
                         "concurrency": 8, "variant": "BASE"}
                    for fork in (1, 2, 3):
                        inside = 100 * rep + fork
                        n = inside + 1
                        cohort = {"admitted": n, "offered": n, "completed": n, "httpAttempts": n,
                                  "completedInWindow": inside, "drainCompletions": 1,
                                  "unfinished": 0, "retries": 0, "forcedCleanup": False,
                                  "serverDrained": True, "observersDrained": True,
                                  "outcomes": {"SUCCESS": n},
                                  "latencyByOutcome": {"SUCCESS": {"recorded": n, "overflow": 0,
                                      "buckets": [{"count": n}], "p50Micros": 5, "p99Micros": 10}},
                                  "serverBefore": {"rejectedTasks": 0, "ioFailures": 0},
                                  "serverAfter": {"rejectedTasks": 0, "ioFailures": 0,
                                      "activeHandlers": 0, "protocol": "HTTP/1.1"},
                                  "serverMeasurement": {"executorQueueNanos": 100, "executorTasks": n}}
                        result = {"status": "complete", "mode": "baseline", "cell": c,
                                  "warmup": dict(cohort, windowNanos=10_000_000_000,
                                                 successfulPerSecond=inside / 10),
                                  "measured": dict(cohort, windowNanos=30_000_000_000,
                                                   successfulPerSecond=inside / 30)}
                        directory = f"{cell}-fork{fork}"
                        child = folder / directory
                        child.mkdir()
                        runner.save(child / "results.json", result)
                        trials.append({"cell": c, "fork": fork, "directory": directory,
                                       "result": result, "resultSha256": runner.sha(child / "results.json")})
                runner.save(folder / "results.json", {"trials": trials})
                result_hash = runner.sha(folder / "results.json")
                manifest = {k: None for k in analysis.COMMON}
                manifest.update(status="complete", gitDirty=False, gitCommit="source",
                                benchmarkJarSha256="jar", availableProcessors=1,
                                javaVersion=f"{jdk}.0", resultSha256=result_hash,
                                settings={"mode": "baseline", "group": "representative", "forks": 3,
                                          "warmupMillis": 10000, "measurementMillis": 30000})
                runner.save(folder / "manifest.json", manifest)
                masks = {str(i): cpus for i in range(10)}
                (root / (rid + "-host.jsonl")).write_text(json.dumps({"processAffinities": masks, "platformProfile": "performance",
                    "boost": "1", "pstateStatus": "active", "powerOnline": {"AC": "1"},
                    "frequencyPolicies": {"0": {"scaling_governor": "performance", "scaling_cur_freq": str(rep)}}}) + "\n")
                runner.save(root / (rid + "-launch.json"), {})
                completed.append({"id": rid, "resultSha256": result_hash, "observedProcessIds": sorted(masks)})
        runner.save(root / "plan.json", plan)
        runner.save(root / "study.json", {"status": "complete", "completed": completed,
                                          "planSha256": runner.sha(root / "plan.json")})

    def test_complete_study_uses_five_passes_and_detects_mismatches(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.make_study(root)
            summary, rows = analysis.analyse(root)
            self.assertEqual(126, len(rows))
            self.assertEqual(6, len(summary["baseline"]))
            for r in summary["baseline"]:
                self.assertEqual(5, r["n"])
                self.assertAlmostEqual(302 / 30, r["mean"])
                self.assertEqual([0, 0], r["aaPairedChangePercent"])
            manifest_path = root / "jdk21-B1/manifest.json"
            original = analysis.read(manifest_path)
            for key, value in (("javaVersion", "21.other"), ("configurationSha256", "other"),
                               ("gitDirty", True), ("benchmarkJarSha256", "other")):
                with self.subTest(key=key):
                    runner.save(manifest_path, dict(original, **{key: value}))
                    with self.assertRaises(ValueError):
                        analysis.analyse(root)
            runner.save(manifest_path, original)
            host_path = root / "jdk21-B1-host.jsonl"
            original_host = host_path.read_text()
            changed_host = json.loads(original_host)
            changed_host["boost"] = "0"
            host_path.write_text(json.dumps(changed_host) + "\n")
            with self.assertRaisesRegex(ValueError, "Host governor/power conditions changed"):
                analysis.analyse(root)
            host_path.write_text(original_host)
            raw = root / "jdk21-A1/control-async-8-base-fork1/results.json"
            raw.write_text(raw.read_text() + " ")
            with self.assertRaisesRegex(ValueError, "Trial hash mismatch"):
                analysis.analyse(root)

    def test_sample_statistics(self):
        result = analysis.describe([10, 20, 30, 40, 50])
        self.assertEqual(5, result["n"])
        self.assertEqual(30, result["mean"])
        self.assertAlmostEqual(250 ** .5, result["sampleSd"])
        self.assertAlmostEqual(100 * 250 ** .5 / 30, result["cvPercent"])

    def test_invalid_statistics(self):
        for values in ([1], [1, float("nan")], [1, float("inf")], [0, 1], [-1, 2]):
            with self.subTest(values=values), self.assertRaises(ValueError):
                analysis.describe(values)

    def test_drain_is_excluded_from_rate_but_included_in_histogram(self):
        c = {"windowNanos": 1_000_000_000, "admitted": 10, "offered": 10,
             "completed": 10, "httpAttempts": 10, "completedInWindow": 8,
             "drainCompletions": 2, "unfinished": 0, "retries": 0,
             "forcedCleanup": False, "serverDrained": True, "observersDrained": True,
             "outcomes": {"SUCCESS": 10}, "successfulPerSecond": 8,
             "latencyByOutcome": {"SUCCESS": {"recorded": 10, "overflow": 0,
                                               "buckets": [{"count": 10}]}},
             "serverBefore": {"rejectedTasks": 0, "ioFailures": 0},
             "serverAfter": {"rejectedTasks": 0, "ioFailures": 0,
                             "activeHandlers": 0, "protocol": "HTTP/1.1"}}
        analysis.validate_cohort(c, 1_000_000_000)
        for key, value in (("successfulPerSecond", 10), ("completed", 9),
                           ("serverDrained", False), ("httpAttempts", 11)):
            invalid = dict(c, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                analysis.validate_cohort(invalid, 1_000_000_000)

    @unittest.skipUnless(hasattr(os, "sched_getaffinity"), "Linux affinity runner")
    def test_preflight_rejects_duplicate_ids_and_bad_hash_before_launch(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            jar = root / "jar"
            jar.write_bytes(b"test-only stub")
            row = {"id": "A1", "jar": str(jar), "jarSha256": runner.sha(jar),
                   "snapshot": str(root)}
            plan = {"cpus": [min(os.sched_getaffinity(0))], "runs": [row, row]}
            path = root / "plan.json"
            for invalid in (plan, dict(plan, runs=[dict(row, jarSha256="wrong")]),
                            dict(plan, runs=[dict(row, id="../escape")]),
                            dict(plan, runs=[dict(row, id="")])):
                path.write_text(json.dumps(invalid))
                with patch.object(runner.subprocess, "Popen") as launch:
                    with self.assertRaises(ValueError):
                        runner.run(path, root / "results")
                    launch.assert_not_called()
                    self.assertFalse((root / "results").exists())

    @unittest.skipUnless(hasattr(os, "sched_getaffinity"), "Linux affinity runner")
    def test_plan_hash_uses_archived_bytes_and_existing_output_is_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            # Noncanonical whitespace in the input must not invalidate the archived plan.
            path = root / "plan.json"
            path.write_text(json.dumps({"cpus": [min(os.sched_getaffinity(0))], "runs": []}))
            output = root / "study"
            runner.run(path, output)
            state = analysis.read(output / "study.json")
            self.assertEqual(runner.sha(output / "plan.json"), state["planSha256"])
            self.assertNotEqual(runner.sha(path), state["planSha256"])
            with self.assertRaises(FileExistsError):
                runner.run(path, output)

    @unittest.skipUnless(hasattr(os, "sched_getaffinity"), "Linux affinity runner")
    def test_launch_failure_records_failed_study(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            jar = root / "jar"
            jar.write_bytes(b"not executed")
            plan = {"sourceCommit": "source", "cpus": [min(os.sched_getaffinity(0))],
                    "taskset": "taskset", "runs": [{"id": "A1", "snapshot": str(root),
                        "java": "java", "jar": str(jar), "jarSha256": runner.sha(jar)}]}
            path = root / "plan.json"
            runner.save(path, plan)
            output = root / "study"
            with patch.object(runner.subprocess, "check_output", side_effect=[b"", "source\n"]), \
                    patch.object(runner.subprocess, "Popen", side_effect=OSError("synthetic launch failure")), \
                    patch.object(runner, "host", return_value={}):
                with self.assertRaisesRegex(OSError, "synthetic launch failure"):
                    runner.run(path, output)
            state = analysis.read(output / "study.json")
            self.assertEqual("failed", state["status"])
            self.assertEqual([], state["completed"])

    def test_incomplete_and_tampered_studies_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "plan.json").write_text("{}\n")
            for state in ({"status": "failed"}, {"status": "complete", "planSha256": "wrong"}):
                (root / "study.json").write_text(json.dumps(state))
                with self.assertRaises(ValueError):
                    analysis.analyse(root)


if __name__ == "__main__":
    unittest.main()
