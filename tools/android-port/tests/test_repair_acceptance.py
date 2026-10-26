"""AndroidOnly: WP-003 Bounded repair acceptance never replaces full-WP completion."""

import base64
import copy
import hashlib
import json
import unittest
from dataclasses import asdict, replace
from unittest.mock import patch

from fixtures import BASE, HEAD, MERGE, base_manifest, bundle, policy
from controller.engine import GateBundle
from controller.errors import PortError
from controller.gates import Binding, policy_revision, verify_completion, verify_pr_gates
from controller.ledger import Identity
from controller.native_review import REVIEW_MARKER
from controller.repair_acceptance import (
    SCOPE_MARKER, authenticated_scope, instrumentation_results, validate_build_log,
    validate_hosted_methods, validate_scope, verify_native, verify_repair,
    retained_repairs_execution,
)
from controller.schema import digest
import test_native_review

PATH = "android/feature/map/src/main/kotlin/com/meshcoreone/android/feature/map/MapEntry.kt"
TEST = "android/core/maps/src/test/kotlin/com/meshcoreone/android/core/maps/MapTest.kt"
METHOD = {"path": TEST, "blob_sha": "4" * 40, "id": "keepsListReachable"}
LOG_STEP = "Run every selected Linux and Android task in one Gradle invocation"


def fixture():
    manifest, rules = base_manifest(), policy()
    rules["trusted_gate_publisher_app_id"] = None
    value = bundle(manifest, rules, "WP-312", changed_paths=[PATH], merged=False)
    source = next(e["path"] for e in manifest.inputs("WP-312", False) if e["kind"] == "production" and e["exclusion"] is None)
    scope = {
        "schema_version": 1, "kind": "landed-wp-repair", "binding": asdict(value.binding),
        "repair_pr": 123, "repair_issue": 224, "landed_pr": 140, "owner": manifest.wp("WP-312")["owner"],
        "summary": "Fixture-only bounded map repair, not actual acceptance.",
        "paths": [{"path": PATH, "operation": "modify", "capability": "wp-owned"}],
        "acceptance": [{"id": "repair-224-list", "description": "List remains reachable at font200."}],
        "coverage": [{"path": PATH, "source_paths": [source], "cases": [], "native_methods": [],
                      "hosted_methods": [METHOD], "adaptation": "Fixture-only original/native adaptation.",
                      "modules": ["feature/map", "core/maps"]}],
        "verification": ["android-scaffold"], "native": [],
    }
    url = f"https://github.com/{rules['repository']}/actions/runs/1/attempts/1"
    acceptance = [{"id": "repair-224-list", "result": "PASS", "evidence": [url]}]
    evidence = {
        "schema_version": 3, **asdict(value.binding), "changed_paths": [PATH],
        "runs": [{"check": "android-ci", "run_id": 1, "run_attempt": 1}],
        "retained_artifacts": [], "acceptance": acceptance, "source_tests": [], "repair_scope": scope,
    }
    review = {**value.review, "acceptance": acceptance, "human_gate_required": False}
    return manifest, rules, replace(value, evidence=evidence, review=review, approvals=[]), scope


def build_log(modules, **changes):
    from controller.ci import TASKS

    count = {"discovered": 3, "run": 3, "passed": 3, "failed": 0, "errors": 0, "skipped": 0}
    report = {"stage": "scaffold", "result": "success", "strict_verification": True,
              "tasks": TASKS["scaffold"], "module_unit_tests": {m: copy.deepcopy(count) for m in modules},
              "unit_test_methods": {m: [{"class": "MapTest", "method": name}
                                      for name in ("keepsListReachable", "unrelatedOne", "unrelatedTwo")]
                                    for m in modules}}
    report.update(changes)
    return "\n".join(f"One Linux Android build\t{LOG_STEP}\t2026-10-19T00:00:00Z {line}"
                     for line in json.dumps(report, indent=2).splitlines())


