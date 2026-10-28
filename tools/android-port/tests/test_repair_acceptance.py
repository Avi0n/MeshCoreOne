"""AndroidOnly: WP-003 Bounded repair acceptance never replaces full-WP completion."""

import base64
import copy
import hashlib
import json
import unittest
from dataclasses import asdict, replace
from unittest.mock import patch

from fixtures import BASE, HEAD, MERGE, REPO, base_manifest, bundle, policy
from controller.engine import GateBundle
from controller.errors import PortError
from controller.gates import Binding, policy_revision, verify_completion, verify_pr_gates
from controller.ledger import Identity
from controller.native_review import REVIEW_MARKER
from controller.repair_acceptance import (
    COMPRESSED_SCOPE_MARKER, SCOPE_MARKER, authenticated_scope, instrumentation_results, validate_build_log,
    validate_hosted_methods as validate_compiled_hosted_methods, validate_scope, verify_native, verify_repair,
    retained_repairs_execution, scoped_candidate_sources, scoped_retained_sources,
)
from controller.schema import digest, load_json
from controller.publication import encode_structured_body
import test_native_review

PATH = "android/feature/map/src/main/kotlin/com/meshcoreone/android/feature/map/MapEntry.kt"
TEST = "android/core/maps/src/test/kotlin/com/meshcoreone/android/core/maps/MapTest.kt"
METHOD = {"path": TEST, "blob_sha": "4" * 40, "id": "keepsListReachable"}
CONSUMER_TEST = "android/app/src/test/kotlin/com/meshcoreone/android/app/ConsumerTest.kt"
LOG_STEP = "Run every selected Linux and Android task in one Gradle invocation"


def validate_hosted_methods(scope, candidate_tree, repo, executed, compiled_sources=None):
    if compiled_sources is None:
        compiled_sources = {}
        for path, blob in candidate_tree.items():
            parts = path.split("/")
            if len(parts) > 3 and parts[0] == "android":
                module = parts[1] if parts[1] == "app" else "/".join(parts[1:3])
                compiled_sources.setdefault(module, {})[path] = blob
    return validate_compiled_hosted_methods(scope, candidate_tree, repo, executed, compiled_sources)


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


def whole_fixture():
    manifest, rules, value, repair = fixture()
    full = bundle(manifest, rules, "WP-312", changed_paths=[PATH], merged=False)
    originals = {
        entry["path"] for entry in manifest.inputs("WP-312", False)
        if entry["kind"] in ("test", "support") and entry["exclusion"] is None
    }
    catalog = copy.deepcopy(full.catalog)
    catalog["entries"] = [entry for entry in catalog["entries"] if entry["path"] in originals]
    scope = {
        "schema_version": 3, "kind": "whole-wp", "binding": asdict(value.binding),
        "owner": repair["owner"], "paths": copy.deepcopy(repair["paths"]),
        "acceptance": copy.deepcopy(manifest.wp("WP-312")["acceptance"]),
        "coverage": copy.deepcopy(repair["coverage"]), "verification": ["android-scaffold"], "native": [],
    }
    scope["coverage"][0]["cases"] = [
        {"path": entry["path"], **case} for entry in catalog["entries"] for case in entry["cases"]
    ]
    url = f"https://github.com/{rules['repository']}/actions/runs/1/attempts/1"
    acceptance = [{"id": entry["id"], "result": "PASS", "evidence": [url]} for entry in scope["acceptance"]]
    results = [copy.deepcopy(result) for result in full.evidence["source_tests"] if result["path"] in originals]
    for result in results:
        for case in result["cases"]:
            case["evidence"] = [url]
    evidence = {
        "schema_version": 4, **asdict(value.binding), "changed_paths": [PATH],
        "runs": [{"check": "android-ci", "run_id": 1, "run_attempt": 1}],
        "retained_artifacts": [], "acceptance": acceptance, "source_tests": results,
        "verification_scope": scope,
    }
    review = {**value.review, "acceptance": acceptance, "human_gate_required": False}
    return manifest, rules, replace(value, evidence=evidence, review=review, catalog=catalog), scope


def build_log(modules, **changes):
    from controller.ci import TASKS

    count = {"discovered": 3, "run": 3, "passed": 3, "failed": 0, "errors": 0, "skipped": 0}
    report = {"stage": "scaffold", "result": "success", "strict_verification": True,
              "tasks": TASKS["scaffold"], "module_unit_tests": {m: copy.deepcopy(count) for m in modules},
              "compiled_test_sources": {m: {} for m in [*modules, "core/protocol", "core/contracts", "app"]},
              "unit_test_methods": {m: [{"class": "MapTest", "method": name}
                                      for name in ("keepsListReachable", "unrelatedOne", "unrelatedTwo")]
                                    for m in modules}}
    if "core/maps" in report["compiled_test_sources"]:
        report["compiled_test_sources"]["core/maps"][TEST] = "4" * 40
    report["compiled_test_sources"]["app"][CONSUMER_TEST] = "4" * 40
    report.update(changes)
    return "\n".join(f"One Linux Android build\t{LOG_STEP}\t2026-10-19T00:00:00Z {line}"
                     for line in json.dumps(report, indent=2).splitlines())


