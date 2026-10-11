"""AndroidOnly: WP-000 Full-catalog scope removal, not message-translation acceptance."""

import copy
import unittest
from unittest.mock import patch

from fixtures import REPO, base_manifest
from bootstrap import build_inventory
from controller.errors import PortError
from controller.gates import validate_cases
from controller.model import HUMAN_GATES, REFERENCE_SHA, validate_manifest
from controller.render import render
from controller.runtime_inputs import required_inputs, verify_tree_entries
from controller.schema import digest, load_json
from controller.scope_amendment import (
    AMENDMENT_PATH, amendment, apply_translation_scope, project_translation_scope,
    project_translation_exclusions,
)
from controller.verification_config import (
    apply_content_scope, apply_overlay, content_scope_manifest_revision,
    content_scope_predecessor, content_scope_revisions, project_content_scope,
)
from inventory_rules import TRANSLATION_PATHS, exclusion, production_owner


class TranslationScopeAmendmentTests(unittest.TestCase):
    def test_all_three_complete_catalog_stages_round_trip_without_mutation(self):
        generated, _ = build_inventory(REPO)
        for current in (generated, apply_overlay(generated), base_manifest().data):
            before = copy.deepcopy(current)
            previous = project_translation_scope(current)
            self.assertEqual(apply_translation_scope(previous), current)
            self.assertEqual(current, before)
            self.assertEqual(len(previous["work_packages"]), 65)
            self.assertEqual(len(current["work_packages"]), 64)
        self.assertEqual(apply_content_scope(apply_overlay(generated)), base_manifest().data)
        self.assertEqual(content_scope_predecessor(base_manifest()).sha256,
                         "78a22920beaa5899f9618806b5cd2b27d50399a9b29b4d8dbd79f755717ec746")

    def test_only_retired_wp_and_its_seven_edges_and_gate_are_removed(self):
        current = base_manifest()
        previous = project_translation_scope(current.data)
        old = {wp["id"]: wp for wp in previous["work_packages"]}
        new = current.work_packages
        self.assertEqual(old.keys() - new.keys(), {"WP-406"})
        self.assertEqual(old["WP-406"]["depends_on"], ["WP-307", "WP-308", "WP-213", "WP-218", "WP-317"])
        for identity, wp in new.items():
            expected = [dep for dep in old[identity]["depends_on"] if dep != "WP-406"]
            self.assertEqual(wp["depends_on"], expected)
            self.assertEqual(wp["human_gate"], old[identity]["human_gate"])
            for field in ("owner", "supervised", "planned_state", "write_paths", "verification"):
                self.assertEqual(wp[field], old[identity][field])
        self.assertEqual({wp["id"] for wp in new.values() if wp["human_gate"]}, HUMAN_GATES)
        self.assertEqual(len(HUMAN_GATES), 7)
        self.assertFalse(any("WP-406" in e["cross_references"] for e in current.data["inventory"]))

    def test_every_original_inventory_blob_kind_and_nontranslation_owner_survives(self):
        current = base_manifest().data
        previous = project_translation_scope(current)
        self.assertEqual(current["reference"]["commit"], REFERENCE_SHA)
        for field in ("repository", "commit", "tree_sha", "source_inventory_sha256"):
            self.assertEqual(current["reference"][field], previous["reference"][field])
        self.assertEqual(current["implementations"], previous["implementations"])
        for old, new in zip(previous["inventory"], current["inventory"], strict=True):
            for field in ("path", "blob_sha", "kind"):
                self.assertEqual(new[field], old[field])
            if old["path"] in TRANSLATION_PATHS:
                self.assertEqual(old["primary_owner"], "WP-406")
                self.assertIsNone(new["primary_owner"])
                self.assertEqual(new["exclusion"], "removed-translation")
                self.assertEqual(new["cross_references"], ["WP-000"])
            else:
                self.assertEqual(new["primary_owner"], old["primary_owner"])
                self.assertEqual(new["exclusion"], old["exclusion"])
                self.assertEqual(new["cross_references"],
                                 [wp for wp in old["cross_references"] if wp != "WP-406"])
        self.assertEqual(len(current["inventory"]), 1866)

    def test_exclusions_are_exact_user_approved_records_not_filename_globs(self):
        record = amendment()
        exclusions = base_manifest().exclusions
        self.assertEqual(digest(exclusions), record["current_exclusions_sha256"])
        previous = copy.deepcopy(exclusions)
        previous["entries"] = [e for e in previous["entries"] if e["reason_code"] != "removed-translation"]
        self.assertEqual(digest(previous), record["previous_exclusions_sha256"])
        self.assertEqual(project_translation_exclusions(exclusions), previous)
        self.assertEqual(content_scope_predecessor(base_manifest()).exclusions, previous)
        self.assertEqual({e["path"] for e in record["added_exclusions"]}, TRANSLATION_PATHS)
        self.assertEqual(len(TRANSLATION_PATHS), 26)
        for path in TRANSLATION_PATHS:
            self.assertEqual(exclusion(path)[0:2], ("removed-translation", "WP-000"))
            with self.assertRaises(PortError):
                production_owner(path)
        for path in ("MC1/NewTranslationFeature.swift", "MC1/Views/Settings/SettingsView.swift",
                     "MC1/Resources/Localization/en.lproj/Settings.strings"):
            self.assertIsNone(exclusion(path))

    def test_historical_translation_cases_remain_inventoried_not_passed_or_dropped(self):
        manifest = base_manifest()
        catalog = load_json(REPO / "docs/android/test-cases.json")
        details = load_json(REPO / "docs/android/evidence/WP-004/inventory-details.json")
        indexed = {entry["path"]: entry for entry in catalog["entries"]}
        detailed = {entry["path"]: entry for entry in details["files"]}
        excluded_tests = [entry for entry in manifest.data["inventory"]
                          if entry["path"] in TRANSLATION_PATHS and entry["kind"] in ("test", "support")]
        self.assertEqual(len(excluded_tests), 9)
        families = 0
        for entry in excluded_tests:
            original = indexed[entry["path"]]
            self.assertEqual(original["blob_sha"], entry["blob_sha"])
            self.assertEqual(detailed[entry["path"]]["work_package"], "WP-406")
            self.assertIsNone(detailed[entry["path"]]["exclusion"])
            families += len(original["cases"])
            self.assertTrue(all(c["port_status"] == "pending" for c in detailed[entry["path"]]["cases"]))
        self.assertGreater(families, 0)
        validate_cases({"source_tests": []}, manifest, manifest.wp("WP-000"), catalog, set())
        with self.assertRaises(PortError):
            validate_cases({"source_tests": [excluded_tests[0]]}, manifest, manifest.wp("WP-000"), catalog, set())

    def test_changed_catalog_or_partial_delta_cannot_bypass_lineage(self):
        current = base_manifest().data
        mutations = {
            "blob": lambda m: m["inventory"][0].update(blob_sha="0" * 40),
            "gate": lambda m: m["work_packages"][0].update(human_gate=False),
            "path": lambda m: m["work_packages"][0]["write_paths"].append("android/app/"),
            "acceptance": lambda m: m["work_packages"][10]["acceptance"][0].update(description="foreign"),
            "pin": lambda m: m["reference"].update(commit="0" * 40),
            "extra": lambda m: m.update(approved=True),
            "resurrect": lambda m: m["work_packages"].append(
                next(w for w in project_translation_scope(current)["work_packages"] if w["id"] == "WP-406")),
        }
        for label, mutate in mutations.items():
            candidate = copy.deepcopy(current)
            mutate(candidate)
            before = copy.deepcopy(candidate)
            with self.subTest(label=label), self.assertRaises(PortError):
                project_translation_scope(candidate)
            self.assertEqual(candidate, before)
        for malformed in (None, [], {}, {"reference": {}}):
            with self.subTest(value=malformed), self.assertRaises(PortError):
                project_translation_scope(malformed)

    def test_amendment_record_cannot_self_authorize_changed_history_or_scope(self):
        for field in ("authorization", "base_sha", "current_policy_revision", "inventory", "added_exclusions"):
            changed = copy.deepcopy(amendment())
            changed[field] = [] if isinstance(changed[field], list) else "changed"
            with self.subTest(field=field), patch("controller.scope_amendment.load_json", return_value=changed), \
                    self.assertRaisesRegex(PortError, "identity drift"):
                project_translation_scope(base_manifest().data)

    def test_current_revisions_never_refresh_historical_success_as_new_acceptance(self):
        manifest = base_manifest()
        policy = load_json(REPO / "docs/android/automation-policy.json")
        revisions = content_scope_revisions(manifest, policy)
        self.assertEqual(revisions, {"manifest_sha256": manifest.sha256,
                                    "policy_revision": amendment()["current_policy_revision"]})
        self.assertEqual(content_scope_manifest_revision(manifest), manifest.sha256)
        self.assertNotEqual(revisions["manifest_sha256"], amendment()["revisions"]["final"]["previous"])
        self.assertEqual(digest(project_content_scope(manifest.data)),
                         "1dd7bc4f74f10e566b66fc8bc3822bb2bd66068053e1aa866e520326aab4dde6")
        changed = copy.deepcopy(policy)
        changed["trusted_check_app_ids"] = [999999]
        with self.assertRaises(PortError):
            content_scope_revisions(manifest, changed)

    def test_fake_or_broadened_translation_exclusions_fail_closed(self):
        manifest = base_manifest()
        for mode in ("reason", "audit", "scope", "missing"):
            exclusions = copy.deepcopy(manifest.exclusions)
            entry = next(e for e in exclusions["entries"] if e["reason_code"] == "removed-translation")
            if mode == "reason":
                entry["reason_code"] = "apple-only-glue"
            elif mode == "audit":
                entry["review_basis"] = "fake"
            elif mode == "scope":
                mixed = next(e for e in manifest.data["inventory"] if e["path"].endswith("/ChatViewModel.swift"))
                entry.update(path=mixed["path"], blob_sha=mixed["blob_sha"])
            else:
                exclusions["entries"].remove(entry)
            with self.subTest(mode=mode), self.assertRaises(PortError):
                validate_manifest(manifest.data, exclusions, REPO, verify_checkout=False)

    def test_workers_cannot_treat_removed_translation_as_required_adaptation(self):
        manifest = base_manifest()
        policy = load_json(REPO / "docs/android/automation-policy.json")
        text = render(manifest, policy, "WP-000")
        self.assertIn("removed-translation (user-approved scope removal; no port or future build requirement)", text)
        self.assertNotIn("removed-translation (adaptation still required)", text)
        self.assertIn("not deferred or a future build/release requirement", render(manifest, policy, "WP-407"))
        with self.assertRaises(PortError):
            render(manifest, policy, "WP-406")

    def test_inert_scaffold_has_no_provider_or_translation_registration(self):
        module = REPO / "android/platform/translation"
        self.assertFalse(list(module.rglob("*.kt")))
        build = (module / "build.gradle.kts").read_text(encoding="utf-8")
        dependencies = [line.strip() for line in build.splitlines() if "implementation(" in line]
        self.assertEqual(dependencies, ['implementation(project(":core:model"))',
                                        'implementation(project(":core:contracts"))'])
        registry = (REPO / "android/app/src/main/kotlin/com/meshcoreone/android/FeatureRegistry.kt").read_text(encoding="utf-8")
        self.assertNotIn("translat", registry.lower())
        self.assertEqual(registry.count("FeatureRegistration(FeatureId."), 7)

    def test_amendment_and_projector_are_required_immutable_runtime_inputs(self):
        required = required_inputs()
        for path in (AMENDMENT_PATH, "tools/android-port/controller/scope_amendment.py"):
            self.assertIn(path, required)
            entries = {name: "a" * 40 for name in required - {path}}
            with self.assertRaisesRegex(PortError, "committed Git tree"):
                verify_tree_entries(entries)

    def test_upstream_changes_do_not_resurrect_translation_producers(self):
        from resync import compare

        manifest = base_manifest()
        entry = next(e for e in manifest.data["inventory"] if e["path"] in TRANSLATION_PATHS)
        change = {
            "status": "M", "old_path": entry["path"], "new_path": entry["path"],
            "old_blob": entry["blob_sha"], "new_blob": "b" * 40, "rename_similarity": None,
            "old_mode": "100644", "new_mode": "100644",
        }
        result = compare(manifest, load_json(REPO / "docs/android/automation-policy.json"),
                         "a" * 40, [change], [])
        self.assertEqual(result["proposals"], [])
        self.assertEqual(result["blockers"], [])
        self.assertEqual(result["status"], "review_required")
        self.assertEqual(result["changed_exclusions"][0]["original_exclusion"], "removed-translation")
        self.assertIn("new user request", result["changed_exclusions"][0]["disposition"])
