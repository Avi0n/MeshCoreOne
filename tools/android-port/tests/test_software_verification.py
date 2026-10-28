"""AndroidOnly: WP-003 Exact software-command lineage without acceptance or drift."""

import copy
import unittest
from dataclasses import asdict, replace
from unittest.mock import patch

from fixtures import REPO, base_manifest
from controller.errors import PortError
from controller.gates import Binding, policy_revision, validate_evidence
from controller.schema import digest, load_json
from controller.software_verification import (
    amendment, apply, current_amendment, next_amendment, project, transform, transform_next,
)
from controller.scope_amendment import current_revisions, project_translation_scope
from controller.verification_config import check_configuration, content_scope_predecessor


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