class RepairContractTests(unittest.TestCase):
    def test_scoped_candidate_sources_include_consumer_tests_and_native_bindings_without_conflicts(self):
        _, _, _, scope = fixture()
        scope["coverage"][0]["production_bindings"] = {PATH: "5" * 40}
        scope["producer_bindings"] = {PATH: "5" * 40}
        scope["native"] = [{"bindings": {PATH: "5" * 40, CONSUMER_TEST: "6" * 40}}]
        self.assertEqual(scoped_candidate_sources(scope),
                         {TEST: "4" * 40, PATH: "5" * 40, CONSUMER_TEST: "6" * 40})
        scope["native"][0]["bindings"][PATH] = "7" * 40
        with self.assertRaisesRegex(PortError, "Conflicting"):
            scoped_candidate_sources(scope)

    def test_scoped_retained_fixtures_bind_each_observation_and_reject_conflicts(self):
        _, _, _, scope = fixture()
        item = {"revision": BASE, "fixture": {"path": TEST, "blob_sha": "4" * 40}}
        scope["native"] = [item, copy.deepcopy(item)]
        self.assertEqual(scoped_retained_sources(scope), {(BASE, TEST): "4" * 40})
        scope["native"][1]["fixture"]["blob_sha"] = "6" * 40
        with self.assertRaisesRegex(PortError, "Conflicting"):
            scoped_retained_sources(scope)
        scope["native"][1]["revision"] = HEAD
        self.assertEqual(scoped_retained_sources(scope),
                         {(BASE, TEST): "4" * 40, (HEAD, TEST): "6" * 40})

    def test_scoped_repair_and_whole_wp_accept_explicit_cross_owner_cases_without_transferring_ownership(self):
        source = "MC1Services/Tests/MC1ServicesTests/SyncCoordinatorMessageHandlerTests.swift"
        original_catalog = load_json(REPO / "docs" / "android" / "test-cases.json")
        definition = next(entry for entry in original_catalog["entries"] if entry["path"] == source)
        wanted = {
            "channel save with undecryptable RX harvests no extras and still broadcasts",
            "duplicate channel receive with unknown path does not save a second message",
            "inbound DM stamps contact lastHeard",
            "inbound channel message does not stamp lastHeard on a contact",
            "live channel receive with undecryptable RX does not copy regionScope onto Message",
            "refreshBlockedContactsCache does not cache non-blocked contacts",
            "refreshBlockedContactsCache loads blocked contacts by name",
            "refreshBlockedContactsCache replaces previous cache",
            "same-path duplicate channel receive inserts nothing",
        }
        cases = [case for case in definition["cases"] if case["id"].removeprefix(
            "SyncCoordinatorMessageHandlerTests::"
        ).removesuffix("()") in wanted]
        self.assertEqual(len(cases), 9)
        self.assertGreater(len(definition["cases"]), len(cases))
        for whole in (False, True):
            manifest, rules, value, scope = whole_fixture() if whole else fixture()
            original = next(entry for entry in manifest.data["inventory"] if entry["path"] == source)
            self.assertEqual(original["primary_owner"], "WP-214")
            catalog = copy.deepcopy(value.catalog) if whole else {
                "schema_version": 1, "source_sha": value.binding.source_sha, "entries": [],
            }
            catalog["entries"].append(copy.deepcopy(definition))
            scope["coverage"][0]["source_paths"].append(source)
            scope["coverage"][0]["cases"].extend({"path": source, **case} for case in cases)
            evidence = copy.deepcopy(value.evidence)
            evidence["verification_scope" if whole else "repair_scope"] = scope
            evidence["source_tests"].append({
                "path": source, "blob_sha": original["blob_sha"],
                "cases": [{**case, "result": "PASS", "evidence": [evidence["acceptance"][0]["evidence"][0]]}
                          for case in cases],
            })
            value = replace(value, evidence=evidence, catalog=catalog)
            if whole:
                verify_pr_gates(value.pr, evidence, value.review, [], value.binding, manifest, rules, catalog)
                missing_primary_scope = copy.deepcopy(scope)
                missing_primary_scope["coverage"][0]["cases"] = [
                    case for case in missing_primary_scope["coverage"][0]["cases"] if case["path"] == source
                ]
                missing_primary_evidence = copy.deepcopy(evidence)
                missing_primary_evidence["verification_scope"] = missing_primary_scope
                missing_primary_evidence["source_tests"] = [
                    result for result in evidence["source_tests"] if result["path"] == source
                ]
                with self.assertRaises(PortError):
                    verify_pr_gates(value.pr, missing_primary_evidence, value.review, [], value.binding,
                                    manifest, rules, catalog)
            else:
                self.assertFalse(verify_repair(value, scope, manifest, rules, catalog)["wp_complete"])
            self.assertEqual(original["primary_owner"], "WP-214")
            for kind in ("missing-reference", "unmapped-source", "unknown-source", "excluded-source",
                         "unknown-case", "missing-result", "missing-family", "stale-source", "malformed-cases"):
                altered_scope, altered_evidence = copy.deepcopy(scope), copy.deepcopy(evidence)
                if kind == "missing-reference":
                    altered_scope["coverage"][0]["source_paths"].remove(source)
                elif kind == "unmapped-source":
                    altered_scope["coverage"][0]["cases"] = [
                        case for case in altered_scope["coverage"][0]["cases"] if case["path"] != source
                    ]
                elif kind == "unknown-source":
                    altered_scope["coverage"][0]["source_paths"].append("unknown/Source.swift")
                elif kind == "excluded-source":
                    excluded = next(entry["path"] for entry in manifest.data["inventory"]
                                    if entry["exclusion"] is not None)
                    altered_scope["coverage"][0]["source_paths"].append(excluded)
                elif kind == "unknown-case":
                    altered_scope["coverage"][0]["cases"][-1]["id"] = "not in inventory"
                elif kind == "missing-result":
                    altered_evidence["source_tests"] = [
                        result for result in altered_evidence["source_tests"] if result["path"] != source
                    ]
                elif kind == "missing-family":
                    next(result for result in altered_evidence["source_tests"]
                         if result["path"] == source)["cases"].pop()
                elif kind == "malformed-cases":
                    altered_scope["coverage"][0]["cases"] = None
                else:
                    next(result for result in altered_evidence["source_tests"]
                         if result["path"] == source)["blob_sha"] = "0" * 40
                altered_evidence["verification_scope" if whole else "repair_scope"] = altered_scope
                altered = replace(value, evidence=altered_evidence)
                with self.subTest(whole=whole, kind=kind), self.assertRaises(PortError):
                    if whole:
                        verify_pr_gates(altered.pr, altered_evidence, altered.review, [], altered.binding,
                                        manifest, rules, catalog)
                    else:
                        verify_repair(altered, altered_scope, manifest, rules, catalog)

    def test_shared_whole_scope_requires_complete_cases_and_actual_methods(self):
        manifest, rules, value, scope = whole_fixture()
        verify_pr_gates(value.pr, value.evidence, value.review, [], value.binding, manifest, rules, value.catalog)
        for kind in ("missing-case", "missing-method", "acceptance", "empty-coverage", "foreign-path",
                     "bad-kind", "configured"):
            manifest, rules, value, scope = whole_fixture()
            if kind == "missing-case":
                scope["coverage"][0]["cases"].pop()
            elif kind == "missing-method":
                scope["coverage"][0]["hosted_methods"] = []
            elif kind == "acceptance":
                scope["acceptance"].pop()
            elif kind == "empty-coverage":
                scope["coverage"] = []
            elif kind == "foreign-path":
                scope["paths"][0]["path"] = "tools/android-port/controller/engine.py"
            elif kind == "bad-kind":
                scope["kind"] = "landed-wp-repair"
            else:
                data = copy.deepcopy(manifest.data)
                next(w for w in data["work_packages"] if w["id"] == "WP-312")["verification"]["commands"] = [
                    ["python", "special"]
                ]
                from controller.model import Manifest
                manifest = Manifest(data, manifest.exclusions, manifest.repo)
            with self.subTest(kind=kind), self.assertRaises(PortError):
                verify_pr_gates(value.pr, value.evidence, value.review, [], value.binding, manifest, rules,
                                value.catalog)

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
    def test_whole_scope_accounts_for_every_changed_unit_test_declaration(self):
        manifest, _, _, scope = whole_fixture()
        scope["coverage"].append({
            "path": TEST, "source_paths": scope["coverage"][0]["source_paths"], "cases": [],
            "native_methods": [], "hosted_methods": [METHOD], "adaptation": "Actual unit tests.",
            "modules": ["core/maps"],
        })
        executed = {"core/maps": [{"class": "MapTest", "method": "keepsListReachable"}]}
        with patch("controller.staging.object_bytes", return_value=b"""
            @Test fun keepsListReachable() { check(true) }
            @Test fun anotherCase() { check(true) }
        """), self.assertRaisesRegex(PortError, "every actual changed JUnit method"):
            validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, executed)

    def test_duplicate_test_names_in_distinct_classes_cannot_share_one_execution(self):
        manifest, _, _, scope = whole_fixture()
        scope["coverage"].append({
            "path": TEST, "source_paths": scope["coverage"][0]["source_paths"], "cases": [],
            "native_methods": [], "hosted_methods": [METHOD], "adaptation": "Actual unit tests.",
            "modules": ["core/maps"],
        })
        executed = {"core/maps": [{"class": "MapTest", "method": "keepsListReachable"}]}
        source = b"""
            class MapTest { @Test fun keepsListReachable() {} }
            class OtherTest { @Test fun keepsListReachable() {} }
        """
        with patch("controller.staging.object_bytes", return_value=source), \
                self.assertRaisesRegex(PortError, "duplicate JUnit method name"):
            validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, executed)
        source = b"@OriginalCase(\"one\") @Test fun keepsListReachable() {}"
        with patch("controller.staging.object_bytes", return_value=source):
            validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, executed)

    def test_whole_scope_helper_requires_executed_consumer_without_hiding_test_declarations(self):
        manifest, rules, value, scope = whole_fixture()
        helper = TEST.replace("MapTest.kt", "MapFixtures.kt")
        scope["paths"].append({"path": helper, "operation": "add-or-update-related-test",
                               "capability": "reviewed-support-test"})
        scope["coverage"].append({
            "path": helper, "source_paths": scope["coverage"][0]["source_paths"], "cases": [],
            "native_methods": [], "hosted_methods": [METHOD], "adaptation": "Fixture consumed by unit test.",
            "modules": ["core/maps"],
        })
        validate_scope(scope, value.binding, manifest, rules, [PATH, helper], value.catalog)
        sources = {
            "3" * 40: b"package com.meshcoreone.android.core.maps\nobject MapFixtures {}",
            "4" * 40: b"package com.meshcoreone.android.core.maps\n@Test fun keepsListReachable() {}",
        }
        executed = {"core/maps": [{"class": "com.meshcoreone.android.core.maps.MapTest",
                                    "method": "keepsListReachable"}]}
        with patch("controller.staging.object_bytes", side_effect=lambda repo, blob: sources[blob]):
            validate_hosted_methods(scope, {helper: "3" * 40, TEST: "4" * 40}, manifest.repo, executed)
            sources["3" * 40] += b"\n@Test fun omitted() {}"
            with self.assertRaisesRegex(PortError, "every actual changed JUnit method"):
                validate_hosted_methods(scope, {helper: "3" * 40, TEST: "4" * 40}, manifest.repo, executed)
            for annotation in ("@org.junit.jupiter.api.Test", "@kotlin.test.Test", "@org.junit.Test",
                               "@org.junit.jupiter.api.TestFactory"):
                sources["3" * 40] = (
                    "package com.meshcoreone.android.core.maps\n" + annotation + " fun omitted() {}"
                ).encode()
                with self.subTest(annotation=annotation), self.assertRaisesRegex(
                    PortError, "every actual changed JUnit method",
                ):
                    validate_hosted_methods(scope, {helper: "3" * 40, TEST: "4" * 40}, manifest.repo, executed)
            for annotation in ("@[Test]", "@[org.junit.jupiter.api.Test]", "@[Test Factory]",
                               "@[/* comment */ Test]", "@file:[Suppress(\"x\")]"):
                sources["3" * 40] = (
                    "package com.meshcoreone.android.core.maps\n" + annotation + " fun omitted() {}"
                ).encode()
                with self.subTest(annotation=annotation), self.assertRaisesRegex(
                    PortError, "Grouped Kotlin annotations",
                ):
                    validate_hosted_methods(scope, {helper: "3" * 40, TEST: "4" * 40}, manifest.repo, executed)
            for imported, annotation in (
                ("import org.junit.jupiter.api.Test as Check // reason", "@Check"),
                ("import org.junit.jupiter.api.Test as Check;", "@Check"),
                ("import org.junit.jupiter.api.Test /* reason */ as Check", "@Check"),
                ("import org.junit.jupiter.api.TestFactory as `family test`", "@`family test`"),
                ("import org.junit.jupiter.params.ParameterizedTest as Param; // reason", "@Param"),
                ("import org.junit.jupiter.api.RepeatedTest as Repeat /* reason */", "@Repeat"),
            ):
                sources["3" * 40] = (
                    "package com.meshcoreone.android.core.maps\n" + imported + "\n"
                    + annotation + " internal fun omitted() {}"
                ).encode()
                with self.subTest(imported=imported), self.assertRaisesRegex(
                    PortError, "every actual changed JUnit method",
                ):
                    validate_hosted_methods(scope, {helper: "3" * 40, TEST: "4" * 40}, manifest.repo, executed)

    def test_dynamic_factory_requires_actual_child_bound_to_its_own_factory(self):
        manifest, _, _, scope = whole_fixture()
        scope["coverage"][0]["hosted_methods"][0]["id"] = "parameterFamily"
        source = b"@TestFactory fun parameterFamily() {}"
        with patch("controller.staging.object_bytes", return_value=source):
            records = {"core/maps": [{"class": "MapTest", "method": "actual child",
                                       "factory": "parameterFamily"}]}
            validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, records)
            for record in (
                {"class": "MapTest", "method": "actual child"},
                {"class": "MapTest", "method": "actual child", "factory": "otherFamily"},
                {"class": "OtherTest", "method": "actual child", "factory": "parameterFamily"},
            ):
                with self.subTest(record=record), self.assertRaisesRegex(PortError, "did not actually execute"):
                    validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, {"core/maps": [record]})

    def test_aliased_dynamic_factory_uses_its_actual_executed_child(self):
        manifest, _, _, scope = whole_fixture()
        scope["coverage"][0]["hosted_methods"][0]["id"] = "parameterFamily"
        source = b"import org.junit.jupiter.api.TestFactory as Factory; // preserved alias\n@Factory fun parameterFamily() {}"
        records = {"core/maps": [{"class": "MapTest", "method": "actual child", "factory": "parameterFamily"}]}
        with patch("controller.staging.object_bytes", return_value=source):
            validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, records)
            records["core/maps"][0]["factory"] = "other"
            with self.assertRaisesRegex(PortError, "did not actually execute"):
                validate_hosted_methods(scope, {TEST: "4" * 40}, manifest.repo, records)

    def test_kotlin_typealias_tests_and_unknown_function_annotations_cannot_hide_as_helpers(self):
        from controller.repair_acceptance import _kotlin_junit_methods

        source = """
            typealias Case = org.junit.jupiter.api.Test
            typealias Other = Case
            typealias Family = org.junit.jupiter.api.TestFactory
            @Other fun normal() {}
            @Family fun dynamic() {}
        """
        self.assertEqual(_kotlin_junit_methods(source), {"normal": "test", "dynamic": "factory"})
        for source in (
            "import other.package.Check\n@Check fun hidden() {}",
            "typealias Check = other.package.Check\n@Check fun hidden() {}",
            "typealias One = Two\ntypealias Two = One\n@One fun hidden() {}",
        ):
            with self.subTest(source=source), self.assertRaisesRegex(PortError, "Ambiguous Kotlin function annotation"):
                _kotlin_junit_methods(source)
        manifest, _, _, scope = whole_fixture()
        source = b"import other.package.Check\n@Check fun hidden() {}"
        with patch("controller.staging.object_bytes", return_value=source), \
                self.assertRaisesRegex(PortError, "Ambiguous Kotlin function annotation"):
            validate_hosted_methods(scope, {PATH: "5" * 40, TEST: "4" * 40}, manifest.repo, {})

    def test_grouped_annotations_fail_closed_without_rejecting_literals_or_comments(self):
        from controller.repair_acceptance import _kotlin_junit_methods

        for source in (
            "@[Test] fun omitted() {}",
            "@[org.junit.jupiter.api.Test Suppress(\"reason\")] fun omitted() {}",
            "import org.junit.jupiter.api.Test as Check\n@[Check] fun omitted() {}",
            "@file:[Suppress(\"reason\")]",
            "@get:[Suppress(\"reason\")] val value = 1",
        ):
            with self.subTest(source=source), self.assertRaisesRegex(PortError, "Grouped Kotlin annotations"):
                _kotlin_junit_methods(source)
        source = 'val s = "@[Test]"; /* @[Test] */ @Test fun executed() {}'
        self.assertEqual(_kotlin_junit_methods(source), {"executed": "test"})

    def test_junit_lifecycle_and_standard_helper_annotations_are_not_tests(self):
        from controller.repair_acceptance import _kotlin_junit_methods

        source = """
            import org.junit.Before as Setup
            import org.junit.After
            import org.junit.runners.Parameterized
            import org.robolectric.annotation.Implementation
            import androidx.compose.runtime.Composable
            import kotlin.test.*
            typealias SetupAlias = Setup
            @Suppress("DEPRECATION") @SetupAlias fun before() {}
            @After fun after() {}
            @BeforeTest fun beforeKotlin() {}
            @OptIn(Experimental::class) fun helper() {}
            @Synchronized @Implementation fun shadow() {}
            @Parameterized.Parameters fun data() {}
            @Composable fun content() {}
            @Test fun actual() {}
        """
        self.assertEqual(_kotlin_junit_methods(source), {"actual": "test"})
        real = REPO / "android" / "app" / "src" / "test" / "kotlin" / "com" / "meshcoreone" / "android" / \
            "wp316diagnostic" / "DisconnectedCliTest.kt"
        self.assertTrue(_kotlin_junit_methods(real.read_text(encoding="utf-8")))
        for source in (
            "import other.Check as Before\n@Before fun hidden() {}",
            "typealias Before = other.Check\n@Before fun hidden() {}",
            "@Before fun hidden() {}",
        ):
            with self.subTest(source=source), self.assertRaisesRegex(PortError, "Ambiguous Kotlin function annotation"):
                _kotlin_junit_methods(source)

    def test_full_scaffold_reads_actual_protocol_counts_and_inventory(self):
        from controller.ci import TASKS
        count = {"discovered": 3, "run": 3, "passed": 3, "failed": 0, "errors": 0, "skipped": 0}
        methods = [{"class": "ProtocolTest", "method": f"case{i}"} for i in range(3)]
        report = {"tasks": TASKS["scaffold"] + TASKS["protocol"], "protocol_suite": count,
                  "unit_test_methods": {"core/protocol": methods}}
        methods[0]["factory"] = "vectorFamily"
        proof = validate_build_log(build_log([], **report), ["core/protocol"])
        self.assertEqual(proof["methods"]["core/protocol"], methods)
        for change in ({"tasks": TASKS["scaffold"]}, {"protocol_suite": None},
                       {"unit_test_methods": {}},
                       {"unit_test_methods": {"core/protocol": methods[:1]}}):
            with self.subTest(change=change), self.assertRaises(PortError):
                validate_build_log(build_log([], **(report | change)), ["core/protocol"])

    def test_change_scoped_hosted_report_uses_exact_trusted_candidate_plan(self):
        counts = {"discovered": 1, "run": 1, "passed": 1, "failed": 0, "errors": 0, "skipped": 0}
        plan = {
            "base": BASE, "head": HEAD, "tree": "8" * 40, "scopes": ["module:core/maps"],
            "tasks": [":core:maps:testDebugUnitTest", "validateModuleGraph", "runtimeDependencyInventory"],
        }
        report = {
            "kind": "local-affected-only", "result": "success", **plan,
            "resolved_task_graph": {"result": "no-duplicate-ci-evidence"},
            "suites": {"core/maps": counts},
            "compiled_test_sources": {"core/maps": {TEST: "4" * 40}},
            "junit_cases": [{
                "report": "android/core/maps/build/test-results/testDebugUnitTest/TEST-MapTest.xml",
                "index": 0, "class": "MapTest", "method": "keepsListReachable", "outcome": "passed",
                "factory": "affectedFamily",
            }],
        }
        step = "Run parallel Gradle validation and Android tasks"
        def log_for(value):
            return "\n".join(f"One Linux Android build\t{step}\t2026-10-19T00:00:00Z {line}"
                             for line in json.dumps(value, indent=2).splitlines())

        proof = validate_build_log(log_for(report), ["core/maps"], plan_for_scopes=lambda scopes: plan)
        self.assertEqual(proof["methods"]["core/maps"][0]["method"], "keepsListReachable")
        self.assertEqual(proof["methods"]["core/maps"][0]["factory"], "affectedFamily")
        for kind in ("missing-plan", "wrong-tasks", "stale-head", "zero", "skipped", "duplicate",
                     "wrong-step", "missing-method", "missing-sources", "wrong-source-blob"):
            altered = copy.deepcopy(report)
            provider = lambda scopes: plan
            if kind == "missing-plan":
                provider = None
            elif kind == "wrong-tasks":
                altered["tasks"] = [":fake:test"]
            elif kind == "stale-head":
                altered["head"] = "0" * 40
            elif kind == "zero":
                altered["suites"]["core/maps"] = {key: 0 for key in counts}
            elif kind == "skipped":
                altered["junit_cases"][0]["outcome"] = "skipped"
            elif kind == "duplicate":
                altered["junit_cases"].append(copy.deepcopy(altered["junit_cases"][0]))
            elif kind == "missing-method":
                altered["junit_cases"] = []
            elif kind == "missing-sources":
                altered.pop("compiled_test_sources")
            elif kind == "wrong-source-blob":
                altered["compiled_test_sources"]["core/maps"][TEST] = "not-a-blob"
            log = log_for(altered)
            if kind == "wrong-step":
                log = log.replace(step, "A candidate-written summary")
            with self.subTest(kind=kind), self.assertRaises(PortError):
                validate_build_log(log, ["core/maps"], plan_for_scopes=provider)

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

    def test_full_scaffold_requires_module_method_inventory_and_consistent_suite_counts(self):
        count = {"discovered": 3, "run": 3, "passed": 3, "failed": 0, "errors": 0, "skipped": 0}
        methods = [{"class": "ContractTest", "method": f"case{i}"} for i in range(3)]
        report = {"suites": {"contracts": count}, "unit_test_methods": {"core/contracts": methods}}
        self.assertEqual(validate_build_log(build_log([], **report), ["core/contracts"])["methods"]["core/contracts"],
                         methods)
        for change in (
            {"unit_test_methods": {}},
            {"unit_test_methods": {"core/contracts": methods[:1]}},
            {"module_unit_tests": {"core/contracts": {**count, "passed": 2}}},
        ):
            with self.subTest(change=change), self.assertRaises(PortError):
                validate_build_log(build_log([], **(report | change)), ["core/contracts"])

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

    def test_hosted_method_must_be_in_a_real_module_test_source_set(self):
        manifest, rules, value, scope = fixture()
        for path in (
            "android/core/maps/src/main/kotlin/nested/src/test/kotlin/MapTest.kt",
            "android/core/maps/nested/src/test/kotlin/MapTest.kt",
            "android/core/maps/src/testFixtures/kotlin/MapTest.kt",
        ):
            altered = copy.deepcopy(scope)
            altered["coverage"][0]["hosted_methods"][0]["path"] = path
            with self.subTest(path=path), self.assertRaisesRegex(PortError, "actual immutable test source"):
                validate_scope(altered, value.binding, manifest, rules, [PATH], None)

    def test_hosted_method_requires_exact_compiled_source_for_selected_task(self):
        manifest, _, _, original = fixture()
        scope = copy.deepcopy(original)
        ignored = TEST.replace("/src/test/", "/src/testDebug/")
        scope["coverage"][0]["hosted_methods"][0]["path"] = ignored
        candidate = {TEST: "4" * 40, ignored: "4" * 40}
        executed = {"core/maps": [{"class": "MapTest", "method": "keepsListReachable"}]}
        with patch("controller.staging.object_bytes", return_value=b"@Test fun keepsListReachable() {}"):
            for sources in ({}, {"core/maps": {TEST: "4" * 40}},
                            {"core/maps": {ignored: "5" * 40}}):
                with self.subTest(sources=sources), self.assertRaisesRegex(PortError, "not compiled"):
                    validate_hosted_methods(scope, candidate, manifest.repo, executed, sources)
            validate_hosted_methods(scope, candidate, manifest.repo, executed,
                                    {"core/maps": {ignored: "4" * 40}})

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

    def verification_fixture(self):
        native, api, prefix, value, scope, staged, entries = self.fixture()
        scope.update(schema_version=2, kind="landed-wp-verification")
        scope["paths"] = [{"path": CONSUMER_TEST, "operation": "add-direct-support-wiring",
                           "capability": "app-build-launcher"}]
        scope["coverage"][0].update(
            path=CONSUMER_TEST, modules=["app", "feature/map"],
            hosted_methods=[{"path": CONSUMER_TEST, "blob_sha": "4" * 40, "id": "keepsListReachable"}],
            production_bindings={PATH: "5" * 40},
        )
        value.pr["changed_paths"] = [CONSUMER_TEST]
        value.evidence.update(changed_paths=[CONSUMER_TEST], repair_scope=scope)
        staged["changed_paths"] = [CONSUMER_TEST]
        entries[CONSUMER_TEST] = "4" * 40
        api.routes[("GET", prefix + "/pulls/123/files?per_page=100&page=1")] = [
            {"filename": CONSUMER_TEST, "status": "added"},
        ]
        api.routes[("GET", prefix + "/issues/comments/700")]["body"] = SCOPE_MARKER + json.dumps(scope)
        receipt = json.loads(api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")][0]["body"][
            len(REVIEW_MARKER):
        ])
        receipt.update(staging_sha256=digest(staged), evidence=value.evidence)
        api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")][0]["body"] = (
            REVIEW_MARKER + json.dumps(receipt)
        )
        for revision in (MERGE, BASE, HEAD):
            api.routes[("GET", prefix + f"/contents/{PATH}?ref={revision}")] = {
                "type": "file", "path": PATH, "sha": "5" * 40,
            }

        def regular_tree(repo, *arguments):
            self.assertEqual(arguments[:3], ("ls-tree", "-z", "--full-tree"))
            self.assertEqual(arguments[4], "--")
            revision, path = arguments[3], arguments[5]
            record = api.routes[("GET", prefix + f"/contents/{path}?ref={revision}")]
            return f"100644 blob {record['sha']}\t{record['path']}\0".encode()

        reader = patch("controller.repair_acceptance.git", side_effect=regular_tree)
        reader.start()
        self.addCleanup(reader.stop)
        return native, api, prefix, value, scope, staged, entries

    def test_support_only_repair_binds_unchanged_landed_producer_without_editing_it(self):
        native, api, prefix, value, scope, _, _ = self.fixture()
        consumer = "android/app/src/main/kotlin/com/meshcoreone/android/app/Consumer.kt"
        scope["paths"] = [{"path": consumer, "operation": "modify-related-consumer",
                           "capability": "reviewed-support-consumer"}]
        scope["coverage"][0].update(path=consumer, modules=["app", "core/maps"])
        scope["producer_bindings"] = {PATH: "5" * 40}
        comment = api.routes[("GET", prefix + "/issues/comments/700")]
        for revision in (BASE, HEAD):
            api.routes[("GET", prefix + f"/contents/{PATH}?ref={revision}")] = {
                "type": "file", "path": PATH, "sha": "5" * 40,
            }

        def bind(value):
            comment["body"] = SCOPE_MARKER + json.dumps(value)
            return authenticated_scope(native, 700, value_binding)

        value_binding = value.binding
        valid = f"100644 blob {'5' * 40}\t{PATH}\0".encode()
        with patch("controller.repair_acceptance.git", return_value=valid):
            self.assertEqual(bind(scope), scope)
            validate_scope(scope, value_binding, native.manifest, native.policy, [consumer], None)
            self.assertEqual(scoped_candidate_sources(scope), {TEST: "4" * 40, PATH: "5" * 40})
            for kind in ("missing", "foreign", "bad-sha", "not-landed", "empty", "test-only"):
                altered = copy.deepcopy(scope)
                if kind == "missing":
                    altered.pop("producer_bindings")
                elif kind == "foreign":
                    altered["producer_bindings"] = {consumer: "5" * 40}
                elif kind == "bad-sha":
                    altered["producer_bindings"] = {PATH: "bad"}
                elif kind == "not-landed":
                    api.routes[("GET", prefix + "/pulls/140/files?per_page=100&page=1")] = []
                elif kind == "test-only":
                    altered["paths"][0]["path"] = CONSUMER_TEST
                else:
                    altered["producer_bindings"] = {}
                with self.subTest(kind=kind), self.assertRaises(PortError):
                    bind(altered)
                api.routes[("GET", prefix + "/pulls/140/files?per_page=100&page=1")] = [{"filename": PATH}]
        for raw in (b"", valid.replace(b"100644", b"120000"), valid.replace(b"5" * 40, b"6" * 40)):
            with self.subTest(raw=raw), patch("controller.repair_acceptance.git", return_value=raw), \
                    self.assertRaises(PortError):
                bind(scope)
        altered = copy.deepcopy(scope)
        altered["coverage"][0]["source_paths"] = ["MC1Services/Tests/MC1ServicesTests/SyncCoordinatorMessageHandlerTests.swift"]
        with self.assertRaisesRegex(PortError, "primary-owned original source"):
            validate_scope(altered, value_binding, native.manifest, native.policy, [consumer], None)

    def test_scoped_cross_owner_sources_are_staged_again_for_receipt_validation(self):
        source = "MC1Services/Tests/MC1ServicesTests/SyncCoordinatorMessageHandlerTests.swift"
        definition = next(entry for entry in load_json(REPO / "docs" / "android" / "test-cases.json")["entries"]
                          if entry["path"] == source)
        case = definition["cases"][0]
        for whole in (False, True):
            native, api, prefix, value, scope, staged, entries = self.fixture()
            if whole:
                manifest, rules, value, scope = whole_fixture()
                native.manifest, native.policy = manifest, rules
                native.backend.manifest, native.backend.policy = manifest, rules
            catalog = copy.deepcopy(value.catalog) if whole else {
                "schema_version": 1, "source_sha": value.binding.source_sha, "entries": [],
            }
            catalog["entries"].append(copy.deepcopy(definition))
            scope["coverage"][0]["source_paths"].append(source)
            scope["coverage"][0]["cases"].append({"path": source, **case})
            scope["native"] = [{
                "revision": BASE, "transcript": "docs/android/evidence/WP-503/fixture.txt",
                "sha256": "a" * 64, "api": 31, "methods": ["NativeTest#affected"],
                "bindings": {PATH: "5" * 40}, "fixture": {"path": CONSUMER_TEST, "blob_sha": "8" * 40},
                "out_of_scope_failures": {}, "execution": {},
            }]
            evidence = copy.deepcopy(value.evidence)
            evidence["verification_scope" if whole else "repair_scope"] = scope
            evidence["source_tests"].append({
                "path": source, "blob_sha": definition["blob_sha"],
                "cases": [{**case, "result": "PASS", "evidence": [evidence["acceptance"][0]["evidence"][0]]}],
            })
            staged["fixture_original"] = source
            receipt = {
                "schema_version": 3 if whole else 2, "binding": asdict(value.binding),
                "method": "authenticated-coordinator-read-only", "staging_sha256": digest(staged),
                "evidence": evidence, "review": value.review, "approvals": [],
            }
            if not whole:
                receipt["scope_comment_id"] = 700
                api.routes[("GET", prefix + "/issues/comments/700")]["body"] = SCOPE_MARKER + json.dumps(scope)
            api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")][0]["body"] = (
                REVIEW_MARKER + json.dumps(receipt)
            )
            with self.subTest(whole=whole), \
                    patch("controller.native_review.stage_review", return_value=staged) as stage, \
                    patch("controller.native_review.tree", return_value=entries), \
                    patch("controller.native_review.object_bytes", return_value=json.dumps(catalog).encode()), \
                    patch.object(native, "verify_repair_execution"):
                if whole:
                    native.stage_whole("WP-312", 123, scope)
                else:
                    native.stage_repair("WP-312", 123, 700)
                self.assertIn(source, stage.call_args.kwargs["additional_original_paths"])
                self.assertEqual(stage.call_args.kwargs["additional_candidate_sources"],
                                 {TEST: "4" * 40, PATH: "5" * 40})
                self.assertEqual(stage.call_args.kwargs["additional_retained_sources"],
                                 {(BASE, CONSUMER_TEST): "8" * 40})
                native.evaluate("WP-312", 123, 90)
                self.assertIn(source, stage.call_args.kwargs["additional_original_paths"])
                self.assertEqual(stage.call_args.kwargs["additional_candidate_sources"],
                                 {TEST: "4" * 40, PATH: "5" * 40})
                self.assertEqual(stage.call_args.kwargs["additional_retained_sources"],
                                 {(BASE, CONSUMER_TEST): "8" * 40})
                self.assertEqual(stage.call_count, 2)
                staged["fixture_original"] = "changed"
                with self.assertRaisesRegex(PortError, "complete trusted-profile"):
                    native.evaluate("WP-312", 123, 90)

    def test_scope_can_live_on_the_repair_pr_without_a_bookkeeping_issue(self):
        native, api, prefix, value, scope, _, _ = self.fixture()
        scope["repair_issue"] = scope["repair_pr"]
        scope["acceptance"][0]["id"] = "repair-123-list"
        comment = api.routes[("GET", prefix + "/issues/comments/700")]
        comment["issue_url"] = f"https://api.github.com/repos/{native.policy['repository']}/issues/123"
        comment["body"] = SCOPE_MARKER + json.dumps(scope)
        api.routes[("GET", prefix + "/issues/123")] = {"number": 123, "pull_request": {}}
        self.assertEqual(authenticated_scope(native, 700, value.binding), scope)
        validate_scope(scope, value.binding, native.manifest, native.policy, [PATH], None)
        for kind in ("different-pr", "issue-misrepresented", "comment-on-other-issue"):
            altered = copy.deepcopy(scope)
            route = api.routes[("GET", prefix + "/issues/123")]
            if kind == "different-pr":
                altered["repair_pr"] = 124
            elif kind == "issue-misrepresented":
                route.pop("pull_request")
            else:
                comment["issue_url"] = f"https://api.github.com/repos/{native.policy['repository']}/issues/224"
            comment["body"] = SCOPE_MARKER + json.dumps(altered)
            with self.subTest(kind=kind), self.assertRaises(PortError):
                authenticated_scope(native, 700, value.binding)
            route["pull_request"] = {}
            comment["issue_url"] = f"https://api.github.com/repos/{native.policy['repository']}/issues/123"

    def test_compressed_scope_remains_bound_to_the_actual_author_and_pr(self):
        native, api, prefix, value, scope, _, _ = self.fixture()
        scope["summary"] += "x" * 70000
        comment = api.routes[("GET", prefix + "/issues/comments/700")]
        comment["body"] = encode_structured_body(scope, SCOPE_MARKER, COMPRESSED_SCOPE_MARKER)
        self.assertTrue(comment["body"].startswith(COMPRESSED_SCOPE_MARKER))
        self.assertEqual(authenticated_scope(native, 700, value.binding), scope)
        comment["user"] = {"login": "unregistered", "type": "User"}
        with self.assertRaises(PortError):
            authenticated_scope(native, 700, value.binding)

    def test_pr_author_can_propose_scope_without_impersonating_coordinator(self):
        native, api, prefix, value, scope, _, _ = self.fixture()
        scope["repair_issue"] = scope["repair_pr"]
        scope["acceptance"][0]["id"] = "repair-123-list"
        comment = api.routes[("GET", prefix + "/issues/comments/700")]
        comment.update(
            user={"id": 42, "type": "Bot", "login": "worker[bot]"},
            issue_url=f"https://api.github.com/repos/{native.policy['repository']}/issues/123",
            body=SCOPE_MARKER + json.dumps(scope),
        )
        api.routes[("GET", prefix + "/issues/123")] = {"number": 123, "pull_request": {}}
        pr = api.routes[("GET", prefix + "/pulls/123")]
        pr["user"] = {"id": 42, "type": "Bot", "login": "worker[bot]"}
        self.assertEqual(authenticated_scope(native, 700, value.binding), scope)
        for author in ({"id": 43, "type": "Bot"}, {"id": 42, "type": "User"}, {"type": "Bot"}, {}):
            comment["user"] = author
            with self.subTest(author=author), self.assertRaises(PortError):
                authenticated_scope(native, 700, value.binding)

    def test_whole_scope_requires_actual_hosted_job_and_test_method(self):
        native, api, prefix, _, _, staged, entries = self.fixture()
        manifest, rules, value, scope = whole_fixture()
        native.manifest, native.policy = manifest, rules
        native.backend.manifest, native.backend.policy = manifest, rules
        receipt = {
            "schema_version": 3, "binding": asdict(value.binding), "method": "authenticated-coordinator-read-only",
            "staging_sha256": digest(staged), "evidence": value.evidence, "review": value.review,
            "approvals": [],
        }
        source_review = api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")][0]
        source_review["body"] = REVIEW_MARKER + json.dumps(receipt)
        catalog = json.dumps(value.catalog).encode()

        def objects(repo, blob):
            if blob == "4" * 40:
                return b"@Test fun keepsListReachable() { check(true) }"
            return catalog

        with patch("controller.native_review.stage_review", return_value=staged), \
                patch("controller.native_review.tree", return_value=entries), \
                patch("controller.native_review.object_bytes", side_effect=objects), \
                patch("controller.staging.object_bytes", side_effect=objects), \
                patch("controller.repair_acceptance.read_job_log",
                      return_value=build_log(["core/maps"])):
            prepared = native.stage_whole("WP-312", 123, scope)
            self.assertFalse(prepared["authoritative"])
            accepted, result = native.evaluate("WP-312", 123, 90)
            self.assertEqual(accepted.evidence["schema_version"], 4)
            self.assertNotIn("acceptance_kind", result)
            job = api.routes[("GET", prefix + "/actions/runs/1/attempts/1/jobs?per_page=100&page=1")]["jobs"][0]
            job["conclusion"] = "skipped"
            with self.assertRaisesRegex(PortError, "missing/skipped/failed"):
                native.evaluate("WP-312", 123, 90)
            job["conclusion"] = "success"
            with patch("controller.repair_acceptance.read_job_log", return_value=build_log(
                ["feature/map", "core/maps"], unit_test_methods={
                    "feature/map": [{"class": "MapTest", "method": "unrelated"}] * 3,
                    "core/maps": [{"class": "MapTest", "method": "unrelated"}] * 3,
                },
            )), self.assertRaises(PortError):
                native.evaluate("WP-312", 123, 90)

    def test_verification_git_mode_identity_is_required_even_when_contents_resolves_a_symlink(self):
        for revision in (MERGE, BASE, HEAD):
            for kind in ("symlink", "tree", "gitlink", "missing", "truncated", "duplicate", "blob", "path",
                         "metadata", "missing-object"):
                native, _, _, value, _, _, _ = self.verification_fixture()
                valid = f"100644 blob {'5' * 40}\t{PATH}\0".encode()
                raw = {
                    "symlink": valid.replace(b"100644", b"120000"),
                    "tree": valid.replace(b"100644 blob", b"040000 tree"),
                    "gitlink": valid.replace(b"100644 blob", b"160000 commit"),
                    "missing": b"",
                    "truncated": valid[:-1],
                    "duplicate": valid + valid,
                    "blob": valid.replace(b"5" * 40, b"6" * 40),
                    "path": valid.replace(PATH.encode(), TEST.encode()),
                    "metadata": b"not-git-tree-data\0",
                    "missing-object": valid,
                }[kind]

                def read(repo, *arguments):
                    self.assertEqual(arguments, ("ls-tree", "-z", "--full-tree", arguments[3], "--", PATH))
                    if arguments[3] != revision:
                        return valid
                    if kind == "missing-object":
                        raise PortError("Git command failed: ls-tree -z")
                    return raw

                # All Contents metadata remains type=file/path/equal SHA, including the symlink case.
                with self.subTest(revision=revision, kind=kind), patch(
                    "controller.repair_acceptance.git", side_effect=read,
                ), self.assertRaisesRegex(PortError, "regular Git blob|Git command failed"):
                    authenticated_scope(native, 700, value.binding)
        native, _, _, value, _, _, _ = self.verification_fixture()
        for mode in ("100644", "100755"):
            with self.subTest(regular_mode=mode), patch(
                "controller.repair_acceptance.git", return_value=f"{mode} blob {'5' * 40}\t{PATH}\0".encode(),
            ) as reader:
                authenticated_scope(native, 700, value.binding)
                self.assertEqual([call.args[4] for call in reader.call_args_list], [MERGE, BASE, HEAD])

    def test_verification_only_scope_authenticates_unchanged_landed_consumer_without_production_edit(self):
        native, _, _, value, scope, _, _ = self.verification_fixture()
        self.assertEqual(authenticated_scope(native, 700, value.binding), scope)
        proof = verify_repair(value, scope, native.manifest, native.policy, None)
        self.assertFalse(proof["wp_complete"])
        self.assertFalse(proof["dependency_unlock"])
        self.assertEqual(proof["modules"], ["app", "feature/map"])
        for path in ("docs/android/deviations/WP-312.md", "docs/android/evidence/WP-312/consumer.md"):
            scope["paths"].append({"path": path, "operation": "modify", "capability": "wp-owned"})
        paths = [entry["path"] for entry in scope["paths"]]
        validate_scope(scope, value.binding, native.manifest, native.policy, paths, None)

    def test_legacy_production_repair_cannot_be_relabelled_as_verification_without_new_contract(self):
        native, api, prefix, value, scope, _, _ = self.verification_fixture()
        scope.update(schema_version=1, kind="landed-wp-repair")
        api.routes[("GET", prefix + "/issues/comments/700")]["body"] = SCOPE_MARKER + json.dumps(scope)
        with self.assertRaisesRegex(PortError, "Repair production paths"):
            authenticated_scope(native, 700, value.binding)
        with self.assertRaisesRegex(PortError, "repair behavioral coverage"):
            validate_scope(scope, value.binding, native.manifest, native.policy, [CONSUMER_TEST], None)

    def test_verification_scope_rejects_foreign_stale_or_noncanonical_producer_bindings(self):
        for kind in ("empty", "missing", "foreign", "malformed", "nonproduction", "merge", "base", "head",
                     "symlink", "wrong-path", "unmerged", "ancestry", "marker", "repository", "owner",
                     "source", "comment-author", "issue", "schema", "kind"):
            native, api, prefix, value, scope, _, _ = self.verification_fixture()
            coverage = scope["coverage"][0]
            if kind == "empty":
                coverage["production_bindings"] = {}
            elif kind == "missing":
                del coverage["production_bindings"]
            elif kind == "foreign":
                api.routes[("GET", prefix + "/pulls/140/files?per_page=100&page=1")] = [{"filename": TEST}]
            elif kind == "malformed":
                coverage["production_bindings"][PATH] = "not-a-blob"
            elif kind == "nonproduction":
                coverage["production_bindings"] = {TEST: "4" * 40}
            elif kind in ("merge", "base", "head", "symlink", "wrong-path"):
                revision = {"merge": MERGE, "base": BASE}.get(kind, HEAD)
                record = api.routes[("GET", prefix + f"/contents/{PATH}?ref={revision}")]
                if kind == "symlink":
                    record["type"] = "symlink"
                elif kind == "wrong-path":
                    record["path"] = TEST
                else:
                    record["sha"] = "6" * 40
            elif kind in ("unmerged", "marker", "repository"):
                producer = api.routes[("GET", prefix + "/pulls/140")]
                if kind == "unmerged":
                    producer["merged"] = False
                elif kind == "marker":
                    producer["body"] = "<!-- android-port-wp:WP-201 -->"
                else:
                    producer["base"]["repo"]["full_name"] = "foreign/repo"
            elif kind == "ancestry":
                api.routes[("GET", prefix + f"/compare/{MERGE}...{BASE}")] = {"status": "diverged"}
            elif kind == "owner":
                scope["owner"] = "other"
            elif kind == "source":
                scope["binding"]["source_sha"] = "0" * 40
            elif kind == "schema":
                scope["schema_version"] = True
            elif kind == "kind":
                scope["kind"] = "landed-wp-repair"
            comment = api.routes[("GET", prefix + "/issues/comments/700")]
            if kind == "comment-author":
                comment["user"]["login"] = "candidate"
            elif kind == "issue":
                comment["issue_url"] += "0"
            comment["body"] = SCOPE_MARKER + json.dumps(scope)
            with self.subTest(kind=kind), self.assertRaises(PortError):
                authenticated_scope(native, 700, value.binding)

    def test_verification_real_crossmodule_consumers_need_continuity_not_one_producer_authorship(self):
        native, api, prefix, value, scope, _, _ = self.verification_fixture()
        consumer = "android/app/src/main/kotlin/com/meshcoreone/android/app/SettingsConsumer.kt"
        scope["coverage"][0]["production_bindings"][consumer] = "6" * 40
        for revision in (MERGE, BASE, HEAD):
            api.routes[("GET", prefix + f"/contents/{consumer}?ref={revision}")] = {
                "type": "file", "path": consumer, "sha": "6" * 40,
            }
        comment = api.routes[("GET", prefix + "/issues/comments/700")]
        comment["body"] = SCOPE_MARKER + json.dumps(scope)
        authenticated_scope(native, 700, value.binding)
        # A second coverage entry cannot borrow another test's producer association.
        extra = copy.deepcopy(scope["coverage"][0])
        extra["production_bindings"] = {consumer: "6" * 40}
        scope["coverage"].append(extra)
        comment["body"] = SCOPE_MARKER + json.dumps(scope)
        with self.assertRaisesRegex(PortError, "actual landed producer"):
            authenticated_scope(native, 700, value.binding)

    def test_verification_path_coverage_modules_and_typed_build_wiring_fail_closed(self):
        for kind in ("production", "manifest", "resource", "foreign-doc", "controller", "omitted-path",
                     "omitted-coverage", "consumer-module", "test-module", "missing-bindings",
                     "invented-capability", "wrong-operation", "wiring-without-tests", "untyped-wiring"):
            native, _, _, value, scope, _, _ = self.verification_fixture()
            paths = [CONSUMER_TEST]
            if kind in ("production", "manifest", "resource", "foreign-doc", "controller"):
                path = {
                    "production": "android/app/src/main/kotlin/HiddenProduction.kt",
                    "manifest": "android/app/src/main/AndroidManifest.xml",
                    "resource": "android/app/src/main/res/values/strings.xml",
                    "foreign-doc": "docs/android/deviations/WP-201.md",
                    "controller": "tools/android-port/controller/native_review.py",
                }[kind]
                scope["paths"].append({"path": path, "operation": "add-direct-support-wiring",
                                       "capability": "app-build-launcher"})
                if kind in ("foreign-doc", "controller"):
                    scope["paths"][-1].update(
                        operation="record-validation-evidence", capability="traceability-validation",
                    )
                paths.append(path)
            elif kind == "omitted-path":
                paths.append(PATH)
            elif kind == "omitted-coverage":
                scope["coverage"] = []
            elif kind == "consumer-module":
                scope["coverage"][0]["modules"] = ["app"]
            elif kind == "test-module":
                scope["coverage"][0]["modules"] = ["feature/map"]
            elif kind == "missing-bindings":
                del scope["coverage"][0]["production_bindings"]
            elif kind == "invented-capability":
                scope["paths"][0]["capability"] = "wp-owned"
            elif kind == "wrong-operation":
                scope["paths"][0]["operation"] = "guessed-test-operation"
            else:
                wiring = "android/app/build.gradle.kts"
                scope["paths"].append({"path": wiring, "operation": "update-build-or-manifest",
                                       "capability": "app-build-launcher"})
                paths.append(wiring)
                if kind == "wiring-without-tests":
                    scope["paths"] = scope["paths"][1:]
                    paths = [wiring]
                else:
                    scope["paths"][-1].update(operation="modify", capability="wp-owned")
            with self.subTest(kind=kind), self.assertRaises(PortError):
                validate_scope(scope, value.binding, native.manifest, native.policy, paths, None)

        native, _, _, value, scope, _, _ = self.verification_fixture()
        wiring = "android/app/build.gradle.kts"
        scope["paths"].append({"path": wiring, "operation": "update-build-or-manifest",
                               "capability": "app-build-launcher"})
        scope["coverage"].append({**copy.deepcopy(scope["coverage"][0]), "path": wiring})
        _, _, modules = validate_scope(scope, value.binding, native.manifest, native.policy,
                                      [CONSUMER_TEST, wiring], None)
        self.assertEqual(modules, {"app", "feature/map"})
        scope["coverage"].pop()
        with self.assertRaisesRegex(PortError, "Every changed"):
            validate_scope(scope, value.binding, native.manifest, native.policy, [CONSUMER_TEST, wiring], None)

    def test_verification_methods_must_cover_all_actual_changed_declarations_and_execution(self):
        native, _, _, _, scope, _, entries = self.verification_fixture()
        executed = {"app": [{"class": "com.meshcoreone.android.app.ConsumerTest",
                             "method": "keepsListReachable[31]"}]}
        source = b"package com.meshcoreone.android.app\n@Test fun keepsListReachable() {}"
        with patch("controller.staging.object_bytes", return_value=source):
            validate_hosted_methods(scope, entries, native.manifest.repo, executed)
        for kind in ("omitted-method", "invented-method", "duplicate", "absent-test", "stale-test",
                     "stale-consumer", "not-executed", "missing-inventory", "comment-only"):
            changed, candidate, records, text = copy.deepcopy(scope), dict(entries), copy.deepcopy(executed), source
            if kind == "omitted-method":
                text += b"\n@Test fun omittedRequiredMethod() {}"
            elif kind == "invented-method":
                changed["coverage"][0]["hosted_methods"][0]["id"] = "notDeclared"
            elif kind == "duplicate":
                changed["coverage"][0]["hosted_methods"] *= 2
            elif kind == "absent-test":
                candidate.pop(CONSUMER_TEST)
            elif kind == "stale-test":
                candidate[CONSUMER_TEST] = "8" * 40
            elif kind == "stale-consumer":
                candidate[PATH] = "8" * 40
            elif kind == "not-executed":
                records["app"][0]["method"] = "other"
            elif kind == "missing-inventory":
                records = {}
            else:
                text = b"// @Test\nfun keepsListReachable() {}"
            with self.subTest(kind=kind), patch("controller.staging.object_bytes", return_value=text), \
                    self.assertRaises(PortError):
                validate_hosted_methods(changed, candidate, native.manifest.repo, records)

    def test_verification_native_methods_also_bind_all_declarations_and_production_consumers(self):
        native, _, _, _, scope, _, entries = self.verification_fixture()
        path = CONSUMER_TEST.replace("/src/test/", "/src/androidTest/")
        entry = scope["coverage"][0]
        identity = "com.meshcoreone.android.app.ConsumerTest#keepsListReachable"
        entry.update(path=path, hosted_methods=[], native_methods=[identity])
        entries[path] = "4" * 40
        source = b"package com.meshcoreone.android.app\n@Test fun keepsListReachable() {}"
        with patch("controller.staging.object_bytes", return_value=source):
            validate_hosted_methods(scope, entries, native.manifest.repo, {})
        with patch("controller.staging.object_bytes", return_value=source + b"\n@Test fun omitted() {}"), \
                self.assertRaisesRegex(PortError, "every actual"):
            validate_hosted_methods(scope, entries, native.manifest.repo, {})
        with self.assertRaisesRegex(PortError, "Missing affected native"):
            verify_native(native, scope, entries)
        helper = RepairExecutionTests()
        raw = helper.transcript([("keepsListReachable", "0")]).replace(
            "class=NativeTest", "class=com.meshcoreone.android.app.ConsumerTest",
        ).encode()
        scope["native"] = [{
            "revision": MERGE, "transcript": f"docs/android/evidence/WP-503/device-repairs-full-api{api}.txt",
            "sha256": hashlib.sha256(raw).hexdigest(), "api": api, "methods": [identity],
            "bindings": {path: "4" * 40, PATH: "5" * 40},
            "fixture": {"path": path, "blob_sha": "4" * 40},
            "out_of_scope_failures": {}, "execution": {},
        } for api in (31, 37)]
        for observation in scope["native"]:
            for binding_path, blob in observation["bindings"].items():
                # The independently retained fixture/source must also exist at the audit revision.
                native.backend.api.routes[("GET", native.prefix + f"/contents/{binding_path}?ref={MERGE}")] = {"sha": blob}
        with patch("controller.repair_acceptance.retained_file", return_value=raw), \
                patch("controller.repair_acceptance.retained_repairs_execution", return_value=BASE):
            verify_native(native, scope, entries)
            scope["native"][0]["bindings"].pop(PATH)
            with self.assertRaisesRegex(PortError, "omits affected production"):
                verify_native(native, scope, entries)

    def test_verification_only_data_staging_and_native_evaluation_keep_existing_fences(self):
        native, api, prefix, value, scope, staged, entries = self.verification_fixture()
        count = {"discovered": 1, "run": 1, "passed": 1, "failed": 0, "errors": 0, "skipped": 0}
        log = build_log(["feature/map"], suites={"app": count}, unit_test_methods={
            "app": [{"class": "com.meshcoreone.android.app.ConsumerTest", "method": "keepsListReachable[31]"}],
            "feature/map": [{"class": "MapTest", "method": name}
                            for name in ("keepsListReachable", "unrelatedOne", "unrelatedTwo")],
        })

        def objects(repo, blob):
            if blob == "4" * 40:
                return b"package com.meshcoreone.android.app\n@Test fun keepsListReachable() { check(true) }"
            return json.dumps({"schema_version": 1, "source_sha": value.binding.source_sha, "entries": []}).encode()

        with patch("controller.native_review.stage_review", return_value=staged), \
                patch("controller.native_review.tree", return_value=entries), \
                patch("controller.native_review.object_bytes", side_effect=objects), \
                patch("controller.staging.object_bytes", side_effect=objects), \
                patch("controller.repair_acceptance.read_job_log", return_value=log):
            staging = native.stage_repair("WP-312", 123, 700)
            self.assertEqual(staging["state"], "staged-data-only")
            self.assertEqual(staging["repair_scope"], scope)
            actual, result = native.evaluate("WP-312", 123, 90)
            self.assertFalse(result["wp_complete"])
            self.assertFalse(result["dependency_unlock"])
            with self.assertRaisesRegex(PortError, "cannot complete"):
                verify_completion(actual.pr, actual.evidence, actual.review, actual.approvals,
                                  actual.binding, native.manifest, native.policy, actual.catalog)
            for kind in ("current-base", "staging", "hosted-run", "skipped-job", "source-finding", "human-gate"):
                routes = copy.deepcopy(api.routes)
                if kind == "current-base":
                    api.routes[("GET", prefix + "/git/ref/heads/main")]["object"]["sha"] = MERGE
                elif kind == "staging":
                    staged["changed_paths"] = [CONSUMER_TEST, PATH]
                elif kind == "hosted-run":
                    api.routes[("GET", prefix + "/actions/runs/1")]["head_sha"] = BASE
                elif kind == "skipped-job":
                    api.routes[("GET", prefix + "/actions/runs/1/attempts/1/jobs?per_page=100&page=1")][
                        "jobs"
                    ][0]["conclusion"] = "skipped"
                elif kind == "source-finding":
                    api.routes[("POST", "/graphql")]["data"]["repository"]["pullRequest"]["reviewThreads"]["nodes"] = [
                        {"isResolved": False},
                    ]
                else:
                    api.routes[("GET", prefix + "/pulls/123")]["labels"] = [{"name": "needs-human"}]
                with self.subTest(kind=kind), self.assertRaises(PortError):
                    native.evaluate("WP-312", 123, 90)
                api.routes = routes
                staged["changed_paths"] = [CONSUMER_TEST]
        self.assertFalse(any(call[0] != "GET" and not call[3] for call in api.calls))

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