class RepairContractTests(unittest.TestCase):
    def test_unconfigured_whole_wp_can_have_strict_bounded_acceptance_without_completion(self):
        manifest, rules, value, scope = fixture()
        self.assertFalse(manifest.wp("WP-312")["verification"]["configured"])
        proof = verify_repair(value, scope, manifest, rules, None)
        self.assertFalse(proof["wp_complete"])
        self.assertFalse(proof["dependency_unlock"])
        for verifier in (verify_pr_gates, verify_completion):
            with self.subTest(verifier=verifier), self.assertRaisesRegex(PortError, "[Bb]ounded repair"):
                verifier(value.pr, value.evidence, value.review, [], value.binding, manifest, rules)
        self.assertFalse(manifest.wp("WP-312")["verification"]["configured"])

    def test_wrong_owner_binding_capability_operation_scope_and_full_wp_criteria_fail(self):
        for kind in ("owner", "binding", "capability", "operation", "paths", "criterion", "verification",
                     "coverage", "excluded", "zero", "module"):
            manifest, rules, value, scope = fixture()
            changed = copy.deepcopy(scope)
            if kind == "owner":
                changed["owner"] = "wrong-owner"
            elif kind == "binding":
                changed["binding"]["source_sha"] = "0" * 40
            elif kind in ("capability", "operation"):
                changed["paths"][0][kind] = "wrong"
            elif kind == "paths":
                changed["paths"][0]["path"] += ".unexplained"
            elif kind == "criterion":
                changed["acceptance"][0]["id"] = "WP-312-behavior"
            elif kind == "verification":
                changed["verification"] = ["guessed-gradle-task"]
            elif kind == "coverage":
                changed["coverage"] = []
            elif kind == "excluded":
                changed["coverage"][0]["source_paths"] = ["MC1/RemovedTranslation.swift"]
            elif kind == "zero":
                changed["coverage"][0]["hosted_methods"] = []
            else:
                changed["coverage"][0]["modules"] = ["core/maps"]
            with self.subTest(kind=kind), self.assertRaises(PortError):
                validate_scope(changed, value.binding, manifest, rules, [PATH], None)

    def test_support_paths_require_actual_typed_rule_not_a_scope_string(self):
        manifest, rules, value, scope = fixture()
        lock = "android/gradle/dependency-locks/feature-map.lockfile"
        scope["paths"].append({"path": lock, "operation": "add-resolved-lock",
                               "capability": "dependency-resolution-metadata"})
        validate_scope(scope, value.binding, manifest, rules, [PATH, lock], None)
        scope["paths"][-1]["capability"] = "wp-owned"
        with self.assertRaisesRegex(PortError, "capability"):
            validate_scope(scope, value.binding, manifest, rules, [PATH, lock], None)

    def test_missing_acceptance_findings_scope_edits_and_human_gate_are_not_waived(self):
        for kind in ("acceptance", "findings", "scope", "human"):
            manifest, rules, value, scope = fixture()
            evidence, review, pr = copy.deepcopy(value.evidence), copy.deepcopy(value.review), copy.deepcopy(value.pr)
            if kind == "acceptance":
                evidence["acceptance"] = []
            elif kind == "findings":
                review["blockers"] = ["Unresolved source finding"]
            elif kind == "scope":
                evidence["repair_scope"]["summary"] = "Edited after admission"
            else:
                pr["labels"] = ["needs-human"]
                review["human_gate_required"] = True
            with self.subTest(kind=kind), self.assertRaises(PortError):
                verify_repair(replace(value, evidence=evidence, review=review, pr=pr), scope, manifest, rules, None)

    def test_affected_original_parameter_family_cannot_be_unknown_dropped_or_excluded(self):
        manifest, rules, value, scope = fixture()
        original = next(e for e in manifest.inputs("WP-312", False)
                        if e["kind"] == "test" and e["exclusion"] is None)
        definition = {"path": original["path"], "blob_sha": original["blob_sha"], "has_assertions": True,
                      "cases": [{"id": "original-case", "parameter_family": "boundary-family"}]}
        catalog = {"schema_version": 1, "source_sha": value.binding.source_sha, "entries": [definition]}
        scope["coverage"][0]["cases"] = [{"path": original["path"], **definition["cases"][0]}]
        value.evidence["repair_scope"] = scope
        with self.assertRaisesRegex(PortError, "Missing affected original"):
            verify_repair(value, scope, manifest, rules, catalog)
        result = {"path": original["path"], "blob_sha": original["blob_sha"],
                  "cases": [{**definition["cases"][0], "result": "PASS",
                             "evidence": value.evidence["acceptance"][0]["evidence"]}]}
        value.evidence["source_tests"] = [result]
        self.assertFalse(verify_repair(value, scope, manifest, rules, catalog)["wp_complete"])
        result["cases"][0]["parameter_family"] = "invented"
        with self.assertRaises(PortError):
            verify_repair(value, scope, manifest, rules, catalog)


