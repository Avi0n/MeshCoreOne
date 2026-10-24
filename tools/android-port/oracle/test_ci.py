"""AndroidOnly: WP-004 Fail-closed workflow, helper-discovery and JUnit contract tests."""

import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree

from controller.ci_environment import candidate_environment
from controller.errors import PortError
from controller.workflows import parse_yaml, validate_boundary, validate_candidate
from oracle.foundation_ci import EXPECTED_TESTS, PACKAGE, verify_junit
from oracle.identity import evidence_identity
from oracle.reference import OracleError, REPO, SOURCE_SHA
from oracle.run_tests import discover


def reports(directory):
    for name, methods in EXPECTED_TESTS.items():
        root = ElementTree.Element("testsuite", name=PACKAGE + name, tests=str(len(methods)),
                                   failures="0", errors="0", skipped="0")
        for method in sorted(methods):
            ElementTree.SubElement(root, "testcase", name=method, classname=PACKAGE + name)
        ElementTree.ElementTree(root).write(directory / ("TEST-" + PACKAGE + name + ".xml"))


class HelperDiscoveryTests(unittest.TestCase):
    def test_all_six_actual_new_suites_are_required(self):
        with tempfile.TemporaryDirectory() as root:
            reports(Path(root))
            result = verify_junit(Path(root))
            self.assertEqual(result["discovered"], 35)
            self.assertEqual(result["passed"], 35)
            self.assertEqual(result["skipped"], 0)
            self.assertEqual(len(result["suites"]), 6)

    def test_missing_empty_malformed_and_unknown_xml_fail(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            with self.assertRaises(OracleError):
                verify_junit(directory)
            path = directory / "TEST-empty.xml"
            for content in ("<invalid", "<testsuite name='unknown' tests='0'/>"):
                path.write_text(content)
                with self.subTest(content=content), self.assertRaises(OracleError):
                    verify_junit(directory)

    def test_failed_skipped_duplicate_zero_and_forged_counts_fail(self):
        for kind in ("failed", "skipped", "duplicate", "missing-case", "count", "missing-suite"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as root:
                directory = Path(root)
                reports(directory)
                path = sorted(directory.glob("TEST-*.xml"))[0]
                tree = ElementTree.parse(path)
                suite = tree.getroot()
                case = suite.find("testcase")
                if kind in ("failed", "skipped"):
                    ElementTree.SubElement(case, "failure" if kind == "failed" else "skipped")
                elif kind == "duplicate":
                    suite.append(copy.deepcopy(case))
                elif kind == "missing-case":
                    suite.remove(case)
                elif kind == "count":
                    suite.set("tests", "999")
                if kind == "missing-suite":
                    path.unlink()
                else:
                    tree.write(path)
                with self.assertRaises(OracleError):
                    verify_junit(directory)

    def test_missing_or_zero_python_suites_fail(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(OracleError):
                discover(Path(root))
            path = Path(root) / "test_empty.py"
            path.write_text("import unittest\n")
            with self.assertRaises(OracleError):
                discover(Path(root), "test_empty.py")

    def test_candidate_gradle_reuses_the_existing_credential_allowlist(self):
        import os
        state = {"schema_version": 1, "host": "windows" if os.name == "nt" else "linux",
                 "java_home": str(REPO), "android_home": str(REPO), "private_root": str(REPO),
                 "provenance": {"scope": "unit fixture only"}}
        result = candidate_environment(state, inherited={"PATH": "path", "GH_TOKEN": "secret", "COPILOT_TOKEN": "secret"})
        self.assertNotIn("GH_TOKEN", result)
        self.assertNotIn("COPILOT_TOKEN", result)
        self.assertIn("GRADLE_USER_HOME", result)

    def test_hosted_identity_preserves_real_binding_without_claiming_a_gate(self):
        unit_identity = {
            "binding": {
                "repository": "cbattlegear/MeshCoreOne-Android", "work_package": "WP-003",
                "base_sha": "a" * 40, "head_sha": "b" * 40, "source_sha": SOURCE_SHA,
                "manifest_sha256": "c" * 64, "policy_revision": "d" * 64,
            },
            "run_id": 13, "run_attempt": 2,
        }
        with patch("oracle.identity.execution_identity", return_value=unit_identity):
            result = evidence_identity()
        self.assertEqual(result["binding"]["work_package"], "WP-004")
        self.assertEqual(result["binding"]["head_sha"], "b" * 40)
        self.assertEqual(result["run_id"], 13)
        self.assertIn("not an authenticated protected publisher", result["scope"])
        self.assertEqual(unit_identity["binding"]["work_package"], "WP-003")

    def test_local_identity_has_no_invented_hosted_run(self):
        with patch("oracle.identity.execution_identity", return_value=None):
            result = evidence_identity()
        self.assertIsNone(result["run_id"])
        self.assertIsNone(result["run_attempt"])
        self.assertEqual(result["binding"]["source_sha"], SOURCE_SHA)
        self.assertIn("no hosted run authority", result["scope"])


class FoundationWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = (REPO / ".github" / "workflows" / "android-ci.yml").read_text(encoding="utf-8")
        cls.workflow = parse_yaml(cls.text)

    def test_pinned_read_only_ephemeral_candidate_boundaries(self):
        validate_boundary(self.workflow, self.text)
        self.assertEqual(self.workflow["permissions"], {"contents": "read"})
        self.assertEqual(self.workflow["jobs"]["build"]["runs-on"], "ubuntu-24.04")
        for name in ("external-oracle", "backup-oracle"):
            self.assertEqual(self.workflow["jobs"][name]["runs-on"], "macos-26-intel")
        for job in self.workflow["jobs"].values():
            self.assertFalse(job.get("environment"))
            self.assertFalse(job.get("services"))
            self.assertFalse(job.get("container"))

    def test_regular_pr_main_and_merge_group_events_have_no_path_skips(self):
        self.assertEqual(set(self.workflow["on"]), {"pull_request", "merge_group", "push", "workflow_dispatch"})
        self.assertEqual(self.workflow["on"]["push"]["branches"], ["main"])
        for event in ("pull_request", "merge_group"):
            self.assertFalse(set(self.workflow["on"][event]) & {"paths", "paths-ignore", "branches", "branches-ignore"})

    def test_actual_new_assertion_and_reference_commands_are_mandatory(self):
        runs = "\n".join(step.get("run", "") for job in self.workflow["jobs"].values() for step in job["steps"])
        for command in ("test_inventory.py --check", "extract_vectors.py --check",
                        "oracle/run_tests.py", "controller/ci.py provision",
                        "controller/ci.py run --stage scaffold", "oracle/codec_harness.py run"):
            self.assertIn(command, runs)
        validate_candidate(self.workflow, self.text)
        self.assertEqual(runs.count("controller/ci.py run --stage scaffold"), 1)

    def test_scope_selector_and_strict_runner_cannot_be_removed_duplicated_or_bypassed(self):
        for job in ("scope", "controller"):
            for mutation in ("missing", "duplicate", "conditional", "ignored", "synthetic", "unbound"):
                with self.subTest(job=job, mutation=mutation):
                    value = copy.deepcopy(self.workflow)
                    steps = value["jobs"][job]["steps"]
                    step = next(step for step in steps if "controller/test_runner.py" in step.get("run", ""))
                    if mutation == "missing":
                        steps.remove(step)
                    elif mutation == "duplicate":
                        steps.append(copy.deepcopy(step))
                    elif mutation == "conditional":
                        step["if"] = "${{ always() }}"
                    elif mutation == "ignored":
                        step["continue-on-error"] = "true"
                    elif mutation == "synthetic":
                        step["run"] = step["run"].replace(
                            "python tools/android-port/controller/test_runner.py",
                            "echo tools/android-port/controller/test_runner.py",
                        )
                    else:
                        step["run"] = step["run"].replace(
                            '--candidate-event-path "${{ github.event_path }}"', "",
                        )
                    with self.assertRaises(PortError):
                        validate_candidate(value, self.text)

    def test_auxiliary_conditions_are_job_level_and_preserve_complete_chains(self):
        expected = {"external-oracle": "external-oracle", "backup-oracle": "backup"}
        for name, scope in expected.items():
            with self.subTest(job=name):
                job = self.workflow["jobs"][name]
                self.assertEqual(
                    job["if"],
                    ("${{ needs.scope.outputs['external-oracle'] == 'true' }}"
                     if scope == "external-oracle"
                     else "${{ needs.scope.outputs." + scope + " == 'true' }}"),
                )
                self.assertIn("scope", job["needs"])
        self.assertIn("controller", self.workflow["jobs"]["build"]["needs"])
        self.assertIn("build", self.workflow["jobs"]["backup-oracle"]["needs"])

    def test_privilege_and_action_pin_mutations_fail(self):
        for kind in ("token", "action", "checkout"):
            value = copy.deepcopy(self.workflow)
            if kind == "token":
                value["permissions"]["contents"] = "write"
            elif kind == "action":
                value["jobs"]["build"]["steps"][0]["uses"] = "actions/checkout@v4"
            else:
                value["jobs"]["build"]["steps"][0]["with"]["persist-credentials"] = "true"
            with self.subTest(kind=kind), self.assertRaises(PortError):
                validate_boundary(value, self.text)

    def test_only_actual_backup_bytes_cross_the_job_boundary(self):
        steps = self.workflow["jobs"]["build"]["steps"]
        uploads = [step for step in steps if step.get("uses", "").startswith("actions/upload-artifact@")]
        self.assertEqual(len(uploads), 2)
        self.assertEqual({step["with"]["path"] for step in uploads}, {
            "android/app/build/outputs/apk/debug/app-debug.apk",
            "android/core/data/build/reports/wp203/interop/",
        })
        transfers = [
            step for job in self.workflow["jobs"].values() for step in job["steps"]
            if step.get("uses", "").startswith("actions/download-artifact@")
        ]
        self.assertEqual(len(transfers), 1)
        backup = next(step for step in uploads if step["with"]["name"] == transfers[0]["with"]["name"])
        self.assertEqual(backup["if"], "${{ needs.scope.outputs.backup == 'true' }}")
        self.assertEqual(backup["with"]["if-no-files-found"], "error")
        paths = backup["with"]["path"]
        self.assertIn("wp203/interop", paths)
        for upload in uploads:
            self.assertNotIn("ci-result", upload["with"]["path"])
