"""AndroidOnly: WP-003 Exact software-command lineage without acceptance or drift."""

import copy
import json
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path
from unittest.mock import patch

from fixtures import REPO, base_manifest
from controller.errors import PortError
from controller.gates import Binding, policy_revision, validate_evidence
from controller.ci_evidence import SUITES
from controller.local_scope import TASKS
from controller.model import git
from controller.schema import digest, load_json
from controller.software_verification import (
    amendment, apply, concern_family_binding, current_amendment, next_amendment, project,
    third_amendment, transform,
    transform_next, transform_third,
)
from controller.scope_amendment import current_revisions, project_translation_scope
from controller.verification_config import check_configuration, content_scope_predecessor


class SoftwareVerificationTests(unittest.TestCase):
    def test_exact_round_trip_preserves_complete_history_and_other_wps(self):
        current = base_manifest().data
        before = copy.deepcopy(current)
        old = project(current)
        first = amendment()
        second = next_amendment()
        third = current_amendment()
        self.assertEqual(third, third_amendment())
        self.assertEqual(digest(old), first["previous_manifest_sha256"])
        self.assertEqual(digest(transform(old)), second["previous_manifest_sha256"])
        self.assertEqual(digest(transform_next(transform(old))), third["previous_manifest_sha256"])
        self.assertEqual(digest(current), third["current_manifest_sha256"])
        self.assertEqual(apply(old), current)
        self.assertEqual(transform_third(transform_next(transform(old))), current)
        self.assertEqual(current, before)
        changed = {new["id"] for new, previous in zip(
            current["work_packages"], old["work_packages"], strict=True) if new != previous}
        self.assertEqual(changed, {"WP-202", "WP-210", "WP-502", "WP-504"})
        self.assertEqual({key: value for key, value in old.items() if key != "work_packages"},
                         {key: value for key, value in current.items() if key != "work_packages"})
        self.assertEqual(content_scope_predecessor(base_manifest()).sha256,
                         "78a22920beaa5899f9618806b5cd2b27d50399a9b29b4d8dbd79f755717ec746")

    def test_existing_tasks_are_executable_but_full_wp_acceptance_stays_closed(self):
        manifest = base_manifest()
        for wp_id, stage in (("WP-202", "scaffold"), ("WP-210", "scaffold"),
                             ("WP-502", "scaffold"), ("WP-504", "benchmark")):
            wp = manifest.wp(wp_id)
            self.assertIs(wp["verification"]["configured"], False)
            self.assertIn(["python", "tools/android-port/controller/ci.py", "run", "--stage", stage],
                          wp["verification"]["commands"])
            self.assertNotIn("Gradle/Android CI do not exist", wp["verification"]["blocker"])
            if wp_id == "WP-210":
                self.assertIn("110-family", wp["verification"]["blocker"])
                self.assertIn("configured=false", wp["verification"]["blocker"])
                self.assertIn("do not unlock dependencies", wp["verification"]["blocker"])
            binding = Binding("cbattlegear/MeshCoreOne-Android", wp_id, "a" * 40, "b" * 40,
                              manifest.data["reference"]["commit"], manifest.sha256, "c" * 64)
            with self.subTest(wp=wp_id), self.assertRaisesRegex(PortError, "(?i)full wp"):
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
                    "capability_id", "concerns", "family_identity", "concern_family_binding",
                    "work_packages"):
            record = copy.deepcopy(third_amendment())
            record[key] = [] if isinstance(record[key], list) else "changed"
            with self.subTest(key=key), patch(
                "controller.software_verification.load_json",
                return_value=record,
            ), self.assertRaisesRegex(PortError, "v3 amendment identity drift"):
                third_amendment()

    def test_concern_family_binding_rejects_replaced_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for path in (
                "docs/android/evidence/WP-003/software-verification-manifest-amendment-v3.json",
                "docs/android/evidence/WP-003/wp210-concern-family-binding.json",
            ):
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((REPO / path).read_bytes())
            binding_path = root / "docs/android/evidence/WP-003/wp210-concern-family-binding.json"
            binding = json.loads(binding_path.read_text(encoding="utf-8"))
            binding["issue"]["number"] = 388
            binding_path.write_text(json.dumps(binding), encoding="utf-8")
            with self.assertRaisesRegex(PortError, "concern-family binding identity drift"):
                third_amendment(root)

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

    def test_v3_record_cannot_change_commands_or_reopen_full_acceptance(self):
        for mutation in ("command", "configured", "blocker"):
            record = copy.deepcopy(third_amendment())
            current = record["work_packages"][0]["current"]
            if mutation == "command":
                current["commands"][1][-1] = "benchmark"
            elif mutation == "configured":
                current["configured"] = True
            else:
                current["blocker"] = ""
            with self.subTest(mutation=mutation), patch(
                "controller.software_verification.load_json", return_value=record,
            ), self.assertRaisesRegex(PortError, "v3 amendment identity drift"):
                third_amendment()

    def test_wp210_concern_binding_covers_exact_catalog_families_without_claiming_acceptance(self):
        record = third_amendment()
        binding = concern_family_binding()
        verification = record["verification_binding"]
        concerns = record["concerns"]
        manifest_verification = base_manifest().wp("WP-210")["verification"]
        self.assertEqual(verification["commands"], manifest_verification["commands"])
        self.assertEqual(
            [check["identity"] for check in verification["checks"]],
            ["controller.ci:preflight", "controller.ci:run_stage(scaffold)", "controller.ci:inspect"],
        )
        self.assertEqual(verification["checks"][1]["tasks"], TASKS["scaffold"])
        self.assertEqual(
            verification["checks"][1]["junit_suites"],
            [{"name": name, "minimum_cases": minimum} for name, (_, minimum) in SUITES.items()],
        )
        workflow = (REPO / ".github/workflows/android-ci.yml").read_text(encoding="utf-8")
        for check in verification["hosted_check_names"]:
            self.assertEqual(check["workflow"], ".github/workflows/android-ci.yml")
            self.assertIn(f"name: {check['name']}", workflow)
        self.assertEqual(
            [(item["id"], item["original_families"]) for item in concerns],
            [("WP210-CM-03", 104), ("WP210-CM-07", 1), ("WP210-CM-09", 3),
             ("WP210-CM-10", 1), ("WP210-CM-13", 1)],
        )
        self.assertEqual(sum(item["original_families"] for item in concerns), 110)
        self.assertEqual(
            set(record["family_identity"]["key_fields"]),
            {"source_path", "case_id", "parameter_family", "declaration_sha256"},
        )
        self.assertEqual(binding["issue"]["number"], 387)
        self.assertEqual(binding["source_sha"], base_manifest().data["reference"]["commit"])
        self.assertEqual(
            binding["catalog_manifest_sha256"],
            record["family_identity"]["catalog_manifest_sha256"],
        )
        self.assertEqual(
            [(item["work_package"], item["primary_families"]) for item in record["family_identity"]["catalogs"]],
            [("WP-209", 28), ("WP-210", 82)],
        )
        self.assertIn("traceability only", record["family_identity"]["limitation"])

        catalog_families = {}
        for catalog in binding["catalogs"]:
            group = load_json(REPO / catalog["path"])
            self.assertEqual(
                git(REPO, "rev-parse", f"HEAD:{catalog['path']}").decode().strip(),
                catalog["blob_sha"],
            )
            self.assertEqual(group["owner"], catalog["work_package"])
            self.assertEqual(group["source_sha"], binding["source_sha"])
            self.assertEqual(group["manifest_sha256"], binding["catalog_manifest_sha256"])
            self.assertEqual(len(group["families"]), catalog["family_count"])
            catalog_families[catalog["work_package"]] = group["families"]
        keys = set()
        owner_counts = {"WP-209": 0, "WP-210": 0}
        for concern in binding["concerns"]:
            self.assertEqual(len(concern["families"]), concern["original_families"])
            for family in concern["families"]:
                key = tuple(family[name] for name in record["family_identity"]["key_fields"])
                self.assertNotIn(key, keys)
                keys.add(key)
                owner = family["primary_owner"]
                owner_counts[owner] += 1
                matches = [candidate for candidate in catalog_families[owner]
                           if all(candidate[name] == family[name]
                                  for name in record["family_identity"]["key_fields"])]
                self.assertEqual(len(matches), 1, key)
        self.assertEqual(len(keys), 110)
        self.assertEqual(owner_counts, {"WP-209": 28, "WP-210": 82})
        special_cases = {
            concern["id"]: {family["case_id"] for family in concern["families"]}
            for concern in binding["concerns"] if concern["id"] != "CM-03"
        }
        self.assertIn("CancellationError stops the loop quietly", next(iter(special_cases["CM-07"])))
        node_config_cases = special_cases["CM-09"]
        self.assertEqual(len(node_config_cases), 3)
        self.assertTrue(any("ContactTypeBytePreservationTests::ContactFrame" in case
                            for case in node_config_cases))
        self.assertTrue(any("advert_loc_policy byte to the device verbatim" in case
                            for case in node_config_cases))
        self.assertTrue(any("sends no setOtherParams commit" in case
                            for case in node_config_cases))
        self.assertIn("local-save failure after the device add", next(iter(special_cases["CM-10"])))
        self.assertIn("does not retain the service", next(iter(special_cases["CM-13"])))
        self.assertFalse(base_manifest().wp("WP-210")["verification"]["configured"])

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
