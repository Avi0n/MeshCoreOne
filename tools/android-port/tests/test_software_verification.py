"""AndroidOnly: WP-003 Exact software-command lineage without acceptance or drift."""

import copy
import unittest
from dataclasses import asdict, replace
from unittest.mock import patch

from fixtures import REPO, base_manifest
from controller.capabilities import CapabilityEngine
from controller.errors import PortError
from controller.gates import Binding, policy_revision, validate_evidence
from controller.model import git
from controller.schema import digest, load_json
from controller.software_verification import (
    amendment, apply, current_amendment, next_amendment, project, transform, transform_next,
)
from controller.scope_amendment import current_revisions, project_translation_scope
from controller.verification_config import check_configuration, content_scope_predecessor
from controller.verification import declared_software_lane, require_shared_whole_lane


class SoftwareVerificationTests(unittest.TestCase):
    def test_exact_round_trip_preserves_complete_history_and_other_wps(self):
        current = base_manifest().data
        before = copy.deepcopy(current)
        old = project(current)
        first = amendment()
        second = current_amendment()
        self.assertEqual(second, next_amendment())
        self.assertEqual(digest(old), first["previous_manifest_sha256"])
        self.assertEqual(digest(transform(old)), second["previous_manifest_sha256"])
        self.assertEqual(digest(current), second["current_manifest_sha256"])
        self.assertEqual(apply(old), current)
        self.assertEqual(transform_next(transform(old)), current)
        self.assertEqual(current, before)
        changed = {new["id"] for new, previous in zip(
            current["work_packages"], old["work_packages"], strict=True) if new != previous}
        self.assertEqual(changed, {"WP-202", "WP-502", "WP-504"})
        self.assertEqual({key: value for key, value in old.items() if key != "work_packages"},
                         {key: value for key, value in current.items() if key != "work_packages"})
        self.assertEqual(content_scope_predecessor(base_manifest()).sha256,
                         "78a22920beaa5899f9618806b5cd2b27d50399a9b29b4d8dbd79f755717ec746")

    def test_existing_tasks_are_executable_but_full_wp_acceptance_stays_closed(self):
        manifest = base_manifest()
        for wp_id, stage in (("WP-202", "scaffold"), ("WP-502", "scaffold"), ("WP-504", "benchmark")):
            wp = manifest.wp(wp_id)
            self.assertIs(wp["verification"]["configured"], False)
            self.assertIn(["python", "tools/android-port/controller/ci.py", "run", "--stage", stage],
                          wp["verification"]["commands"])
            self.assertNotIn("Gradle/Android CI do not exist", wp["verification"]["blocker"])
            binding = Binding("cbattlegear/MeshCoreOne-Android", wp_id, "a" * 40, "b" * 40,
                              manifest.data["reference"]["commit"], manifest.sha256, "c" * 64)
            with self.subTest(wp=wp_id), self.assertRaisesRegex(PortError, "Full WP"):
                validate_evidence({
                    **asdict(binding), "schema_version": 1, "runs": [], "acceptance": [],
                    "source_tests": [], "changed_paths": [],
                }, binding, manifest, {})

    def test_every_catalog_drift_and_partial_amendment_is_rejected(self):
        for mode in ("command", "configured", "blocker", "missing-wp", "duplicate-wp",
                     "partial", "owner", "dependency", "source", "inventory", "acceptance"):
            data = copy.deepcopy(base_manifest().data)
            wp = next(w for w in data["work_packages"] if w["id"] == "WP-504")
            if mode == "command":
                wp["verification"]["commands"][1][-1] = "assemble"
            elif mode == "configured":
                wp["verification"]["configured"] = True
            elif mode == "blocker":
                wp["verification"]["blocker"] = ""
            elif mode == "missing-wp":
                data["work_packages"].remove(wp)
            elif mode == "duplicate-wp":
                data["work_packages"].append(copy.deepcopy(wp))
            elif mode == "partial":
                wp["verification"] = amendment()["work_packages"][1]["previous"]
            elif mode == "owner":
                wp["owner"] = "test-parity-engineer"
            elif mode == "dependency":
                wp["depends_on"] = []
            elif mode == "source":
                data["reference"]["commit"] = "0" * 40
            elif mode == "inventory":
                data["inventory"][0]["blob_sha"] = "0" * 40
            else:
                wp["acceptance"] = []
            with self.subTest(mode=mode), self.assertRaises(PortError):
                project_translation_scope(data)
            with self.subTest(mode=mode), self.assertRaises(PortError):
                transform_next(data, reverse=True)

    def test_record_cannot_authorize_itself_or_modify_receipt_history(self):
        for key in ("authorization", "previous_manifest_sha256", "current_manifest_sha256",
                    "capability_id", "mapping_sources", "work_packages"):
            record = copy.deepcopy(amendment())
            record[key] = [] if isinstance(record[key], list) else "changed"
            with self.subTest(key=key), patch(
                "controller.software_verification.load_json",
                side_effect=[next_amendment(), record],
            ), \
                    self.assertRaisesRegex(PortError, "identity drift"):
                project(base_manifest().data)

    def test_v2_record_cannot_change_commands_or_reopen_full_acceptance(self):
        for mutation in ("command", "configured", "blocker"):
            record = copy.deepcopy(next_amendment())
            current = record["work_packages"][0]["current"]
            if mutation == "command":
                current["commands"][1][-1] = "benchmark"
            elif mutation == "configured":
                current["configured"] = True
            else:
                current["blocker"] = ""
            with self.subTest(mutation=mutation), patch(
                "controller.software_verification.load_json", return_value=record,
            ), self.assertRaisesRegex(PortError, "v2 amendment identity drift"):
                next_amendment()

    def test_current_policy_binding_is_new_and_old_policy_is_not_relabelled(self):
        manifest = base_manifest()
        policy = load_json(REPO / "docs/android/automation-policy.json")
        historical = replace(manifest, data=project(manifest.data))
        self.assertNotEqual(manifest.sha256, historical.sha256)
        self.assertNotEqual(policy_revision(manifest, policy), policy_revision(historical, policy))
        self.assertEqual(current_revisions(manifest, policy), {
            "manifest_sha256": manifest.sha256, "policy_revision": policy_revision(manifest, policy),
        })
        changed = copy.deepcopy(policy)
        changed["branch_rules_proven"] = True
        with self.assertRaises(PortError):
            current_revisions(manifest, changed)

    def test_read_only_configuration_reconstructs_the_amended_catalog(self):
        result = check_configuration(REPO)
        self.assertEqual(result["software_command_amendment"], current_amendment()["amendment_id"])
        self.assertFalse(result["feature_verification_configured"])
        self.assertFalse(result["human_or_dependency_acceptance"])


