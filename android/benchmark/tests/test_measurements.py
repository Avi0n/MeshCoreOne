"""AndroidOnly: WP-504 Synthetic parser controls; no device measurements or budgets."""

from contextlib import redirect_stderr, redirect_stdout
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import statistics
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / "measurements.py"
SPEC = importlib.util.spec_from_file_location("wp504_measurements", SCRIPT)
measurements = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(measurements)
COMMIT = "a" * 40
DATASET = "b" * 64
APK = b"synthetic parser input; deliberately not an installable APK"
SHA = hashlib.sha256(APK).hexdigest()


def metric(runs, sampled=False):
    values = [value for run in runs for value in run] if sampled else runs
    if sampled:
        return {"runs": runs, **{f"P{p}": measurements.percentile(values, p) for p in (50, 90, 95, 99)}}
    return {
        "runs": runs, "minimum": min(values), "maximum": max(values), "median": statistics.median(values),
        "coefficientOfVariation": statistics.stdev(values) / statistics.mean(values),
    }


def synthetic_results():
    cases = []
    for name in sorted(measurements.EXPECTED):
        startup = name.startswith("coldStartup")
        cases.append({
            "name": name, "className": measurements.CLASS, "params": {},
            "repeatIterations": 10, "warmupIterations": 0, "totalRunTimeNs": 1_000_000,
            "thermalThrottleSleepSeconds": 0, "profilerOutputs": [],
            "metrics": {"timeToInitialDisplayMs": metric(list(range(1, 11)))} if startup else {
                "frameCount": metric([3] * 10),
            },
            "sampledMetrics": {} if startup else {
                "frameDurationCpuMs": metric([[1, 2, 3]] * 10, sampled=True),
                "frameOverrunMs": metric([[-3, -2, -1]] * 10, sampled=True),
            },
        })
    return {
        "context": {
            "build": {
                "brand": "SYNTHETIC", "device": "parser-only", "fingerprint": "not-a-device",
                "id": "fixture", "model": "fixture", "type": "user", "version": {"codename": "REL", "sdk": 37},
            },
            "cpuCoreCount": 8, "cpuLocked": False, "cpuMaxFreqHz": 1, "memTotalBytes": 1,
            "sustainedPerformanceModeEnabled": False, "artMainlineVersion": -1,
            "osCodenameAbbreviated": "REL", "compilationMode": "speed",
            "payload": {
                "wp504.commit": COMMIT, "wp504.dataset": "wp504-v1",
                "wp504.datasetSha256": DATASET, "wp504.apkSha256": SHA,
            },
        },
        "benchmarks": cases,
    }


class MeasurementsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.results = synthetic_results()
        self.identities = {(measurements.CLASS, name) for name in measurements.EXPECTED}
        self.xml = self.directory / "synthetic-junit.xml"
        root = ET.Element("testsuite", tests="9", failures="0", errors="0", skipped="0")
        for name in sorted(measurements.EXPECTED):
            ET.SubElement(root, "testcase", classname=measurements.CLASS, name=name, time="0.1")
        self.tree = ET.ElementTree(root)
        self.tree.write(self.xml, encoding="utf-8", xml_declaration=True)

    def summarize(self, results=None, **kwargs):
        arguments = {
            "commit": COMMIT, "target_sha256": SHA, "dataset_sha256": DATASET, "device_kind": "emulator",
        }
        arguments.update(kwargs)
        return measurements.summarize(self.results if results is None else results, self.identities, **arguments)

    def test_complete_matrix_preserves_raw_data_without_acceptance(self):
        result = self.summarize()
        self.assertEqual(9, len(result["benchmarks"]))
        self.assertFalse(result["budget_evaluated"])
        self.assertFalse(result["physical_device_verified"])
        self.assertEqual("emulator", result["device_kind_reported_by_operator"])
        self.assertEqual(self.results["context"], result["recorded_context"])
        self.assertFalse(result["profiles_collected_or_provenance_verified"])
        self.assertFalse(result["anr_battery_strictmode_or_native_runtime_verified"])
        frame = next(case for case in result["benchmarks"] if case["name"].startswith("nativeMapPan"))
        self.assertEqual([[-3, -2, -1]] * 10, frame["metrics"]["frameOverrunMs"]["raw_runs"])
        self.assertEqual("pooled frame samples", frame["metrics"]["frameOverrunMs"]["aggregation"])
        self.assertEqual(30, frame["metrics"]["frameOverrunMs"]["count"])
        self.assertEqual(-2, frame["metrics"]["frameOverrunMs"]["P50"])
        self.assertEqual(10, len(frame["metrics"]["frameOverrunMs"]["per_iteration"]))
        self.assertTrue(all(run["count"] == 3 for run in frame["metrics"]["frameOverrunMs"]["per_iteration"]))

    def test_percentiles_match_pinned_androidx_linear_interpolation(self):
        self.assertEqual(5.5, measurements.percentile(list(range(1, 11)), 50))
        self.assertAlmostEqual(9.1, measurements.percentile(list(range(1, 11)), 90))
        self.assertEqual(7, measurements.percentile([7], 99))

    def test_missing_duplicate_foreign_or_wrong_mode_cases_fail(self):
        for change in ("missing", "duplicate", "foreign", "wrong-mode"):
            with self.subTest(change=change):
                result = copy.deepcopy(self.results)
                if change == "missing":
                    result["benchmarks"].pop()
                elif change == "duplicate":
                    result["benchmarks"][0] = result["benchmarks"][1]
                elif change == "foreign":
                    result["benchmarks"][0]["className"] = "foreign.Class"
                else:
                    result["benchmarks"][0]["name"] = "coldStartup[Partial]"
                with self.assertRaises(ValueError):
                    self.summarize(result)

    def test_payload_bindings_are_required_and_exact(self):
        for key in self.results["context"]["payload"]:
            with self.subTest(key=key):
                result = copy.deepcopy(self.results)
                del result["context"]["payload"][key]
                with self.assertRaisesRegex(ValueError, "Run binding mismatch"):
                    self.summarize(result)
        for argument in ("commit", "target_sha256", "dataset_sha256"):
            with self.subTest(argument=argument), self.assertRaises(ValueError):
                self.summarize(**{argument: "c" * (40 if argument == "commit" else 64)})

    def test_invalid_context_or_operator_labels_fail(self):
        for sdk in (30, True, "37", None):
            with self.subTest(sdk=sdk):
                self.results["context"]["build"]["version"]["sdk"] = sdk
                with self.assertRaises(ValueError):
                    self.summarize()
        self.results = synthetic_results()
        del self.results["context"]["build"]["fingerprint"]
        with self.assertRaises(ValueError):
            self.summarize()
        with self.assertRaises(ValueError):
            self.summarize(device_kind="unknown")

    def test_reported_physical_label_never_becomes_verified(self):
        result = self.summarize(device_kind="physical")
        self.assertEqual("physical", result["device_kind_reported_by_operator"])
        self.assertFalse(result["physical_device_verified"])

    def test_missing_context_fails_but_published_unknown_sentinels_are_preserved(self):
        for key in ("cpuCoreCount", "cpuMaxFreqHz", "memTotalBytes", "artMainlineVersion", "cpuLocked",
                    "sustainedPerformanceModeEnabled", "osCodenameAbbreviated", "compilationMode"):
            with self.subTest(key=key):
                self.results = synthetic_results()
                del self.results["context"][key]
                with self.assertRaises(ValueError):
                    self.summarize()
        self.results = synthetic_results()
        for key in ("cpuMaxFreqHz", "memTotalBytes", "artMainlineVersion"):
            self.results["context"][key] = -1
        result = self.summarize()
        self.assertEqual(-1, result["recorded_context"]["cpuMaxFreqHz"])

    def test_wrong_iteration_counts_and_raw_shapes_fail(self):
        for value in (0, 9, 11, True, "10", None):
            with self.subTest(value=value):
                self.results["benchmarks"][0]["repeatIterations"] = value
                with self.assertRaises(ValueError):
                    self.summarize()
        for runs in ([], [1] * 9, [1] * 11, "ten"):
            with self.subTest(runs=runs):
                self.results = synthetic_results()
                self.results["benchmarks"][0]["metrics"]["timeToInitialDisplayMs"]["runs"] = runs
                with self.assertRaises(ValueError):
                    self.summarize()

    def test_missing_or_additional_warmup_and_unexpected_named_parameters_fail(self):
        for value in (None, True, 1, "0"):
            with self.subTest(warmup=value):
                self.results["benchmarks"][0]["warmupIterations"] = value
                with self.assertRaisesRegex(ValueError, "warmup"):
                    self.summarize()
        self.results = synthetic_results()
        self.results["benchmarks"][0]["params"] = {"compilation": "Full"}
        with self.assertRaisesRegex(ValueError, "parameters"):
            self.summarize()

    def test_nonfinite_boolean_string_negative_and_oversized_values_fail(self):
        for value in (float("nan"), float("inf"), True, "1", None, -1, 0, 10**400):
            with self.subTest(value=value):
                self.results = synthetic_results()
                self.results["benchmarks"][0]["metrics"]["timeToInitialDisplayMs"]["runs"][0] = value
                with self.assertRaises(ValueError):
                    self.summarize()

    def test_missing_frame_metrics_empty_samples_and_invalid_frame_counts_fail(self):
        for change in ("missing", "empty", "boolean", "fractional", "negative-duration"):
            with self.subTest(change=change):
                self.results = synthetic_results()
                frame = next(case for case in self.results["benchmarks"] if case["name"].startswith("nativeMapPan"))
                if change == "missing":
                    del frame["sampledMetrics"]["frameOverrunMs"]
                elif change == "empty":
                    frame["sampledMetrics"]["frameOverrunMs"]["runs"][0] = []
                elif change == "boolean":
                    frame["sampledMetrics"]["frameDurationCpuMs"]["runs"][0][0] = True
                elif change == "fractional":
                    frame["metrics"]["frameCount"] = metric([1.5] * 10)
                else:
                    frame["sampledMetrics"]["frameDurationCpuMs"]["runs"][0][0] = -1
                with self.assertRaises(ValueError):
                    self.summarize()

    def test_summary_tampering_new_metric_and_thermal_delay_fail(self):
        for change in ("scalar-summary", "frame-summary", "new-metric", "thermal-delay"):
            with self.subTest(change=change):
                self.results = synthetic_results()
                if change == "scalar-summary":
                    self.results["benchmarks"][0]["metrics"]["timeToInitialDisplayMs"]["median"] = 999
                elif change == "frame-summary":
                    frame = next(case for case in self.results["benchmarks"] if case["name"].startswith("nativeMapPan"))
                    frame["sampledMetrics"]["frameDurationCpuMs"]["P99"] = 999
                elif change == "new-metric":
                    self.results["benchmarks"][0]["metrics"]["unknownMs"] = metric([1] * 10)
                else:
                    self.results["benchmarks"][0]["thermalThrottleSleepSeconds"] = 1
                with self.assertRaises(ValueError):
                    self.summarize()

    def test_individually_valid_scalar_frame_count_must_match_samples(self):
        frame = next(case for case in self.results["benchmarks"] if case["name"].startswith("nativeMapPan"))
        frame["metrics"]["frameCount"] = metric([999] * 10)
        with self.assertRaisesRegex(ValueError, "Frame cardinality"):
            self.summarize()

    def test_individually_valid_cpu_and_overrun_samples_need_matching_iteration_counts(self):
        for change in ("cpu-extra", "overrun-extra", "redistributed"):
            with self.subTest(change=change):
                self.results = synthetic_results()
                frame = next(case for case in self.results["benchmarks"] if case["name"].startswith("nativeMapPan"))
                key = "frameOverrunMs" if change == "overrun-extra" else "frameDurationCpuMs"
                runs = frame["sampledMetrics"][key]["runs"]
                runs[0] = [*runs[0], -1 if key == "frameOverrunMs" else 1]
                if change == "redistributed":
                    runs[1] = runs[1][:-1]
                frame["sampledMetrics"][key] = metric(runs, sampled=True)
                with self.assertRaisesRegex(ValueError, "Frame cardinality"):
                    self.summarize()

    def test_consistent_different_frame_counts_between_iterations_are_preserved(self):
        counts = list(range(1, 11))
        for frame in self.results["benchmarks"]:
            if frame["name"].startswith("coldStartup"):
                continue
            frame["metrics"]["frameCount"] = metric(counts)
            frame["sampledMetrics"]["frameDurationCpuMs"] = metric(
                [list(range(1, count + 1)) for count in counts], sampled=True,
            )
            frame["sampledMetrics"]["frameOverrunMs"] = metric(
                [[-value for value in range(1, count + 1)] for count in counts], sampled=True,
            )
        result = self.summarize()
        for frame in result["benchmarks"]:
            if frame["name"].startswith("coldStartup"):
                continue
            self.assertEqual(counts, frame["metrics"]["frameCount"]["raw_runs"])
            for key in ("frameDurationCpuMs", "frameOverrunMs"):
                self.assertEqual(counts, [run["count"] for run in frame["metrics"][key]["per_iteration"]])
                self.assertEqual(sum(counts), frame["metrics"][key]["count"])
            self.assertTrue(all(value < 0 for run in frame["metrics"]["frameOverrunMs"]["raw_runs"] for value in run))

    def test_junit_exact_positive_identity_and_aggregate_wrapper(self):
        self.assertEqual(self.identities, measurements.junit_cases(self.xml))
        wrapper = ET.Element("testsuites")
        wrapper.append(self.tree.getroot())
        ET.ElementTree(wrapper).write(self.xml)
        self.assertEqual(self.identities, measurements.junit_cases(self.xml))

    def test_junit_failure_error_skips_and_false_declared_counts_fail(self):
        for tag in ("failure", "error", "skipped"):
            for declared in (False, True):
                with self.subTest(tag=tag, declared=declared):
                    tree = copy.deepcopy(self.tree)
                    ET.SubElement(tree.getroot().find("testcase"), tag)
                    if declared:
                        tree.getroot().set({"failure": "failures", "error": "errors", "skipped": "skipped"}[tag], "1")
                    tree.write(self.xml)
                    with self.assertRaises(ValueError):
                        measurements.junit_cases(self.xml)

    def test_junit_zero_missing_duplicate_and_foreign_cases_fail(self):
        for change in ("zero", "missing", "duplicate", "foreign", "malformed-count"):
            with self.subTest(change=change):
                tree = copy.deepcopy(self.tree)
                root = tree.getroot()
                if change == "zero":
                    for case in list(root):
                        root.remove(case)
                    root.set("tests", "0")
                elif change == "missing":
                    root.remove(root.find("testcase"))
                elif change == "duplicate":
                    root.append(copy.deepcopy(root.find("testcase")))
                    root.set("tests", "10")
                elif change == "foreign":
                    root.find("testcase").set("classname", "foreign.Class")
                else:
                    root.set("tests", "9.0")
                tree.write(self.xml)
                with self.assertRaises(ValueError):
                    measurements.junit_cases(self.xml)

    def test_junit_inconsistent_aggregate_counts_and_json_disagreement_fail(self):
        wrapper = ET.Element("testsuites", tests="9", failures="1")
        wrapper.append(self.tree.getroot())
        ET.ElementTree(wrapper).write(self.xml)
        with self.assertRaisesRegex(ValueError, "aggregate"):
            measurements.junit_cases(self.xml)
        self.identities.remove(next(iter(self.identities)))
        with self.assertRaisesRegex(ValueError, "JSON/JUnit"):
            self.summarize()

    def test_unsafe_xml_duplicate_json_nonfinite_and_oversized_inputs_fail(self):
        self.xml.write_text('<!DOCTYPE testsuite [<!ENTITY x "unsafe">]><testsuite/>', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            measurements.junit_cases(self.xml)
        path = self.directory / "malformed.json"
        for text in ('{"benchmarks": [], "benchmarks": []}', '{"value": NaN}', '{"value": Infinity}',
                     '{"value": 1e999}', '{"unusedMetadata": -1e999}', "{"):
            with self.subTest(text=text):
                path.write_text(text, encoding="utf-8")
                with self.assertRaises(ValueError):
                    measurements.read_results(path)
        path.write_text("{}", encoding="utf-8")
        with patch.object(measurements, "MAX_INPUT_BYTES", 1), self.assertRaisesRegex(ValueError, "Oversized"):
            measurements.read_results(path)

    def test_malformed_documents_are_explicit_cli_failures(self):
        for value in (None, [], {"context": []}, {"context": {"build": {}}}):
            with self.subTest(value=value):
                results = self.directory / "bad.json"
                results.write_text(json.dumps(value), encoding="utf-8")
                apk = self.directory / "synthetic.apk"
                apk.write_bytes(APK)
                stderr = io.StringIO()
                stdout = io.StringIO()
                with redirect_stdout(stdout), redirect_stderr(stderr):
                    code = measurements.main([
                        "--results", str(results), "--junit", str(self.xml), "--apk", str(apk),
                        "--commit", COMMIT, "--dataset-sha256", DATASET, "--device-kind", "emulator",
                    ])
                self.assertEqual(2, code)
                self.assertTrue(stderr.getvalue().startswith("BLOCKED:"))
                self.assertEqual("", stdout.getvalue())

    def test_cli_reads_actual_input_files_and_hashes_supplied_apk(self):
        results = self.directory / "synthetic.json"
        results.write_text(json.dumps(self.results), encoding="utf-8")
        apk = self.directory / "synthetic.apk"
        apk.write_bytes(APK)
        args = [
            "--results", str(results), "--junit", str(self.xml), "--apk", str(apk),
            "--commit", COMMIT, "--dataset-sha256", DATASET, "--device-kind", "emulator",
        ]
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            self.assertEqual(0, measurements.main(args))
        self.assertEqual(SHA, json.loads(stdout.getvalue())["target_apk_sha256"])
        apk.write_bytes(b"changed synthetic APK")
        stderr = io.StringIO()
        with redirect_stderr(stderr), redirect_stdout(io.StringIO()):
            self.assertEqual(2, measurements.main(args))
        self.assertIn("Run binding mismatch: wp504.apkSha256", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
