"""AndroidOnly: WP-501 Regression assertions for complete fail-closed original-family accounting."""

import copy
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import audit
from controller.errors import PortError


def fixture():
    case = {"id": "SharedSuite::sameName()", "parameter_family": "single",
            "assertion_status": "direct", "direct_assertions": [{"kind": "#expect", "line": 7}],
            "helper_assertions": [], "inputs": None}
    paths = ["MC1Tests/A.swift", "MC1Tests/B.swift"]
    inventory = [{"path": path, "blob_sha": "a" * 40, "kind": "test",
                  "primary_owner": "WP-308", "exclusion": None} for path in paths]
    catalog = {"entries": [{"path": path, "blob_sha": "a" * 40,
                           "cases": [{"id": case["id"], "parameter_family": "single"}]} for path in paths]}
    details = {"files": [{"path": path, "blob_sha": "a" * 40, "cases": [copy.deepcopy(case)]} for path in paths]}
    manifest = SimpleNamespace(data={"inventory": inventory}, sha256="b" * 64)
    return manifest, catalog, details


class AccountingTest(unittest.TestCase):
    def test_same_case_name_in_two_original_paths_never_collapses(self):
        _, catalog, details = fixture()
        keys = audit.index_cases(catalog, details)
        self.assertEqual(2, len(keys))
        self.assertNotEqual(*keys.keys())

    def test_missing_original_family_is_rejected(self):
        _, catalog, details = fixture()
        details["files"][0]["cases"].clear()
        with self.assertRaisesRegex(PortError, "Missing or duplicate"):
            audit.index_cases(catalog, details)

    def test_extra_original_family_is_rejected(self):
        _, catalog, details = fixture()
        extra = copy.deepcopy(details["files"][0]["cases"][0])
        extra["id"] = "SharedSuite::extra()"
        details["files"][0]["cases"].append(extra)
        with self.assertRaisesRegex(PortError, "Unaccounted"):
            audit.index_cases(catalog, details)

    def test_duplicate_original_path_is_rejected(self):
        _, catalog, details = fixture()
        details["files"].append(copy.deepcopy(details["files"][0]))
        with self.assertRaisesRegex(PortError, "Duplicate detailed original path"):
            audit.index_cases(catalog, details)

    def test_duplicate_original_identity_is_rejected(self):
        _, catalog, details = fixture()
        details["files"][0]["cases"] *= 2
        with self.assertRaisesRegex(PortError, "Duplicate original path/case"):
            audit.index_cases(catalog, details)

    def test_catalog_duplicate_is_not_hidden_by_a_dictionary(self):
        _, catalog, details = fixture()
        catalog["entries"][0]["cases"] *= 2
        with self.assertRaisesRegex(PortError, "Missing or duplicate"):
            audit.index_cases(catalog, details)

    def test_parameter_family_drift_is_rejected(self):
        _, catalog, details = fixture()
        details["files"][0]["cases"][0]["parameter_family"] = "declared-inputs:changed"
        with self.assertRaises(PortError):
            audit.index_cases(catalog, details)

    def test_blob_drift_is_rejected(self):
        _, catalog, details = fixture()
        details["files"][0]["blob_sha"] = "c" * 40
        with self.assertRaisesRegex(PortError, "blob mismatch"):
            audit.index_cases(catalog, details)

    def test_zero_original_cases_is_rejected(self):
        with self.assertRaisesRegex(PortError, "Missing or zero"):
            audit.index_cases({"entries": []}, {"files": []})

    def test_missing_support_only_path_is_rejected(self):
        _, catalog, details = fixture()
        details["files"].append({"path": "Helpers.swift", "blob_sha": "a" * 40, "cases": []})
        with self.assertRaisesRegex(PortError, "Missing or zero"):
            audit.index_cases(catalog, details)

    def test_generated_accounting_never_promotes_headers_to_behavior(self):
        manifest, catalog, details = fixture()
        value = audit.build_accounting(manifest, {}, catalog, details, {"MC1Tests/A.swift": ["Native.kt"]}, {}, [])
        self.assertEqual("BLOCKED", value["acceptance"])
        self.assertEqual(0, value["counts"]["verified_behavioral_acceptances"])
        self.assertEqual("no-explicit-family-mapping", value["case_families"][0]["acceptance"])
        self.assertEqual("independent-behavior-review-required", value["sources"][0]["acceptance"])

    def test_predecessor_pass_is_still_pending_current_independent_review(self):
        manifest, catalog, details = fixture()
        key = audit.case_key("MC1Tests/A.swift", catalog["entries"][0]["cases"][0])
        candidates = {key: [{"kind": "historical-mapping-not-current-proof", "path": "old.json", "record": 0}]}
        value = audit.build_accounting(manifest, {}, catalog, details, {}, candidates, [])
        self.assertEqual("independent-family-review-required", value["case_families"][0]["acceptance"])
        self.assertEqual(0, value["counts"]["verified_behavioral_acceptances"])

    def test_removed_translation_is_accounted_but_never_passed(self):
        manifest, catalog, details = fixture()
        manifest.data["inventory"][0].update(primary_owner=None, exclusion="removed-translation")
        value = audit.build_accounting(manifest, {}, catalog, details, {}, {}, [])
        self.assertEqual("scope-excluded-not-passed", value["case_families"][0]["acceptance"])
        self.assertEqual(2, value["counts"]["original_case_families"])
        self.assertEqual(1, value["counts"]["family_dispositions"]["scope-excluded-not-passed"])

    def test_every_source_kind_remains_in_accounting(self):
        manifest, catalog, details = fixture()
        for kind in ("production", "resource", "license", "support", "reference", "generated", "apple-glue"):
            manifest.data["inventory"].append({
                "path": kind, "blob_sha": "d" * 40, "kind": kind,
                "primary_owner": "WP-501", "exclusion": None,
            })
        value = audit.build_accounting(manifest, {}, catalog, details, {}, {}, [])
        self.assertEqual(9, value["counts"]["source_paths"])

    def test_full_original_input_expression_is_digest_bound(self):
        manifest, catalog, details = fixture()
        before = audit.build_accounting(manifest, {}, catalog, details, {}, {}, [])
        details["files"][0]["cases"][0]["inputs"] = {"rows": ["CJK", "RTL", "emoji"]}
        after = audit.build_accounting(manifest, {}, catalog, details, {}, {}, [])
        self.assertNotEqual(before["original_details_sha256"], after["original_details_sha256"])
        self.assertNotEqual(before["case_families"][0]["original_detail_sha256"],
                            after["case_families"][0]["original_detail_sha256"])

    def test_policy_cannot_allow_mandatory_skips(self):
        manifest = audit.load_manifest(audit.REPO)
        policy = audit.load_json(audit.repo_path(audit.REPO, audit.POLICY))
        policy["mandatory_skips_allowed"] = True
        with patch.object(audit, "load_json", return_value=policy), self.assertRaisesRegex(PortError, "Coverage policy"):
            audit.current_policy(audit.REPO, manifest)

    def test_policy_cannot_add_unapproved_exclusions(self):
        manifest = audit.load_manifest(audit.REPO)
        policy = audit.load_json(audit.repo_path(audit.REPO, audit.POLICY))
        policy["new_exclusions_allowed"] = True
        with patch.object(audit, "load_json", return_value=policy), self.assertRaisesRegex(PortError, "Coverage policy"):
            audit.current_policy(audit.REPO, manifest)


class CurrentExecutionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.expected = {("Acceptance", "originalText")}

    def report(self, body='<testcase classname="Acceptance" name="originalText"/>',
               tests=1, failures=0, errors=0, skipped=0):
        (self.directory / "TEST-Acceptance.xml").write_text(
            f'<testsuite tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">{body}</testsuite>',
            encoding="utf-8",
        )

    def test_required_actual_case_passes(self):
        self.report()
        self.assertEqual(1, audit.verify_acceptance_tests(self.directory, self.expected)["passed"])

    def test_missing_reports_fail(self):
        with self.assertRaises(PortError):
            audit.verify_acceptance_tests(self.directory, self.expected)

    def test_zero_tests_fail(self):
        self.report(body="", tests=0)
        with self.assertRaises(PortError):
            audit.verify_acceptance_tests(self.directory, self.expected)

    def test_green_unrelated_tests_are_not_the_expected_behavior(self):
        self.report('<testcase classname="Other" name="unrelated"/>')
        with self.assertRaisesRegex(PortError, "Missing WP-501"):
            audit.verify_acceptance_tests(self.directory, self.expected)

    def test_actual_skip_is_not_hidden_by_zero_summary(self):
        self.report('<testcase classname="Acceptance" name="originalText"><skipped/></testcase>')
        with self.assertRaises(PortError):
            audit.verify_acceptance_tests(self.directory, self.expected)

    def test_failed_assertion_is_rejected(self):
        self.report('<testcase classname="Acceptance" name="originalText"><failure/></testcase>', failures=1)
        with self.assertRaises(PortError):
            audit.verify_acceptance_tests(self.directory, self.expected)

    def test_runner_error_is_rejected(self):
        self.report('<testcase classname="Acceptance" name="originalText"><error/></testcase>', errors=1)
        with self.assertRaises(PortError):
            audit.verify_acceptance_tests(self.directory, self.expected)

    def test_malformed_report_is_rejected(self):
        (self.directory / "TEST-Acceptance.xml").write_text("<testsuite", encoding="utf-8")
        with self.assertRaises(PortError):
            audit.verify_acceptance_tests(self.directory, self.expected)

    def test_duplicate_actual_identity_is_rejected(self):
        self.report('<testcase classname="Acceptance" name="originalText"/>' * 2, tests=2)
        with self.assertRaises(PortError):
            audit.verify_acceptance_tests(self.directory, self.expected)

    def test_count_mismatch_is_rejected(self):
        self.report(tests=2)
        with self.assertRaises(PortError):
            audit.verify_acceptance_tests(self.directory, self.expected)

    def test_zero_required_identities_is_rejected(self):
        self.report()
        with self.assertRaisesRegex(PortError, "Zero required"):
            audit.verify_acceptance_tests(self.directory, set())


