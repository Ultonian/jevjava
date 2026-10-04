"""CLI compatibility and preflight errors without launching measured workloads."""
import argparse
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import study_support


class CliTest(unittest.TestCase):
    @unittest.skipUnless(sys.version_info >= (3, 11), "Safe-path mode requires Python 3.11+")
    def test_entry_points_work_with_safe_path_from_an_unrelated_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            for name in ("run-study", "analyse-study", "executor-pilot", "immediate-study",
                         "diagnostic-study", "runtime-study"):
                for flag in (True, False):
                    with self.subTest(name=name, flag=flag):
                        command = [sys.executable, "-B"]
                        env = dict(os.environ)
                        env.pop("PYTHONSAFEPATH", None)
                        env.pop("PYTHONPATH", None)
                        if flag:
                            command.append("-P")
                        else:
                            env["PYTHONSAFEPATH"] = "1"
                        command.extend([str(Path(__file__).with_name(name + ".py").resolve()), "--help"])
                        result = subprocess.run(command, cwd=temp, env=env, capture_output=True,
                                                text=True, check=False, timeout=10)
                        self.assertEqual(result.returncode, 0, result.stderr)
                        self.assertIn("usage:", result.stdout)

    def test_documented_entry_points_keep_help_and_do_not_start_a_workload(self):
        for name in ("run-study", "analyse-study", "executor-pilot", "immediate-study",
                     "diagnostic-study", "runtime-study"):
            with self.subTest(name=name):
                result = subprocess.run(
                    [sys.executable, "-B", str(Path(__file__).with_name(name + ".py")), "--help"],
                    capture_output=True, text=True, check=False, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("usage:", result.stdout)

    def test_run_requires_explicit_runtime_jar_and_cpu_selection_before_writing(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "must-not-exist"
            for name in ("executor-pilot", "immediate-study", "diagnostic-study", "runtime-study"):
                with self.subTest(name=name):
                    # Supply everything except --cpus so this catches removal of that requirement.
                    runtime = ["--runtimes", str(Path(temp) / "runtimes.json")] if name == "runtime-study" else [
                        "--java", str(Path(temp) / "java")]
                    result = subprocess.run(
                        [sys.executable, "-B", str(Path(__file__).with_name(name + ".py")),
                         "run", str(output), "--jar", str(Path(temp) / "benchmarks.jar"), *runtime],
                        capture_output=True, text=True, check=False, timeout=10)
                    self.assertEqual(result.returncode, 2)
                    self.assertIn("run requires", result.stderr)
                    self.assertIn("--cpus", result.stderr)
                    self.assertNotIn("Traceback", result.stderr)
                    self.assertFalse(output.exists())

    def test_cpu_parser_rejects_ambiguous_or_invalid_masks(self):
        self.assertEqual(study_support.parse_cpus("0,1,2,3,4,5,6,7"), list(range(8)))
        for value in ("", "0,1", "0,1,2,3,4,5,6,6", "-1,1,2,3,4,5,6,7", "0,x"):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                study_support.parse_cpus(value)

    def test_execution_explains_platform_requirement(self):
        with patch.object(study_support.sys, "platform", "darwin"):
            with self.assertRaisesRegex(ValueError, "requires Linux"):
                study_support.require_linux()