class RepairExecutionTests(unittest.TestCase):
    def test_authoritative_hosted_report_requires_nonzero_successful_affected_modules(self):
        modules = ["feature/map", "core/maps"]
        self.assertTrue(validate_build_log(build_log(modules), modules)["positive_discovery"])
        for kind in ("zero", "failed", "skipped", "malformed", "missing", "tasks", "ambiguous", "other-step"):
            log = build_log(modules)
            if kind in ("zero", "failed", "skipped", "malformed"):
                value = {"discovered": 3, "run": 3, "passed": 3, "failed": 0, "errors": 0, "skipped": 0}
                if kind == "zero":
                    value = {k: 0 for k in value}
                elif kind == "malformed":
                    value["passed"] = "3"
                else:
                    value[kind] = 1
                log = build_log(modules, module_unit_tests={"feature/map": value, "core/maps": value})
            elif kind == "missing":
                log = build_log(["core/maps"])
            elif kind == "tasks":
                log = build_log(modules, tasks=[":fake:test"])
            elif kind == "ambiguous":
                log += "\n" + log
            else:
                log = log.replace(LOG_STEP, "A candidate-written summary")
            with self.subTest(kind=kind), self.assertRaises(PortError):
                validate_build_log(log, modules)

    def test_hosted_method_maps_to_actual_candidate_test_and_verified_module(self):
        manifest, _, _, scope = fixture()
        executed = {"core/maps": [{"class": "MapTest", "method": "keepsListReachable[31]"}]}
        with patch("controller.staging.object_bytes", return_value=b"@Test fun keepsListReachable() { assertTrue(true) }"):
            validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, executed)
        for kind in ("blob", "method", "module"):
            changed = copy.deepcopy(scope)
            if kind == "module":
                changed["coverage"][0]["modules"] = ["feature/map"]
            with patch("controller.staging.object_bytes", return_value=b"fun different() {}"), self.assertRaises(PortError):
                validate_hosted_methods(changed, {TEST: "5" * 40 if kind == "blob" else "4" * 40}, manifest.repo, executed)
        for inventory in ({}, {"core/maps": [{"class": "MapTest", "method": "unrelated"}]}):
            with patch("controller.staging.object_bytes", return_value=b"@Test fun keepsListReachable() {}"), self.assertRaises(PortError):
                validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, inventory)

    def test_hosted_method_accepts_quoted_annotation_arguments_without_treating_them_as_syntax(self):
        manifest, _, _, scope = fixture()
        executed = {"core/maps": [{"class": "MapTest", "method": "keepsListReachable[31]"}]}
        for annotations in (
            '@Test\n@OriginalCase("TimelineTests::older page preserves anchor()")',
            '@org.junit.Test(timeout = 1000)\n@OriginalCase("case with \\"quotes\\" and ()")',
            '@OriginalCase("literal @Test() is only text")\n@Test',
            '@Test\n@Cases(values = [Case("nested )"), Case("other(")])',
            '@Test\n@OriginalCase("""raw "quotes" and ()""")',
            '@Test\n@OriginalCase(""""quoted"""")',
            '@Test\n@OriginalCase("""""two quotes""""")',
            '@Test\n@OriginalCase("${"nested ()"}")',
            '@Test /* outer /* @NotTest ) */ still comment */\n@OriginalCase("case()")',
        ):
            source = (annotations + "\nfun keepsListReachable() {}").encode()
            with self.subTest(annotations=annotations), patch(
                "controller.staging.object_bytes", return_value=source,
            ):
                validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, executed)

        for quotes in (4, 5):
            source = (
                'val ordinary = """content' + '"' * quotes
                + '\n@Test fun keepsListReachable() {}'
            ).encode()
            with self.subTest(closing_quotes=quotes), patch(
                "controller.staging.object_bytes", return_value=source,
            ):
                validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, executed)

        scope = copy.deepcopy(scope)
        scope["coverage"][0]["hosted_methods"][0]["id"] = "older page preserves anchor"
        executed["core/maps"][0]["method"] = "older page preserves anchor[31]"
        with patch("controller.staging.object_bytes", return_value=(
            b'@Test @OriginalCase("case()") internal fun `older page preserves anchor`() {}'
        )):
            validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, executed)

    def test_annotation_argument_cannot_impersonate_a_junit_annotation(self):
        manifest, _, _, scope = fixture()
        executed = {"core/maps": [{"class": "MapTest", "method": "keepsListReachable"}]}
        for annotations in (
            '@OriginalCase("@Test")',
            '@OriginalCase("@org.junit.Test()")',
            '@NotTest\n@OriginalCase("case()")',
        ):
            source = (annotations + "\nfun keepsListReachable() {}").encode()
            with self.subTest(annotations=annotations), patch(
                "controller.staging.object_bytes", return_value=source,
            ), self.assertRaisesRegex(PortError, "JUnit test declaration"):
                validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, executed)

    def test_hosted_junit_annotation_must_be_attached_to_the_selected_declaration(self):
        manifest, _, _, scope = fixture()
        executed = {"core/maps": [{"class": "MapTest", "method": "keepsListReachable"}]}
        for prefix in (
            "// @Test",
            "/* @Test */",
            "/* nested /* comment */ @Test */",
            'val example = "@Test fun keepsListReachable() {}"',
            'val example = """@Test fun keepsListReachable() {}"""',
            'val example = "${"@Test fun keepsListReachable() {}"}"',
            "@Test fun other() {}",
            "@Test val unrelated = 1",
            "@my.Test",
            '@OriginalCase("""literal @Test()""")',
            '@OriginalCase(""""literal @Test()"""")',
            '@OriginalCase("""""literal @Test()""""")',
            '@OriginalCase("${"literal @Test()"}")',
        ):
            source = (prefix + "\nfun keepsListReachable() {}").encode()
            with self.subTest(prefix=prefix), patch(
                "controller.staging.object_bytes", return_value=source,
            ), self.assertRaisesRegex(PortError, "JUnit test declaration"):
                validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, executed)

    def test_malformed_hosted_annotation_syntax_fails_closed(self):
        manifest, _, _, scope = fixture()
        executed = {"core/maps": [{"class": "MapTest", "method": "keepsListReachable"}]}
        for prefix in (
            '@Test @OriginalCase("unterminated)',
            '@Test @OriginalCase("case()"',
            '@Test @Cases([Case("case()"))',
            "@Test /* unterminated",
            '@Test @OriginalCase("${"unterminated template"}',
        ):
            source = (prefix + "\nfun keepsListReachable() {}").encode()
            with self.subTest(prefix=prefix), patch(
                "controller.staging.object_bytes", return_value=source,
            ), self.assertRaises(PortError):
                validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, executed)

    def transcript(self, outcomes):
        failed = sum(status in ("-1", "-2") for _, status in outcomes)
        executed = len(outcomes) - sum(status == "-3" for _, status in outcomes)
        completion = (f"FAILURES!!!\nTests run: {executed},  Failures: {failed}"
                      if failed else f"OK ({executed} tests)")
        return "\n".join(
            f"INSTRUMENTATION_STATUS: class=NativeTest\nINSTRUMENTATION_STATUS: test={method}\n"
            f"INSTRUMENTATION_STATUS: current={index}\nINSTRUMENTATION_STATUS: numtests={len(outcomes)}\n"
            f"INSTRUMENTATION_STATUS_CODE: {code}"
            for index, (method, status) in enumerate(outcomes, 1) for code in ("1", status)
        ) + f"\nINSTRUMENTATION_RESULT: stream=\n\nTime: 0.123\n\n{completion}\n\nINSTRUMENTATION_CODE: -1\n"

    def test_native_results_keep_unrelated_failure_but_never_accept_affected_failure_or_skip(self):
        text = self.transcript([("affected", "0"), ("unrelated", "-2")])
        self.assertEqual(instrumentation_results(text), {"NativeTest#affected": "0", "NativeTest#unrelated": "-2"})
        for text in ("", text + text, text.replace("INSTRUMENTATION_CODE: -1", "")):
            with self.assertRaises(PortError):
                instrumentation_results(text)
        valid = self.transcript([("affected", "0")])
        for malformed in (
            valid.replace("INSTRUMENTATION_STATUS_CODE: 1\n", "", 1),
            valid.replace("INSTRUMENTATION_STATUS_CODE: 1\n",
                          "INSTRUMENTATION_STATUS_CODE: 1\n" + valid.split("INSTRUMENTATION_STATUS_CODE: 1\n")[0] +
                          "INSTRUMENTATION_STATUS_CODE: 1\n", 1),
            valid.replace("INSTRUMENTATION_STATUS: class=NativeTest",
                          "INSTRUMENTATION_STATUS: class=NativeTest\nINSTRUMENTATION_STATUS: class=NativeTest", 1),
            valid.replace("INSTRUMENTATION_STATUS: test=affected", "INSTRUMENTATION_STATUS: test=wrong", 1),
            valid.replace("numtests=1", "numtests=2"),
            valid.replace("current=1", "current=2"),
            valid + "INSTRUMENTATION_CODE: -1\n",
            valid + "INSTRUMENTATION_CODE: 0\n",
            valid + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n",
            valid.replace("INSTRUMENTATION_CODE: -1", "prefix INSTRUMENTATION_CODE: -1"),
            valid.replace("OK (1 tests)", "FAILURES!!!\nTests run: 1,  Failures: 1"),
            valid.replace("OK (1 tests)", "OK (2 tests)"),
            valid.replace("Time: 0.123", ""),
            valid.replace("INSTRUMENTATION_RESULT: stream=\n\nTime: 0.123\n\nOK (1 tests)\n\n", ""),
            valid.replace("OK (1 tests)", "OK (1 tests)\nOK (1 tests)"),
        ):
            with self.subTest(malformed=malformed), self.assertRaises(PortError):
                instrumentation_results(malformed)
        two = self.transcript([("affected", "0"), ("other", "0")])
        for malformed in (
            two.replace("test=other", "test=affected"),
            two.replace("numtests=2", "numtests=3", 1),
            two.replace("current=2", "current=1"),
        ):
            with self.subTest(malformed=malformed), self.assertRaises(PortError):
                instrumentation_results(malformed)
        failing = self.transcript([("affected", "0"), ("unrelated", "-2")])
        for malformed in (
            failing.replace("Failures: 1", "Failures: 0"),
            failing.replace("FAILURES!!!\nTests run: 2,  Failures: 1", "OK (2 tests)"),
        ):
            with self.assertRaises(PortError):
                instrumentation_results(malformed)

    def test_retained_native_scope_requires_both_apis_exact_source_fixture_and_all_failures(self):
        _, _, _, scope = fixture()
        scope["coverage"][0].update(native_methods=["NativeTest#affected"], hosted_methods=[])
        raw = self.transcript([("affected", "0"), ("unrelated", "-2")]).encode()
        records = [{
            "revision": MERGE, "transcript": f"docs/android/evidence/WP-503/device-repairs-full-api{api}.txt",
            "sha256": hashlib.sha256(raw).hexdigest(), "api": api, "methods": ["NativeTest#affected"],
            "bindings": {PATH: "5" * 40}, "fixture": {"path": TEST, "blob_sha": "4" * 40},
            "out_of_scope_failures": {"NativeTest#unrelated": "Outside the admitted repair; original failure retained."},
            "execution": {},
        } for api in (31, 37)]
        scope["native"] = records
        class Native:
            def get(self, path):
                return {"sha": "4" * 40 if TEST in path else "5" * 40}
        with patch("controller.repair_acceptance.retained_file", return_value=raw), \
                patch("controller.repair_acceptance.retained_repairs_execution", return_value=BASE):
            self.assertFalse(verify_native(Native(), scope, {PATH: "5" * 40})["standalone_head_execution"])
            for kind in ("api", "source", "fixture", "hidden-failure", "excluded-affected", "duplicate-capture"):
                changed = copy.deepcopy(scope)
                tree = {PATH: "5" * 40}
                if kind == "api":
                    changed["native"] = records[:1]
                elif kind == "source":
                    tree[PATH] = "6" * 40
                elif kind == "fixture":
                    changed["native"][0]["fixture"]["blob_sha"] = "6" * 40
                elif kind == "hidden-failure":
                    changed["native"][0]["out_of_scope_failures"] = {}
                elif kind == "duplicate-capture":
                    changed["native"][1].update(
                        transcript=changed["native"][0]["transcript"],
                        sha256=changed["native"][0]["sha256"],
                    )
                else:
                    changed["native"][0]["out_of_scope_failures"]["NativeTest#affected"] = "Must not exclude"
                with self.subTest(kind=kind), self.assertRaises(PortError):
                    verify_native(Native(), changed, tree)
        for status in ("-1", "-2", "-3", "-4"):
            failed = self.transcript([("affected", status), ("unrelated", "-2")]).encode()
            with patch("controller.repair_acceptance.retained_file", return_value=failed), \
                    patch("controller.repair_acceptance.retained_repairs_execution", return_value=BASE), \
                    self.assertRaises(PortError):
                verify_native(Native(), scope, {PATH: "5" * 40})

    def test_actual_legacy_execution_binds_api_source_apks_and_executed_fixture(self):
        manifest, rules, _, _ = fixture()
        text = b"class NativeTest { @Test fun affected() {} }"
        blob = hashlib.sha1(b"blob " + str(len(text)).encode() + b"\0" + text).hexdigest()
        root = "docs/android/evidence/WP-503/"
        item = {
            "revision": MERGE, "api": 31, "transcript": root + "device-repairs-full-api31.txt",
            "methods": ["NativeTest#affected"], "bindings": {PATH: "5" * 40},
            "fixture": {"path": TEST, "blob_sha": blob},
            "execution": {"format": "wp503-device-repairs-v1", **{
                key: {"path": root + name, "sha256": "a" * 64} for key, name in (
                    ("integration", "device-repairs-integration.json"),
                    ("inputs", "device-repairs-inputs.json"),
                    ("metrics", "device-repairs-full-api31-metrics.txt"),
                )
            }},
        }
        integration = {
            "repository": rules["repository"], "work_package": "WP-503",
            "source": manifest.data["reference"]["commit"],
            "shared_nodes_registration_integration": {
                "head": BASE, "native_api31": {"full": {"discovered": 1, "passed": 1}},
            },
        }
        inputs = {"head": BASE, "apks": {
            name: {"size": 42, "sha256": "a" * 64}
            for name in ("wp503-device-app.apk", "wp503-device-test.apk")
        }}
        class Native:
            policy = rules
            def __init__(self):
                self.manifest = manifest
                self.fixture = text
                self.source = "5" * 40
            def get(self, path):
                return ({"sha": blob, "content": base64.b64encode(self.fixture).decode(), "encoding": "base64"}
                        if TEST in path else {"sha": self.source})
        for kind in ("positive", "api-relabel", "nonexecuted-source", "wrong-fixture", "missing-apk",
                     "wrong-run", "missing-method", "wrong-capture"):
            native, changed = Native(), copy.deepcopy(item)
            bound_inputs = copy.deepcopy(inputs)
            metrics = b"WP503_DEVICE_PROFILE|sdk=31|fontScale=2.0\n"
            if kind == "api-relabel":
                metrics = b"WP503_DEVICE_PROFILE|sdk=37|fontScale=2.0\n"
            elif kind == "nonexecuted-source":
                native.source = "6" * 40
            elif kind == "wrong-fixture":
                native.fixture = b"class Wrong {}"
            elif kind == "missing-apk":
                bound_inputs["apks"].pop("wp503-device-test.apk")
            elif kind == "wrong-run":
                bound_inputs["head"] = HEAD
            elif kind == "missing-method":
                changed["methods"] = ["NativeTest#notExecuted"]
            elif kind == "wrong-capture":
                changed["transcript"] = root + "device-repairs-full-api37.txt"
            records = {
                root + "device-repairs-integration.json": json.dumps(integration).encode(),
                root + "device-repairs-inputs.json": json.dumps(bound_inputs).encode(),
                root + "device-repairs-full-api31-metrics.txt": metrics,
            }
            with self.subTest(kind=kind), patch(
                "controller.repair_acceptance.retained_file",
                side_effect=lambda _, revision, path, sha: records[path],
            ):
                if kind == "positive":
                    self.assertEqual(retained_repairs_execution(native, changed, {"NativeTest#affected": "0"}), BASE)
                else:
                    with self.assertRaises(PortError):
                        retained_repairs_execution(native, changed, {"NativeTest#affected": "0"})