class CheckCommandTest(unittest.TestCase):
    def test_check_validates_inputs_without_rewriting_or_freshness_gating_history(self):
        with patch.object(audit, "generate", return_value={}) as validate, \
                patch.object(audit, "write_or_check") as output, \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, audit.main(["--check"]))
        validate.assert_called_once_with()
        output.assert_not_called()

    def test_check_still_rejects_source_or_provenance_drift(self):
        with patch.object(audit, "generate", side_effect=PortError("source drift")), \
                patch.object(audit, "write_or_check") as output, \
                contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(2, audit.main(["--check"]))
        self.assertIn("source drift", errors.getvalue())
        output.assert_not_called()


class WiringTest(unittest.TestCase):
    def test_parity_sources_and_policy_select_controller_and_scaffold(self):
        path = audit.REPO / "tools" / "android-port" / "oracle" / "workflow_scope.py"
        spec = importlib.util.spec_from_file_location("wp501_workflow_scope", path)
        scope = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(scope)
        for candidate in ("android/parity-tests/test_audit.py",
                          "android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/MessageBackupAcceptanceTest.kt"):
            selected = scope.classify([candidate])
            self.assertTrue(selected["controller"])
            self.assertTrue(selected["scaffold"])
        self.assertTrue(scope.classify(["docs/android/parity/coverage-policy.json"])["controller"])

    def test_parity_inputs_are_committed_runtime_requirements(self):
        from controller.runtime_inputs import required_inputs

        for path in ("android/parity-tests/audit.py", "android/parity-tests/test_audit.py",
                     "docs/android/parity/accounting.json", "docs/android/parity/coverage-policy.json"):
            self.assertIn(path, required_inputs())

    def test_exact_required_behavioral_names_are_distinct_and_nonzero(self):
        from controller.ci_evidence import WP501_ACCEPTANCE_CASES

        self.assertEqual(83, len(WP501_ACCEPTANCE_CASES))
        self.assertEqual({
            ("com.meshcoreone.android.parity.BlePacingAcceptanceTest", name)
            for name in (
                "configuredEsp32LinkPacesAcknowledgedWritesForSixtyMilliseconds",
                "configuredNrf52LinkPacesAcknowledgedWritesForTwentyFiveMilliseconds",
                "configuredUnknownBluetoothLinkKeepsConservativeSixtyMillisecondPacing",
            )
        }, {identity for identity in WP501_ACCEPTANCE_CASES
            if identity[0] == "com.meshcoreone.android.parity.BlePacingAcceptanceTest"})
        self.assertEqual({
            ("com.meshcoreone.android.parity.IncomingReactionAcceptanceTest", name)
            for name in (
                "mountedPocketChannelReactionSurvivesArrivalBeforeItsTarget",
                "mountedPocketDirectReactionSurvivesArrivalBeforeItsTarget",
                "mountedMeshCoreOpenV3ChannelReactionReachesTheActualStoredTarget",
                "mountedMeshCoreOpenV3DirectReactionReachesTheActualStoredTarget",
                "mountedMeshCoreOpenV1ChannelReactionReachesTheActualStoredTarget",
                "mountedMeshCoreOpenV1DirectReactionReachesTheActualStoredTarget",
            )
        }, {identity for identity in WP501_ACCEPTANCE_CASES
            if identity[0] == "com.meshcoreone.android.parity.IncomingReactionAcceptanceTest"})
        self.assertEqual({
            ("com.meshcoreone.android.parity.HeardRepeatStoreAcceptanceTest",
             "exactTextDisambiguatesStoredOutgoingRowsWithTheSameChannelAndTimestamp"),
        }, {identity for identity in WP501_ACCEPTANCE_CASES
            if identity[0] == "com.meshcoreone.android.parity.HeardRepeatStoreAcceptanceTest"})
        self.assertEqual({
            ("com.meshcoreone.android.parity.CliCompletionUnicodeAcceptanceTest", name)
            for name in (
                "learnedNodePrefixRetainsTheIndependentSwiftCanonicalMatch",
                "learnedCustomKeyPrefixRetainsTheIndependentSwiftCanonicalMatch",
                "actualTerminalGhostRetainsTheCanonicalPrefixAndOriginalInput",
                "actualTerminalTabAppliesTheVerbatimCanonicalPrefixMatch",
            )
        }, {identity for identity in WP501_ACCEPTANCE_CASES
            if identity[0] == "com.meshcoreone.android.parity.CliCompletionUnicodeAcceptanceTest"})
        self.assertEqual({
            ("com.meshcoreone.android.parity.ConversationDeletionAcceptanceTest", name)
            for name in (
                "actualAppDeletionRecomputesRecencyFromRemainingDatabaseHistory",
                "actualAppDeletionClearsTheLastMessageDateAndRemovesTheEmptyConversation",
            )
        }, {identity for identity in WP501_ACCEPTANCE_CASES
            if identity[0] == "com.meshcoreone.android.parity.ConversationDeletionAcceptanceTest"})
        self.assertIn(("com.meshcoreone.android.parity.MessageKeyAcceptanceTest",
                       "originalPrefixParameterFamilyRetainsAllFourRows"), WP501_ACCEPTANCE_CASES)
        directory = audit.REPO / "android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity"
        for class_name, method in WP501_ACCEPTANCE_CASES:
            text = (directory / (class_name.rsplit(".", 1)[1] + ".kt")).read_text(encoding="utf-8")
            self.assertIn(f"@Test fun {method}(", text)
        from controller.model import load_manifest
        from portmap import port_map

        port_map(load_manifest(audit.REPO))

    def test_parity_fixture_directory_is_test_only(self):
        text = (audit.REPO / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('sourceSets.getByName("test").kotlin.srcDir("../parity-tests/src/test/kotlin")', text)
        self.assertIn('testImplementation(project(":core:testing"))', text)
        self.assertNotIn('sourceSets.getByName("main").kotlin.srcDir("../parity-tests', text)


if __name__ == "__main__":
    unittest.main()
