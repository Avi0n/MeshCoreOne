import copy
import json
import tempfile
import unittest
from pathlib import Path

from fixtures import BASE, NOW, policy, settings, test_manifest
from controller.capabilities import CapabilityEngine
from controller.errors import PortError
from controller.ledger import HARD_LOCK_RESOURCES, Identity, Ledger
from portmap import capability_support_admitted


class CapabilityReservationTests(unittest.TestCase):
    def setUp(self):
        self.rules = policy()
        self.manifest = test_manifest("WP-101")
        self.engine = CapabilityEngine(self.rules)
        self.temporary = tempfile.TemporaryDirectory(prefix="meshcore-capability-tests-")
        self.addCleanup(self.temporary.cleanup)
        self.ledger = Ledger(Path(self.temporary.name) / "ledger.sqlite", self.rules["repository"])

    def test_wrong_path_and_operation_fail_closed_with_actionable_blocker(self):
        with self.assertRaisesRegex(PortError, r"WP WP-101.*secrets/key.*export"):
            self.engine.admit("WP-101", self.manifest.wp("WP-101")["write_paths"],
                              "secrets/key", "export")

    def test_assigned_path_with_unsupported_operation_fails_closed(self):
        assigned_path = self.manifest.wp("WP-101")["write_paths"][0]
        with self.assertRaisesRegex(PortError, rf"WP WP-101.*{assigned_path}.*unsupported-operation"):
            self.engine.admit("WP-101", self.manifest.wp("WP-101")["write_paths"],
                              assigned_path, "unsupported-operation")

    def test_shared_dependency_scope_is_admitted_without_byte_authorization(self):
        admission = self.engine.admit(
            "WP-101", self.manifest.wp("WP-101")["write_paths"],
            "android/core/libs.versions.toml", "add-resolved-artifact-checksum",
        )
        self.assertEqual(admission.capability_id, "dependency-resolution-metadata")

    def test_app_support_traceability_includes_native_tests_through_the_same_capability(self):
        manifest = test_manifest("WP-313")
        for source_set in ("main", "test", "androidTest"):
            with self.subTest(source_set=source_set):
                self.assertTrue(capability_support_admitted(
                    manifest, "WP-313",
                    f"android/app/src/{source_set}/kotlin/com/meshcoreone/android/Fixture.kt",
                ))

    def test_app_test_traceability_does_not_admit_resources_or_unrelated_paths(self):
        manifest = test_manifest("WP-313")
        for path in (
            "android/app/src/test/resources/Fixture.kt",
            "android/app/src/test/kotlin/Fixture.json",
            "android/feature/map/src/test/kotlin/Fixture.kt",
            "android/app/src/unapproved/kotlin/Fixture.kt",
            "secrets/Fixture.kt",
        ):
            with self.subTest(path=path):
                self.assertFalse(capability_support_admitted(manifest, "WP-313", path))

    def test_scope_evolution_is_same_owner_idempotent_and_stale_cas_is_safe(self):
        wp = self.manifest.wp("WP-101")
        binding = {
            "repository": self.rules["repository"], "work_package": "WP-101",
            "base_sha": BASE, "head_sha": "2" * 40,
            "source_sha": "d" * 40, "manifest_sha256": "e" * 64,
            "policy_revision": "f" * 64,
        }
        record, acquired = self.ledger.claim(
            wp, binding, "local", "attempt", NOW, 1800, 5, 0, 100, 5,
        )
        self.assertTrue(acquired)
        evolved = self.ledger.evolve_scope(
            "WP-101", "attempt", record["revision"],
            {"dependency-resolution-metadata": ["android/core/libs.versions.toml"]},
            ["add-resolved-artifact-checksum"], ["android/core/libs.versions.toml"],
        )
        self.assertEqual(evolved["revision"], 2)
        unchanged = self.ledger.evolve_scope(
            "WP-101", "attempt", evolved["revision"],
            {"dependency-resolution-metadata": ["android/core/libs.versions.toml"]},
            ["add-resolved-artifact-checksum"], ["android/core/libs.versions.toml"],
        )
        self.assertEqual(unchanged["revision"], evolved["revision"])
        before_stale = self.ledger.get("WP-101")
        with self.assertRaisesRegex(PortError, "Stale capability reservation"):
            self.ledger.evolve_scope(
                "WP-101", "attempt", 1, {}, ["modify"], ["android/core/libs.versions.toml"],
            )
        self.assertEqual(self.ledger.get("WP-101"), before_stale)

    def test_hard_locks_are_separate_from_file_claims(self):
        self.assertEqual(
            set(self.rules["reservation_policy"]["hard_lock_resources"]),
            HARD_LOCK_RESOURCES,
        )
        with self.ledger.hard_lock("gradle-execution", "session-a", {"kind": "runtime"}, NOW):
            self.assertEqual(self.ledger.hard_lock_records()[0]["record_state"], "hard")
            with self.assertRaisesRegex(PortError, "Hard-lock collision"):
                self.ledger.acquire_hard_lock("gradle-execution", "session-b", {}, NOW)
        self.assertEqual(self.ledger.hard_lock_records(), [])
        with self.assertRaisesRegex(PortError, "Unsupported external"):
            self.ledger.acquire_hard_lock("ordinary-file-edit", "session-a", {}, NOW)
        with self.assertRaisesRegex(PortError, "Missing or foreign"):
            self.ledger.release_hard_lock("gradle-execution", "foreign")

    def test_terminal_merge_reconciliation_is_retained_and_idempotent(self):
        paths = [".github/workflows/test.yml", ".github/workflows/lint.yml"]
        receipt, created = self.ledger.reconcile_merged_intent(
            "coordinator-session", 65, "a" * 40, "b" * 40, paths, NOW,
        )
        self.assertTrue(created)
        self.assertEqual(receipt["record_state"], "advisory-terminal-merged")
        repeated, created = self.ledger.reconcile_merged_intent(
            "coordinator-session", 65, "a" * 40, "b" * 40, list(reversed(paths)), NOW + 1,
        )
        self.assertFalse(created)
        self.assertEqual(repeated["paths"], sorted(paths))
        self.assertEqual(repeated["reconciled_at"], receipt["reconciled_at"])
        with self.assertRaisesRegex(PortError, "receipt collision"):
            self.ledger.reconcile_merged_intent(
                "coordinator-session", 65, "c" * 40, "b" * 40, paths, NOW,
            )
        self.assertEqual(len(self.ledger.intent_reconciliation_records()), 1)

    def test_overlapping_isolated_claims_are_advisory_and_record_complete_bindings(self):
        path = "android/app/build.gradle.kts"
        binding = {
            "repository": self.rules["repository"], "work_package": "WP-218",
            "base_sha": BASE, "source_sha": "d" * 40,
            "manifest_sha256": "e" * 64, "policy_revision": "f" * 64,
        }
        first, _ = self.ledger.claim(
            {"id": "WP-218", "write_paths": [path]}, binding, "local", "content",
            NOW, 1800, 5, 0, 100, 5,
            capabilities={"app-build-launcher": [path]}, operations=["update-build-or-manifest"],
            branch="content-branch", worktree="C:\\content",
            initial_identity=Identity(session_id="content"),
        )
        second, acquired = self.ledger.claim(
            {"id": "WP-302", "write_paths": [path]},
            {**binding, "work_package": "WP-302"}, "local", "shell",
            NOW, 1800, 5, 0, 100, 5,
            capabilities={"app-build-launcher": [path]}, operations=["update-build-or-manifest"],
            branch="shell-branch", worktree="C:\\shell",
            initial_identity=Identity(session_id="shell"),
        )
        self.assertTrue(acquired)
        self.assertEqual(second["overlapping_intents"], ["WP-218"])
        self.assertEqual(first["binding"]["source_sha"], "d" * 40)
        self.assertEqual(first["identity"]["session_id"], "content")
        self.assertEqual((first["branch"], first["worktree"]), ("content-branch", "C:\\content"))
        self.assertTrue(self.ledger.overlap_report()[0]["advisory"])

    def test_app3_transfer_preserves_origin_and_current_producer(self):
        result = self.engine.transfer_intent(
            {"wp": "WP-218", "session": "content", "evidence": "app3-origin.json"},
            {"wp": "WP-302", "session": "shell", "evidence": "app3-producer.json"},
            "android/app/build.gradle.kts", "app-build-launcher",
        )
        self.assertFalse(result["file_edit_blocked"])
        self.assertEqual([owner["wp"] for owner in result["attributions"]], ["WP-218", "WP-302"])

    def test_validator2_handoff_retains_both_producers_evidence(self):
        result = self.engine.transfer_intent(
            {"wp": "WP-218", "session": "content", "evidence": "validator-origin.json"},
            {"wp": "WP-003", "session": "controller", "evidence": "validator-producer.json"},
            "tools/android-port/tests/test_cli.py", "traceability-validation",
        )
        self.assertEqual(
            {owner["evidence"] for owner in result["attributions"]},
            {"validator-origin.json", "validator-producer.json"},
        )

    def test_annotation_and_37_pom_checksums_evolve_without_byte_authorization(self):
        wp = self.manifest.wp("WP-101")
        paths = [
            "android/gradle/verification-metadata.xml",
            *[f"android/gradle/dependency-locks/pom-{index}.lock" for index in range(37)],
        ]
        admissions = [
            self.engine.admit(
                "WP-101", wp["write_paths"], path, "add-resolved-artifact-checksum"
            )
            for path in paths
        ]
        self.assertEqual(len(admissions), 38)
        self.assertEqual({admission.capability_id for admission in admissions},
                         {"dependency-resolution-metadata"})

    def test_same_path_disjoint_semantic_edits_merge_deterministically(self):
        intents = [
            {"owner": "WP-302", "semantic_key": "dependency.b", "value_digest": "b" * 64,
             "evidence": "shell.json"},
            {"owner": "WP-218", "semantic_key": "dependency.a", "value_digest": "a" * 64,
             "evidence": "content.json"},
        ]
        first = self.engine.reconcile_intents(
            BASE, BASE, "android/app/build.gradle.kts",
            "dependency-resolution-metadata", intents,
        )
        second = self.engine.reconcile_intents(
            BASE, BASE, "android/app/build.gradle.kts",
            "dependency-resolution-metadata", list(reversed(intents)),
        )
        self.assertEqual(first, second)
        self.assertEqual(first["owners"], ["WP-218", "WP-302"])
        self.assertTrue(first["validation"])

    def test_current_base_and_semantic_collision_block_integration_not_local_intent(self):
        intent = {"owner": "WP-218", "semantic_key": "plugin", "value_digest": "a" * 64,
                  "evidence": "content.json"}
        with self.assertRaisesRegex(PortError, "Current base changed"):
            self.engine.reconcile_intents(
                BASE, "9" * 40, "android/app/build.gradle.kts",
                "dependency-resolution-metadata", [intent],
            )
        conflicting = [
            intent,
            {**intent, "owner": "controller", "value_digest": "b" * 64,
             "evidence": "controller.json"},
        ]
        with self.assertRaisesRegex(PortError, "Unresolved semantic ownership collision"):
            self.engine.reconcile_intents(
                BASE, BASE, "android/app/build.gradle.kts",
                "dependency-resolution-metadata", conflicting,
            )
        overlap = self.engine.overlap_report([
            {"wp": "WP-218", "paths": ["android/app/build.gradle.kts"],
             "capabilities": {"dependency-resolution-metadata": []}},
            {"wp": "WP-302", "paths": ["android/app/build.gradle.kts"],
             "capabilities": {"dependency-resolution-metadata": []}},
        ])
        self.assertTrue(overlap[0]["advisory"])

    def test_assigned_child_never_authorizes_parent_scope(self):
        with self.assertRaisesRegex(PortError, "no trusted path\\+operation rule"):
            self.engine.admit(
                "WP-101", ["private/child/file.txt"], "private", "modify"
            )

    def test_merge_integration_is_an_explicit_operation_capability(self):
        admission = self.engine.admit(
            "WP-101", self.manifest.wp("WP-101")["write_paths"],
            "android/app/build.gradle.kts", "reconcile-current-base",
        )
        self.assertEqual(admission.capability_id, "merge-integration")

    def test_legacy_migration_preserves_binding_identity_and_state(self):
        wp = copy.deepcopy(self.manifest.wp("WP-101"))
        binding = {
            "repository": self.rules["repository"], "work_package": "WP-101",
            "base_sha": BASE, "head_sha": "2" * 40,
            "source_sha": "d" * 40, "manifest_sha256": "e" * 64,
            "policy_revision": "f" * 64,
        }
        self.ledger.claim(wp, binding, "local", "attempt", NOW, 1800, 5, 0, 100, 5)
        before = self.ledger.get("WP-101")
        with self.ledger.transaction() as connection:
            connection.execute(
                "UPDATE leases SET record_state='legacy-advisory',migration_revision='' "
                "WHERE repository=? AND wp=?", (self.rules["repository"], "WP-101")
            )
        self.assertEqual(self.ledger.migrate_legacy(), 1)
        after = self.ledger.get("WP-101")
        self.assertEqual(after["binding"], before["binding"])
        self.assertEqual(after["identity"], before["identity"])
        self.assertEqual(after["state"], before["state"])
        self.assertEqual(self.ledger.migrate_legacy(), 0)

    def test_historical_supervised_receipt_migrates_alongside_original_bytes(self):
        receipt = {
            "kind": "supervised-write-reservation",
            "repository": self.rules["repository"],
            "wp": "WP-218", "owner": "content", "session_id": "historical-session",
            "branch": "historical-branch", "worktree": "C:\\worktree",
            "binding": {
                "base_sha": BASE, "source_sha": "d" * 40,
                "manifest_sha256": "e" * 64, "policy_revision": "f" * 64,
            },
            "paths": ["android/app/src/main/kotlin/content/"],
            "authorization": "original authorization bytes",
        }
        raw = json.dumps(receipt, separators=(",", ":"))
        with self.ledger.transaction() as connection:
            connection.execute(
                "CREATE TABLE supervised_reservations "
                "(repository TEXT NOT NULL, session_id TEXT NOT NULL, receipt TEXT NOT NULL, "
                "PRIMARY KEY(repository,session_id))"
            )
            connection.execute(
                "INSERT INTO supervised_reservations VALUES (?,?,?)",
                (self.rules["repository"], receipt["session_id"], raw),
            )
        self.assertEqual(self.ledger.migrate_legacy(), 1)
        migrated = self.ledger.get("WP-218")
        self.assertEqual(migrated["identity"]["session_id"], receipt["session_id"])
        self.assertEqual(migrated["binding"], receipt["binding"])
        self.assertEqual(migrated["authorization_context"]["historical_receipt"], raw)
        self.assertEqual(migrated["authorization_context"]["authorization"],
                         receipt["authorization"])
        self.assertEqual(self.ledger.migrate_legacy(), 0)

    def test_malformed_historical_receipt_rolls_back_without_partial_migration(self):
        with self.ledger.transaction() as connection:
            connection.execute(
                "CREATE TABLE supervised_reservations "
                "(repository TEXT NOT NULL, session_id TEXT NOT NULL, receipt TEXT NOT NULL, "
                "PRIMARY KEY(repository,session_id))"
            )
            connection.execute(
                "INSERT INTO supervised_reservations VALUES (?,?,?)",
                (self.rules["repository"], "malformed", json.dumps({"kind": "wrong"})),
            )
        with self.assertRaisesRegex(PortError, "Malformed historical"):
            self.ledger.migrate_legacy()
        self.assertIsNone(self.ledger.get("WP-218"))