class RepairAuthorityTests(unittest.TestCase):
    def fixture(self):
        helper = test_native_review.NativeReviewTests()
        native, api, prefix, _ = helper.fixture()
        manifest, rules, value, scope = fixture()
        native.manifest, native.policy = manifest, rules
        native.backend.manifest, native.backend.policy = manifest, rules
        pr = api.routes[("GET", prefix + "/pulls/123")]
        pr["body"] = "<!-- android-port-wp:WP-312 -->"
        api.routes[("GET", prefix + "/pulls/123/files?per_page=100&page=1")] = [{"filename": PATH}]
        api.routes[("GET", prefix + "/issues/comments/700")] = {
            "id": 700, "user": {"login": "cbattlegear", "type": "User"},
            "issue_url": f"https://api.github.com/repos/{rules['repository']}/issues/224",
            "body": SCOPE_MARKER + json.dumps(scope),
        }
        api.routes[("GET", prefix + "/issues/224")] = {"number": 224}
        api.routes[("GET", prefix + "/pulls/140")] = {
            "number": 140, "merged": True, "body": "<!-- android-port-wp:WP-312 -->", "merge_commit_sha": MERGE,
            "base": {"ref": "main", "repo": {"full_name": rules["repository"]}},
        }
        api.routes[("GET", prefix + "/pulls/140/files?per_page=100&page=1")] = [{"filename": PATH}]
        api.routes[("GET", prefix + f"/compare/{MERGE}...{BASE}")] = {"status": "ahead"}
        staged = {"binding": asdict(value.binding), "changed_paths": [PATH]}
        review = api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")][0]
        review["body"] = REVIEW_MARKER + json.dumps({
            "schema_version": 2, "binding": asdict(value.binding), "method": "authenticated-coordinator-read-only",
            "staging_sha256": digest(staged), "evidence": value.evidence, "review": value.review,
            "approvals": [], "scope_comment_id": 700,
        })
        api.routes[("GET", prefix + "/actions/runs/1/attempts/1/jobs?per_page=100&page=1")] = {"jobs": [{
            "id": 99, "name": "One Linux Android build", "head_sha": HEAD,
            "run_id": 1, "status": "completed", "conclusion": "success",
        }]}
        from controller.runtime_inputs import required_inputs
        entries = {p: "6" * 40 for p in required_inputs()}
        entries.update({"docs/android/test-cases.json": "7" * 40, PATH: "5" * 40, TEST: "4" * 40})
        return native, api, prefix, value, scope, staged, entries

    def test_authenticated_scope_binds_issue_owner_landed_wp_and_actual_ancestry(self):
        native, api, prefix, value, scope, _, _ = self.fixture()
        self.assertEqual(authenticated_scope(native, 700, value.binding), scope)
        for kind in ("unmerged", "ancestry", "comment-author", "issue", "owner", "head"):
            native, api, prefix, value, _, _, _ = self.fixture()
            if kind == "unmerged":
                api.routes[("GET", prefix + "/pulls/140")]["merged"] = False
            elif kind == "ancestry":
                api.routes[("GET", prefix + f"/compare/{MERGE}...{BASE}")] = {"status": "diverged"}
            else:
                comment = api.routes[("GET", prefix + "/issues/comments/700")]
                if kind == "comment-author":
                    comment["user"]["login"] = "candidate"
                elif kind == "issue":
                    comment["issue_url"] += "0"
                else:
                    payload = json.loads(comment["body"][len(SCOPE_MARKER):])
                    if kind == "owner":
                        payload["owner"] = "other"
                    else:
                        payload["binding"]["head_sha"] = BASE
                    comment["body"] = SCOPE_MARKER + json.dumps(payload)
            with self.subTest(kind=kind), self.assertRaises(PortError):
                authenticated_scope(native, 700, value.binding)

    def test_native_transport_evaluates_bounded_repair_with_real_hosted_log_contract(self):
        native, api, _, value, _, staged, entries = self.fixture()
        def objects(repo, blob):
            return b"@Test fun keepsListReachable() { check(true) }" if blob == "4" * 40 else (
                json.dumps({"schema_version": 1, "source_sha": value.binding.source_sha, "entries": []}).encode()
            )
        with patch("controller.native_review.stage_review", return_value=staged), \
                patch("controller.native_review.tree", return_value=entries), \
                patch("controller.native_review.object_bytes", side_effect=objects), \
                patch("controller.staging.object_bytes", side_effect=objects), \
                patch("controller.repair_acceptance.read_job_log",
                      return_value=build_log(["feature/map", "core/maps"])):
            actual, _ = native.evaluate("WP-312", 123, 90)
            self.assertEqual(actual.evidence["schema_version"], 3)
            self.assertFalse(any(c[0] != "GET" and not c[3] for c in api.calls))
            with self.assertRaisesRegex(PortError, "cannot complete"):
                verify_completion(actual.pr, actual.evidence, actual.review, actual.approvals,
                                  actual.binding, native.manifest, native.policy, actual.catalog)

    def test_skipped_affected_build_and_changed_runner_cannot_attest_repair(self):
        for kind in ("skipped", "changed-runner"):
            native, api, prefix, value, scope, _, entries = self.fixture()
            if kind == "skipped":
                api.routes[("GET", prefix + "/actions/runs/1/attempts/1/jobs?per_page=100&page=1")]["jobs"][0]["conclusion"] = "skipped"
            base = dict(entries)
            if kind == "changed-runner":
                entries["tools/android-port/controller/ci.py"] = "8" * 40
            with patch("controller.native_review.tree", return_value=base), self.assertRaises(PortError):
                native.verify_repair_execution(value, scope, {"id": 1, "run_attempt": 1}, entries)

    def test_bounded_publication_replays_without_becoming_full_wp_completion(self):
        native, api, prefix, value, _, staged, entries = self.fixture()
        api.routes[("GET", prefix + "/actions/runs/2/attempts/1/jobs?per_page=100&page=1")]["jobs"][0]["name"] = (
            "Native review WP-312 PR 123 receipt 90"
        )
        api.routes[("GET", prefix + "/actions/runs/2")]["status"] = "in_progress"
        next_id = iter((201, 202))
        api.routes[("POST", prefix + "/check-runs")] = lambda payload: {"id": next(next_id)}
        for check_id in (201, 202):
            api.routes[("PATCH", prefix + f"/check-runs/{check_id}")] = {}
        def objects(repo, blob):
            if blob == "4" * 40:
                return b"@Test fun keepsListReachable() { check(true) }"
            return json.dumps({"schema_version": 1, "source_sha": value.binding.source_sha, "entries": []}).encode()
        with patch("controller.native_review.stage_review", return_value=staged), \
                patch("controller.native_review.tree", return_value=entries), \
                patch("controller.native_review.object_bytes", side_effect=objects), \
                patch("controller.native_review.git", return_value=BASE.encode()), \
                patch("controller.staging.object_bytes", side_effect=objects), \
                patch("controller.repair_acceptance.read_job_log",
                      return_value=build_log(["feature/map", "core/maps"])):
            result = native.publish("WP-312", 123, 90, 2, 1)
            self.assertEqual(result["acceptance_kind"], "bounded-repair")
            self.assertFalse(result["wp_complete"])
            self.assertFalse(result["dependency_unlock"])
            checks = []
            created = [call[2] for call in api.calls if call[0] == "POST" and call[1].endswith("/check-runs")]
            for index, (_, _, body, _) in enumerate(call for call in api.calls if call[0] == "PATCH"):
                check = copy.deepcopy(created[index])
                check.update(body)
                check.update(id=900 + index, app={"id": 10})
                checks.append(check)
            existing = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1")]["check_runs"]
            existing[:] = [check for check in existing if check["name"] == "android-ci"] + checks
            api.routes[("GET", prefix + "/actions/runs/2")]["status"] = "completed"
            replayed = native.bundle("WP-312", Identity(pr_number=123))
            with self.assertRaisesRegex(PortError, "cannot complete"):
                verify_completion(replayed.pr, replayed.evidence, replayed.review, replayed.approvals,
                                  replayed.binding, native.manifest, native.policy, replayed.catalog)
            api.routes[("GET", prefix + "/issues/comments/700")]["body"] += "\nnot-json"
            with self.assertRaises(PortError):
                native.bundle("WP-312", Identity(pr_number=123))

    def test_repair_stage_is_data_only_and_scope_is_bound(self):
        native, api, _, value, scope, staged, entries = self.fixture()
        catalog = json.dumps({"schema_version": 1, "source_sha": value.binding.source_sha, "entries": []}).encode()
        with patch("controller.native_review.stage_review", return_value=staged), \
                patch("controller.native_review.tree", return_value=entries), \
                patch("controller.native_review.object_bytes", return_value=catalog):
            result = native.stage_repair("WP-312", 123, 700)
        self.assertEqual(result["state"], "staged-data-only")
        self.assertEqual(result["repair_scope_sha256"], digest(scope))
        self.assertFalse(result["authoritative"])
        self.assertFalse(any(call[0] != "GET" and not call[3] for call in api.calls))