class WP210ConcernMappingTests(unittest.TestCase):
    def binding(self):
        return load_json(REPO / "docs/android/evidence/WP-003/wp210-concern-family-binding.json")

    def test_exact_issue_family_map_retains_pinned_identity_and_primary_ownership(self):
        binding = self.binding()
        original = {key: value for key, value in binding.items()
                    if key not in ("handoff", "acceptance_criteria")}
        self.assertEqual(digest(original),
                         "12dff6ac3a7fceb00d1a34d256581fdb890814183c90d0d9f99a87268c7dd023")
        manifest = base_manifest()
        self.assertEqual(binding["source_sha"], manifest.data["reference"]["commit"])
        self.assertEqual(binding["issue"]["number"], 387)
        self.assertEqual(
            [(entry["id"], entry["original_families"]) for entry in binding["concerns"]],
            [("CM-03", 104), ("CM-07", 1), ("CM-09", 3), ("CM-10", 1), ("CM-13", 1)],
        )
        inventory = {entry["path"]: entry for entry in manifest.data["inventory"]}
        original_cases = load_json(REPO / "docs/android/test-cases.json")
        self.assertEqual(original_cases["source_sha"], binding["source_sha"])
        cases = {entry["path"]: entry for entry in original_cases["entries"]}
        groups = {}
        for catalog in binding["catalogs"]:
            group = load_json(REPO / catalog["path"])
            self.assertEqual(
                git(REPO, "rev-parse", f"HEAD:{catalog['path']}").decode().strip(),
                catalog["blob_sha"],
            )
            self.assertEqual(group["source_sha"], binding["source_sha"])
            self.assertEqual(group["manifest_sha256"], binding["catalog_manifest_sha256"])
            self.assertEqual(group["owner"], catalog["work_package"])
            self.assertEqual(len(group["families"]), catalog["family_count"])
            groups[catalog["work_package"]] = group["families"]
        keys, owner_counts = set(), {"WP-209": 0, "WP-210": 0}
        key_fields = ("source_path", "case_id", "parameter_family", "declaration_sha256")
        for concern in binding["concerns"]:
            self.assertEqual(len(concern["families"]), concern["original_families"])
            for family in concern["families"]:
                key = tuple(family[name] for name in key_fields)
                self.assertNotIn(key, keys)
                keys.add(key)
                owner = family["primary_owner"]
                owner_counts[owner] += 1
                self.assertEqual(
                    sum(all(candidate[name] == family[name] for name in key_fields)
                        for candidate in groups[owner]), 1, key,
                )
                source = family["source_path"]
                self.assertEqual(inventory[source]["primary_owner"], owner)
                self.assertIsNone(inventory[source]["exclusion"])
                self.assertEqual(cases[source]["blob_sha"], inventory[source]["blob_sha"])
                self.assertIn(
                    {"id": family["case_id"], "parameter_family": family["parameter_family"]},
                    cases[source]["cases"],
                )
        self.assertEqual(len(keys), 110)
        self.assertEqual(owner_counts, {"WP-209": 28, "WP-210": 82})

    def test_criteria_remain_pending_and_map_to_every_concern(self):
        binding = self.binding()
        criteria = binding["acceptance_criteria"]
        self.assertEqual([entry["concern"] for entry in criteria],
                         [entry["id"] for entry in binding["concerns"]])
        for criterion in criteria:
            with self.subTest(concern=criterion["concern"]):
                self.assertTrue(criterion["required_evidence"])
                if criterion["concern"] == "CM-09":
                    self.assertEqual(criterion["status"], "pending-evidence")
                    self.assertIsNone(criterion["pending_decision"])
                else:
                    self.assertEqual(criterion["status"], "pending-evidence-and-maintainer-decision")
                    self.assertTrue(criterion["pending_decision"])
        handoff = binding["handoff"]
        self.assertEqual(handoff["method_pr"], 456)
        self.assertEqual(handoff["status"],
                         "planning-only-not-an-authenticated-scope-or-acceptance-receipt")
        self.assertIn("not the complete WP-210 inventory", handoff["completion_boundary"])

    def test_current_shared_lane_and_consumer_capability_need_no_wp210_amendment(self):
        manifest = base_manifest()
        wp = manifest.wp("WP-210")
        self.assertIs(wp["verification"]["configured"], False)
        self.assertEqual(wp["verification"]["commands"], [])
        self.assertEqual(require_shared_whole_lane(manifest, wp), declared_software_lane(manifest))
        policy = load_json(REPO / "docs/android/automation-policy.json")
        engine = CapabilityEngine(policy)
        path = ("android/feature/remotenodes/src/test/kotlin/com/meshcoreone/android/"
                "feature/remotenodes/cli/RemoteNodeCLIConsumerParityTest.kt")
        admission = engine.admit(wp["id"], wp["write_paths"], path, "add-or-update-related-test")
        self.assertEqual(admission.capability_id, "reviewed-support-test")
        for operation in ("create", "test", "modify"):
            with self.subTest(operation=operation), self.assertRaises(PortError):
                engine.admit(wp["id"], wp["write_paths"], path, operation)
        self.assertNotIn("wp210-feature-cli-consumer-regression",
                         [rule["id"] for rule in policy["reservation_policy"]["capabilities"]])
