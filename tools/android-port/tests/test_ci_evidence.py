"""AndroidOnly: WP-003 Validate live CI reports without checked-in result bundles."""

import ast
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from controller.ci_evidence import (
    EXPECTED_APK_PERMISSIONS,
    counts,
    failed_case_report,
    lint_evidence,
    suite_counts,
    suite_case_ids,
    validate_graph_runtime,
)
from controller.errors import PortError

REPO = Path(__file__).resolve().parents[3]


def junit_report(path: Path, tests=1, failures=0, errors=0, skipped=0):
    suite = ET.Element("testsuite", {
        "tests": str(tests),
        "failures": str(failures),
        "errors": str(errors),
        "skipped": str(skipped),
    })
    for index in range(tests):
        case = ET.SubElement(suite, "testcase", {"classname": "fixture.live", "name": f"case-{index}"})
        if index < failures:
            ET.SubElement(case, "failure")
        elif index < failures + errors:
            ET.SubElement(case, "error")
        elif index < failures + errors + skipped:
            ET.SubElement(case, "skipped")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(ET.tostring(suite))


class LiveReportTests(unittest.TestCase):
    def test_failed_hosted_logs_keep_all_real_identities_and_actual_totals(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            report = directory / "TEST-fixture.xml"
            junit_report(report, tests=6, failures=3, errors=1, skipped=1)
            observed = failed_case_report([directory])
            self.assertEqual(observed["counts"], {"discovered": 6, "passed": 1, "failed": 3, "errors": 1, "skipped": 1})
            self.assertEqual([case["test"] for case in observed["failures"]], ["case-0", "case-1", "case-2", "case-3"])
            self.assertTrue(all(case["class"] == "fixture.live" for case in observed["failures"]))
            with self.assertRaises(PortError):
                suite_case_ids(directory)
            junit_report(report, tests=2)
            self.assertEqual(len(suite_case_ids(directory)), 2)
            for count in (0,):
                junit_report(report, tests=count)
                with self.assertRaises(PortError):
                    failed_case_report([directory])
            report.unlink()
            with self.assertRaises(PortError):
                failed_case_report([directory])

    def test_runtime_inventory_preserves_distinct_licenses_for_one_component(self):
        with tempfile.TemporaryDirectory() as temporary:
            reports = Path(temporary)
            (reports / "module-graph.tsv").write_text(
                "consumer\tproducer\tconfiguration\n:app\t:core:model\tdebugRuntimeClasspath\n",
                encoding="utf-8",
            )
            header = (
                "artifact\tdeclared_license\tlicense_url\tlicense_pom\t"
                "license_pom_sha256\tlegal_gate\n"
            )
            coordinate = "androidx.camera:camera-core:1.6.2"
            digest = "a" * 64
            rows = [
                f"{coordinate}\tApache-2.0\thttps://apache.org/license\t{coordinate}\t{digest}\thuman-review-pending",
                f"{coordinate}\tBSD-3-Clause\thttps://opensource.org/license/bsd\t{coordinate}\t{digest}\thuman-review-pending",
            ]
            (reports / "runtime-dependencies.tsv").write_text(
                header + "\n".join(rows) + "\n",
                encoding="utf-8",
            )
            validate_graph_runtime(reports)

    def test_apk_inspector_and_controller_require_the_same_exact_permissions(self):
        module = ast.parse((REPO / "android/scaffold/inspect_apk.py").read_text(encoding="utf-8"))
        expected = next(
            ast.literal_eval(node.value)
            for node in ast.walk(module)
            if isinstance(node, ast.Assign)
            and any(isinstance(target, ast.Name) and target.id == "expected" for target in node.targets)
            and isinstance(node.value, ast.List)
            and any(
                isinstance(element, ast.Constant) and element.value == "android.permission.BLUETOOTH_CONNECT"
                for element in node.value.elts
            )
        )
        self.assertEqual(expected, EXPECTED_APK_PERMISSIONS)

    def test_complete_junit_report_has_positive_discovery(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "TEST-suite.xml"
            junit_report(report, tests=3)
            self.assertEqual(suite_counts(report.parent, 3)["passed"], 3)

    def test_zero_failed_error_and_skipped_discovery_reject(self):
        cases = (
            {"tests": 0},
            {"tests": 1, "failures": 1},
            {"tests": 1, "errors": 1},
            {"tests": 1, "skipped": 1},
        )
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                report = Path(temporary) / "TEST-suite.xml"
                junit_report(report, **case)
                with self.assertRaises(PortError):
                    suite_counts(report.parent, 1)

    def test_claimed_counts_must_be_consistent_and_positive(self):
        valid = {"discovered": 2, "run": 2, "passed": 2, "failed": 0, "errors": 0, "skipped": 0}
        self.assertEqual(counts(valid, minimum=2), valid)
        for mutation in (
            {**valid, "discovered": 0, "run": 0, "passed": 0},
            {**valid, "failed": 1, "passed": 1},
            {**valid, "run": 1},
        ):
            with self.subTest(mutation=mutation), self.assertRaises(PortError):
                counts(mutation, minimum=1)

    def test_lint_errors_reject_live_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "lint.xml"
            report.write_text(
                '<issues format="6" by="lint fixture"><issue severity="Warning" id="W" message="warning"/></issues>',
                encoding="utf-8",
            )
            self.assertEqual(lint_evidence(report)["warnings"], 1)
            report.write_text(
                '<issues format="6" by="lint fixture"><issue severity="Error" id="E" message="error"/></issues>',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(PortError, "lint contains actual errors"):
                lint_evidence(report)

    def test_pinned_lint_informational_description_is_hint(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "lint-results-debug.xml"
            report.write_text(
                '<issues format="6" by="lint 9.4.1">'
                '<issue id="FixtureInformational" severity="Hint" message="Informational finding"/>'
                '</issues>', encoding="utf-8",
            )
            result = lint_evidence(report)
            self.assertEqual(result["warnings"], 1)
            self.assertEqual(len(result["sha256"]), 64)

    def test_lint_unknown_severity_and_missing_fields_remain_distinct_failures(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "lint-results-debug.xml"
            for severity in ("Warning", "Information", "Hint"):
                for field in ("id", "message"):
                    for value in (None, ""):
                        with self.subTest(severity=severity, field=field, value=value):
                            attributes = {"id": "Fixture", "severity": severity, "message": "finding"}
                            if value is None:
                                del attributes[field]
                            else:
                                attributes[field] = value
                            root = ET.Element("issues", {"format": "6", "by": "lint 9.4.1"})
                            ET.SubElement(root, "issue", attributes)
                            ET.ElementTree(root).write(report, encoding="utf-8")
                            with self.assertRaisesRegex(PortError, "Missing lint issue id/message"):
                                lint_evidence(report)
            for severity in (None, "", "Ignore", "hint", "Unknown"):
                with self.subTest(severity=severity):
                    attributes = {"id": "Fixture", "message": "finding"}
                    if severity is not None:
                        attributes["severity"] = severity
                    root = ET.Element("issues", {"format": "6", "by": "lint 9.4.1"})
                    ET.SubElement(root, "issue", attributes)
                    ET.ElementTree(root).write(report, encoding="utf-8")
                    with self.assertRaisesRegex(PortError, "Unknown/missing lint severity") as raised:
                        lint_evidence(report)
                    self.assertIn(str(report), str(raised.exception))
            for severity in ("Error", "Fatal"):
                with self.subTest(severity=severity):
                    report.write_text(
                        f'<issues format="6" by="lint 9.4.1"><issue id="Fixture" '
                        f'severity="{severity}" message="finding"/></issues>', encoding="utf-8",
                    )
                    with self.assertRaisesRegex(PortError, "lint contains actual errors"):
                        lint_evidence(report)
            report.write_text('<issues format="6" by="lint 9.4.1"><unexpected/></issues>', encoding="utf-8")
            with self.assertRaisesRegex(PortError, "Malformed Android Lint XML"):
                lint_evidence(report)

    def test_malformed_junit_rejects(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "TEST-suite.xml"
            report.write_text("<testsuite>", encoding="utf-8")
            with self.assertRaises(PortError):
                suite_counts(report.parent, 1)


if __name__ == "__main__":
    unittest.main()
