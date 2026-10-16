"""Regression tests for invalid benchmark evidence and the unchanged relative gates."""

import copy
import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("native_gate", Path(__file__).with_name("check-native-benchmarks.py"))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


def report():
    return {
        "context": {
            "payload": {"runId": "fresh-run"},
            "build": {"brand": "google", "device": "redfin", "fingerprint": "google/redfin/release", "model": "Pixel 5"},
        },
        "benchmarks": [
            {
                "name": name,
                "className": GATE.BENCHMARK_CLASS,
                "warmupIterations": 4,
                "repeatIterations": 1,
                "metrics": {"timeNs": {"median": median, "runs": [median - 1, median, median + 1]}},
            }
            for name, median in [("regionBatched", 10), ("regionPerRow", 20), ("decryptBatched", 30), ("decryptPerRow", 40)]
        ],
    }


class NativeBenchmarkGateTest(unittest.TestCase):
    def test_both_valid_pairs_pass(self):
        self.assertEqual(2, len(GATE.check_report(report(), "fresh-run")))

    def test_equality_retains_original_non_strict_gate(self):
        data = report()
        data["benchmarks"][0]["metrics"] = copy.deepcopy(data["benchmarks"][1]["metrics"])
        GATE.check_report(data, "fresh-run")

    def test_each_slower_batch_fails(self):
        for index in (0, 2):
            with self.subTest(index=index):
                data = report()
                data["benchmarks"][index]["metrics"]["timeNs"] = {"median": 100, "runs": [99, 100, 101]}
                with self.assertRaisesRegex(ValueError, "batch must not cost more"):
                    GATE.check_report(data, "fresh-run")

    def test_stale_report_fails(self):
        with self.assertRaisesRegex(ValueError, "different run"):
            GATE.check_report(report(), "another-run")

    def test_emulator_report_fails(self):
        data = report()
        data["context"]["build"]["device"] = "sdk_gphone64_arm64"
        with self.assertRaisesRegex(ValueError, "physical"):
            GATE.check_report(data, "fresh-run")

    def test_missing_duplicate_and_foreign_cases_fail(self):
        for kind in ("missing", "duplicate", "foreign"):
            with self.subTest(kind=kind):
                data = report()
                if kind == "missing":
                    data["benchmarks"].pop()
                elif kind == "duplicate":
                    data["benchmarks"].append(copy.deepcopy(data["benchmarks"][0]))
                else:
                    data["benchmarks"][0]["className"] = "another.Benchmark"
                with self.assertRaises(ValueError):
                    GATE.check_report(data, "fresh-run")

    def test_invalid_raw_measurements_fail(self):
        for value in (float("nan"), float("inf"), 0, -1, True, "10"):
            with self.subTest(value=value):
                data = report()
                data["benchmarks"][0]["metrics"]["timeNs"]["runs"][0] = value
                with self.assertRaises(ValueError):
                    GATE.check_report(data, "fresh-run")

    def test_single_sample_and_inconsistent_median_fail(self):
        for metric in ({"median": 10, "runs": [10]}, {"median": 10, "runs": [20, 30, 40]}):
            with self.subTest(metric=metric):
                data = report()
                data["benchmarks"][0]["metrics"]["timeNs"] = metric
                with self.assertRaises(ValueError):
                    GATE.check_report(data, "fresh-run")

    def test_absent_warmup_fails(self):
        data = report()
        data["benchmarks"][0]["warmupIterations"] = 0
        with self.assertRaises(ValueError):
            GATE.check_report(data, "fresh-run")


if __name__ == "__main__":
    unittest.main()
