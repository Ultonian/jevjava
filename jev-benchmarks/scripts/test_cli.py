"""CLI compatibility and preflight errors without launching measured workloads."""
import argparse
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import study_support


class CliTest(unittest.TestCase):
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
                    result = subprocess.run(
                        [sys.executable, "-B", str(Path(__file__).with_name(name + ".py")),
                         "run", str(output)], capture_output=True, text=True, check=False, timeout=10)
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
