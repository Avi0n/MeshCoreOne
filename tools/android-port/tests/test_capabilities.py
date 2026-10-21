import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from fixtures import BASE, NOW, REPO, policy, settings, test_manifest
from controller.capabilities import CapabilityEngine
from controller.errors import PortError
from controller.ledger import HARD_LOCK_RESOURCES, Identity, Ledger
from controller.scope_amendment import (
    ROOMS_AVATAR_CAPABILITY, ROOMS_MESSAGE_ACTIONS_CAPABILITY,
    MESSAGE_ACTIONS_TRANSLATION_REMOVAL_CAPABILITY,
)
from portmap import capability_support_admitted


ROOM_RENDERER = (
    "android/feature/chats/src/main/kotlin/com/meshcoreone/android/"
    "feature/chats/rooms/RoomConversationScreen.kt"
)
ROOM_REPAIR = "repair-room-incoming-avatar-bookends"
ROOM_CAPABILITY = "rooms-incoming-avatar-repair"
ROOM_ACTIONS_OPERATION = "implement-room-message-actions"
ROOM_ACTIONS_PATHS = [
    ROOM_RENDERER.rsplit("/", 1)[0] + "/" + name
    for name in (
        "RoomConversationScreen.kt", "RoomConversationStateHolder.kt", "RoomContracts.kt",
        "RoomMessageAction.kt", "RoomMessageActionAvailability.kt", "RoomMessageActionsSheet.kt",
        "RoomSendDMSheet.kt",
    )
]
PICKER_PATH = (
    "android/feature/chats/src/main/kotlin/com/meshcoreone/android/"
    "feature/chats/composer/ui/MentionSuggestions.kt"
)
PICKER_PATHS = [
    PICKER_PATH, PICKER_PATH.replace("MentionSuggestions.kt", "ChatConversationScreen.kt"), ROOM_RENDERER,
]
PICKER_OPERATION = "repair-shared-mention-picker"
PICKER_CAPABILITY = "shared-mention-picker-repair"
TRANSLATION_ACTION_PATH = (
    "android/feature/chats/src/main/kotlin/com/meshcoreone/android/"
    "feature/chats/actions/MessageActionsUi.kt"
)
TRANSLATION_REMOVAL_OPERATION = "remove-active-message-translation-affordance"
TRANSLATION_REMOVAL_CAPABILITY = "message-actions-translation-removal"


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

    def test_rooms_repair_admits_only_exact_wp_path_and_specific_operation(self):
        manifest = test_manifest("WP-502")
        assigned = manifest.wp("WP-502")["write_paths"]
        admission = self.engine.admit("WP-502", assigned, ROOM_RENDERER, ROOM_REPAIR)
        self.assertEqual(admission.capability_id, ROOM_CAPABILITY)
        self.assertEqual((admission.path, admission.operation), (ROOM_RENDERER, ROOM_REPAIR))
        self.assertNotIn(ROOM_RENDERER, assigned)
        self.assertTrue(any(ROOM_RENDERER.startswith(path) for path in manifest.wp("WP-310")["write_paths"]))
        for wp, path, operation in (
            ("WP-003", ROOM_RENDERER, ROOM_REPAIR),
            ("WP-501", ROOM_RENDERER, ROOM_REPAIR),
            ("WP-503", ROOM_RENDERER, ROOM_REPAIR),
            ("WP-310", ROOM_RENDERER, ROOM_REPAIR),
            ("WP-502", ROOM_RENDERER.replace("Screen.kt", "StateHolder.kt"), ROOM_REPAIR),
            ("WP-502", ROOM_RENDERER.replace("chats/rooms/", "chats/channels/"), ROOM_REPAIR),
            ("WP-502", ROOM_RENDERER.rsplit("/", 1)[0], ROOM_REPAIR),
            ("WP-502", ROOM_RENDERER + ".backup", ROOM_REPAIR),
            *[("WP-502", ROOM_RENDERER, operation) for operation in
              ("modify", "create", "delete", "add-direct-support-wiring", "unknown-operation")],
        ):
            with self.subTest(wp=wp, path=path, operation=operation):
                with self.assertRaisesRegex(PortError, r"no trusted path\+operation rule"):
                    self.engine.admit(wp, manifest.wp(wp)["write_paths"], path, operation)

    def test_rooms_repair_retains_product_invariants_and_does_not_deploy_candidate(self):
        rule = next(rule for rule in self.rules["reservation_policy"]["capabilities"]
                    if rule["id"] == ROOM_CAPABILITY)
        self.assertEqual(rule["work_packages"], ["WP-502"])
        self.assertEqual(rule["path_rules"], [ROOM_RENDERER])
        self.assertEqual(rule["operations"], [ROOM_REPAIR])
        self.assertEqual(set(rule["invariants"]), {
            "issue-253-renderer-only",
            "pinned-source-db14559b39d32322b06477c6ae676112f583db50",
            "WP-310-primary-provenance-unchanged", "existing-WP-502-owner-same-reservation",
            "incoming-gutter-and-showAvatar-bookends-no-self-avatar",
            "reuse-shared-native-identity-and-theme", "once-only-spoken-message-label",
            "no-auth-service-state-ordering-or-activation-change",
            "semantic-collisions-serialize-or-transfer-current-producer",
            "trusted-main-admission-before-product-edit",
            "no-main-policy-landing-during-held-native-or-gradle-operation",
        })
        self.assertEqual(len(rule["validation"]), 5)
        trusted = copy.deepcopy(self.rules)
        trusted["reservation_policy"]["capabilities"].remove(rule)
        trusted_engine = CapabilityEngine(trusted)
        self.engine.admit("WP-502", [], ROOM_RENDERER, ROOM_REPAIR)
        with self.assertRaisesRegex(PortError, r"no trusted path\+operation rule"):
            trusted_engine.admit("WP-502", [], ROOM_RENDERER, ROOM_REPAIR)
        for operation in ("transfer-current-producer", "reconcile-current-base"):
            admission = trusted_engine.admit("WP-502", [], ROOM_RENDERER, operation)
            self.assertEqual(admission.capability_id, "merge-integration")
            self.assertNotEqual(admission.operation, ROOM_REPAIR)
        self.assertNotEqual(trusted_engine.revision, self.engine.revision)
        for key in ("paused", "dispatch_mode", "activation_approved", "merge_gate_policy",
                    "review_policy", "protected_paths", "admin_bypass"):
            self.assertEqual(trusted[key], self.rules[key])

    def test_rooms_repair_scope_evolution_preserves_identity_and_rejects_stale_or_foreign_owner(self):
        manifest = test_manifest("WP-502")
        wp = manifest.wp("WP-502")
        binding = {
            "repository": self.rules["repository"], "work_package": "WP-502",
            "base_sha": BASE, "head_sha": "2" * 40, "source_sha": "d" * 40,
            "manifest_sha256": manifest.sha256, "policy_revision": "f" * 64,
        }
        context = {"session_id": "existing-502", "branch": "existing-branch", "worktree": "C:\\existing"}
        record, acquired = self.ledger.claim(
            wp, binding, "local", "existing-attempt", NOW, 1800, 5, 0, 100, 5,
            initial_identity=Identity(session_id=context["session_id"]),
            branch=context["branch"], worktree=context["worktree"],
            authorization_context={"authorization": "immutable original authorization"},
        )
        self.assertTrue(acquired)
        record = self.ledger.get("WP-502")
        admission = self.engine.admit("WP-502", wp["write_paths"], ROOM_RENDERER, ROOM_REPAIR)
        capabilities = {admission.capability_id: [admission.path]}
        for attempt, revision, owner in (
            ("foreign-attempt", record["revision"], context),
            ("existing-attempt", record["revision"], {**context, "session_id": "replacement"}),
            ("existing-attempt", record["revision"], {**context, "branch": "foreign"}),
            ("existing-attempt", record["revision"], {**context, "worktree": "C:\\foreign"}),
        ):
            with self.subTest(attempt=attempt, owner=owner), self.assertRaises(PortError):
                self.ledger.evolve_scope(
                    "WP-502", attempt, revision, capabilities, [ROOM_REPAIR], [ROOM_RENDERER],
                    owner_context=owner,
                )
            self.assertEqual(self.ledger.get("WP-502"), record)
        evolved = self.ledger.evolve_scope(
            "WP-502", record["attempt"], record["revision"], capabilities,
            [ROOM_REPAIR], [ROOM_RENDERER], owner_context=context,
        )
        for key in ("identity", "binding", "authorization_context", "attempt", "branch",
                    "worktree", "state", "repairs"):
            self.assertEqual(evolved[key], record[key])
        self.assertEqual(evolved["revision"], record["revision"] + 1)
        self.assertEqual(evolved["capabilities"][ROOM_CAPABILITY], [ROOM_RENDERER])
        with self.assertRaisesRegex(PortError, "Stale capability reservation revision"):
            self.ledger.evolve_scope(
                "WP-502", record["attempt"], record["revision"], capabilities,
                [ROOM_REPAIR], [ROOM_RENDERER], owner_context=context,
            )
        self.assertEqual(self.ledger.get("WP-502"), evolved)
        self.assertEqual(self.ledger.evolve_scope(
            "WP-502", record["attempt"], evolved["revision"], capabilities,
            [ROOM_REPAIR], [ROOM_RENDERER], owner_context=context,
        ), evolved)

    def test_rooms_repair_keeps_semantic_collision_and_current_producer_transfer_checks(self):
        intent = {"owner": "WP-502", "semantic_key": "incoming-gutter",
                  "value_digest": "a" * 64, "evidence": "mounted-rooms"}
        with self.assertRaisesRegex(PortError, "Current base changed"):
            self.engine.reconcile_intents(BASE, "9" * 40, ROOM_RENDERER, ROOM_CAPABILITY, [intent])
        with self.assertRaisesRegex(PortError, "Unresolved semantic ownership collision"):
            self.engine.reconcile_intents(BASE, BASE, ROOM_RENDERER, ROOM_CAPABILITY, [
                intent, {**intent, "owner": "WP-310", "value_digest": "b" * 64},
            ])
        origin = {"wp": "WP-310", "session": "historical-producer", "evidence": "primary-provenance"}
        producer = {"wp": "WP-502", "session": "existing-502", "evidence": "mounted-rooms"}
        transfer = self.engine.transfer_intent(origin, producer, ROOM_RENDERER, ROOM_CAPABILITY)
        self.assertEqual(transfer["origin"], origin)
        self.assertEqual(transfer["producer"], producer)
        self.assertTrue(transfer["advisory"])
        with self.assertRaisesRegex(PortError, "distinct owner sessions"):
            self.engine.transfer_intent(origin, {**producer, "session": origin["session"]},
                                        ROOM_RENDERER, ROOM_CAPABILITY)

    def test_assigned_path_with_unsupported_operation_fails_closed(self):
        assigned_path = self.manifest.wp("WP-101")["write_paths"][0]
        with self.assertRaisesRegex(PortError, rf"WP WP-101.*{assigned_path}.*unsupported-operation"):
            self.engine.admit("WP-101", self.manifest.wp("WP-101")["write_paths"],
                              assigned_path, "unsupported-operation")

    def test_rooms_actions_exact_paths_and_named_operation_do_not_widen_avatar_or_other_features(self):
        assigned = test_manifest("WP-502").wp("WP-502")["write_paths"]
        admissions = self.engine.admit_scope("WP-502", assigned, ROOM_ACTIONS_PATHS, [ROOM_ACTIONS_OPERATION])
        self.assertEqual({admission.capability_id for admission in admissions}, {"rooms-message-actions"})
        self.assertEqual({admission.path for admission in admissions}, set(ROOM_ACTIONS_PATHS))
        self.assertTrue(all(path != p and not path.startswith(p.rstrip("/") + "/")
                            for p in assigned for path in ROOM_ACTIONS_PATHS))
        for wp, path, operation in (
            *[(wp, ROOM_RENDERER, ROOM_ACTIONS_OPERATION) for wp in
              ("WP-003", "WP-501", "WP-503", "WP-310")],
            *[("WP-502", path, ROOM_ACTIONS_OPERATION) for path in (
                ROOM_RENDERER.rsplit("/", 1)[0],
                ROOM_RENDERER.replace("rooms/", "channels/"),
                ROOM_RENDERER.replace("Screen.kt", "Service.kt"),
                ROOM_RENDERER.replace("Screen.kt", "AuthenticationSheet.kt"),
                ROOM_RENDERER.replace("RoomConversationScreen.kt", "UnknownRoomAction.kt"),
                ROOM_RENDERER + ".backup",
                "android/core/services/RoomService.kt",
                "android/app/src/main/kotlin/RoomActions.kt",
            )],
            *[("WP-502", path, operation) for path in ROOM_ACTIONS_PATHS
              for operation in ("modify", "create", "delete", "implement-translation", "unknown")],
            *[("WP-502", path, ROOM_REPAIR) for path in ROOM_ACTIONS_PATHS if path != ROOM_RENDERER],
        ):
            with self.subTest(wp=wp, path=path, operation=operation), self.assertRaisesRegex(
                PortError, r"no trusted path\+operation rule"
            ):
                self.engine.admit(wp, [], path, operation)
        avatar = next(r for r in self.rules["reservation_policy"]["capabilities"] if r["id"] == ROOM_CAPABILITY)
        self.assertEqual(avatar, ROOMS_AVATAR_CAPABILITY)
        self.assertEqual(self.engine.admit("WP-502", [], ROOM_RENDERER, ROOM_REPAIR).capability_id,
                         ROOM_CAPABILITY)

    def test_rooms_actions_invariants_and_candidate_policy_are_not_live_product_authority(self):
        rule = next(r for r in self.rules["reservation_policy"]["capabilities"]
                    if r["id"] == "rooms-message-actions")
        self.assertEqual(rule["work_packages"], ["WP-502"])
        self.assertEqual(rule["path_rules"], ROOM_ACTIONS_PATHS)
        self.assertEqual(rule["operations"], [ROOM_ACTIONS_OPERATION])
        self.assertEqual(set(rule["invariants"]), {
            "pinned-source-db14559b39d32322b06477c6ae676112f583db50",
            "WP-310-primary-provenance-unchanged", "existing-WP-502-owner-same-reservation",
            "original-copy-reply-mention-sendDM-sendAgain-availability-and-metadata",
            "presentation-time-availability-stable-until-dismissal",
            "real-clipboard-preference-composer-focus-navigation-and-room-post-effects",
            "sendAgain-post-original-text-without-clearing-existing-draft",
            "radio-scoped-fresh-contacts-excludeBlocked-and-room-DTO-not-DM-conversion",
            "source-dismissal-delay-before-focus-or-sendDM-presentation",
            "once-only-accessibility-label-and-long-press-link-suppression",
            "reuse-shared-native-identity-theme-and-localized-actions",
            "translation-removed-no-provider-or-action", "no-unrelated-auth-service-ordering-or-activation-change",
            "avatar-only-capability-unchanged", "semantic-collisions-serialize-or-transfer-current-producer",
            "trusted-main-admission-before-product-edit",
            "no-main-policy-landing-during-held-native-or-gradle-operation",
        })
        self.assertEqual(len(rule["validation"]), 5)
        trusted = copy.deepcopy(self.rules)
        trusted["reservation_policy"]["capabilities"].remove(rule)
        for path in ROOM_ACTIONS_PATHS:
            with self.subTest(path=path), self.assertRaises(PortError):
                CapabilityEngine(trusted).admit("WP-502", [], path, ROOM_ACTIONS_OPERATION)
        for key in ("paused", "dispatch_mode", "activation_approved", "merge_gate_policy",
                    "review_policy", "protected_paths", "admin_bypass"):
            self.assertEqual(trusted[key], self.rules[key])

    def test_rooms_actions_same_owner_evolution_and_semantic_fences_remain_required(self):
        wp = test_manifest("WP-502").wp("WP-502")
        binding = {"repository": self.rules["repository"], "work_package": "WP-502",
                   "base_sha": BASE, "head_sha": "2" * 40, "source_sha": "d" * 40,
                   "manifest_sha256": "e" * 64, "policy_revision": "f" * 64}
        owner = {"session_id": "existing-502", "branch": "existing-branch", "worktree": "C:\\existing"}
        _, acquired = self.ledger.claim(
            wp, binding, "local", "same-attempt", NOW, 1800, 5, 0, 100, 5,
            initial_identity=Identity(session_id=owner["session_id"]),
            branch=owner["branch"], worktree=owner["worktree"],
            authorization_context={"authorization": "immutable original authorization"},
        )
        self.assertTrue(acquired)
        before = self.ledger.get("WP-502")
        admitted = self.engine.admit_scope("WP-502", wp["write_paths"], ROOM_ACTIONS_PATHS,
                                           [ROOM_ACTIONS_OPERATION])
        capabilities = {"rooms-message-actions": [a.path for a in admitted]}
        with self.assertRaises(PortError):
            self.ledger.evolve_scope("WP-502", before["attempt"], before["revision"], capabilities,
                                     [ROOM_ACTIONS_OPERATION], ROOM_ACTIONS_PATHS,
                                     owner_context={**owner, "session_id": "foreign"})
        self.assertEqual(self.ledger.get("WP-502"), before)
        evolved = self.ledger.evolve_scope(
            "WP-502", before["attempt"], before["revision"], capabilities,
            [ROOM_ACTIONS_OPERATION], ROOM_ACTIONS_PATHS, owner_context=owner,
        )
        for key in ("identity", "binding", "authorization_context", "attempt", "branch",
                    "worktree", "state", "repairs"):
            self.assertEqual(evolved[key], before[key])
        self.assertEqual(evolved["capabilities"]["rooms-message-actions"], sorted(ROOM_ACTIONS_PATHS))
        with self.assertRaises(PortError):
            self.ledger.evolve_scope("WP-502", before["attempt"], before["revision"], capabilities,
                                     [ROOM_ACTIONS_OPERATION], ROOM_ACTIONS_PATHS, owner_context=owner)
        self.assertEqual(self.ledger.get("WP-502"), evolved)
        self.assertEqual(self.ledger.evolve_scope(
            "WP-502", before["attempt"], evolved["revision"], capabilities,
            [ROOM_ACTIONS_OPERATION], ROOM_ACTIONS_PATHS, owner_context=owner,
        ), evolved)
        intent = {"owner": "existing-502", "semantic_key": "message-actions",
                  "value_digest": "a" * 64, "evidence": "mounted-originals"}
        with self.assertRaisesRegex(PortError, "Current base changed"):
            self.engine.reconcile_intents(BASE, "9" * 40, ROOM_RENDERER, "rooms-message-actions", [intent])
        with self.assertRaisesRegex(PortError, "Unresolved semantic ownership collision"):
            self.engine.reconcile_intents(BASE, BASE, ROOM_RENDERER, "rooms-message-actions", [
                intent, {**intent, "owner": "other", "value_digest": "b" * 64},
            ])

    def test_picker_exact_admission_does_not_repurpose_rooms_or_editor_permission(self):
        assigned = test_manifest("WP-502").wp("WP-502")["write_paths"]
        for path in PICKER_PATHS:
            admission = self.engine.admit("WP-502", assigned, path, PICKER_OPERATION)
            self.assertEqual(admission.capability_id, PICKER_CAPABILITY)
        for wp, path, operation in (
            *[(wp, PICKER_PATH, PICKER_OPERATION) for wp in ("WP-003", "WP-306", "WP-308", "WP-501", "WP-503")],
            *[("WP-502", path, PICKER_OPERATION) for path in (
                PICKER_PATH.rsplit("/", 1)[0], PICKER_PATH + ".backup",
                PICKER_PATH.replace("MentionSuggestions.kt", "MentionPickerSheet.kt"),
                PICKER_PATH.replace("composer/ui/MentionSuggestions.kt", "list/ui/NewChatSheet.kt"),
                "android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatsContent.kt",
                "android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatConversationScreen.kt",
                *[p for p in ROOM_ACTIONS_PATHS if p != ROOM_RENDERER],
            )],
            *[("WP-502", PICKER_PATH, operation) for operation in
              ("modify", "create", "delete", ROOM_REPAIR, ROOM_ACTIONS_OPERATION, "unknown-operation")],
        ):
            with self.subTest(wp=wp, path=path, operation=operation), self.assertRaisesRegex(
                PortError, r"no trusted path\+operation rule"
            ):
                self.engine.admit(wp, [], path, operation)
        rules = {r["id"]: r for r in self.rules["reservation_policy"]["capabilities"]}
        self.assertEqual(rules[ROOM_CAPABILITY], ROOMS_AVATAR_CAPABILITY)
        self.assertEqual(rules["rooms-message-actions"], ROOMS_MESSAGE_ACTIONS_CAPABILITY)
        trusted = copy.deepcopy(self.rules)
        trusted["reservation_policy"]["capabilities"].remove(rules[PICKER_CAPABILITY])
        for path in PICKER_PATHS:
            with self.subTest(path=path), self.assertRaises(PortError):
                CapabilityEngine(trusted).admit("WP-502", assigned, path, PICKER_OPERATION)
            for wp in ("WP-003", "WP-306", "WP-308", "WP-310", "WP-501", "WP-503"):
                with self.subTest(path=path, wp=wp), self.assertRaises(PortError):
                    self.engine.admit(wp, [], path, PICKER_OPERATION)
            for operation in ("modify", "create", "delete", "repair-mention-picker-contact-metadata"):
                with self.subTest(path=path, operation=operation), self.assertRaises(PortError):
                    self.engine.admit("WP-502", assigned, path, operation)
        for name in ("AppChatsContent.kt", "AppChatConversationScreen.kt"):
            path = "android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/" + name
            self.assertEqual(self.engine.admit(
                "WP-502", assigned, path, "update-launcher-or-composition",
            ).capability_id, "app-build-launcher")

    def test_picker_invariants_preserve_real_metadata_callbacks_and_editor_without_fake_location(self):
        rule = next(r for r in self.rules["reservation_policy"]["capabilities"] if r["id"] == PICKER_CAPABILITY)
        self.assertEqual(rule["work_packages"], ["WP-502"])
        self.assertEqual(rule["path_rules"], PICKER_PATHS)
        self.assertEqual(rule["operations"], [PICKER_OPERATION])
        self.assertEqual(set(rule["invariants"]), {
            "pinned-source-db14559b39d32322b06477c6ae676112f583db50",
            "WP-308-picker-and-WP-306-match-row-primary-provenance-unchanged",
            "existing-WP-502-owner-same-reservation", "reuse-real-ContactMatchRow-Tap-for-matching-picker-rows",
            "original-ordered-contact-identities-selection-dismiss-self-unknown-Done-focus",
            "source-scroll-and-large-font-reachability", "existing-theme-localization-identity-48dp-Tap-semantics",
            "editor-MentionSuggestionList-and-MentionSuggestionRow-byte-and-behavior-unchanged",
            "no-fabricated-location-distance-or-clock-no-full-location-parity-from-nil",
            "all-three-shared-sheet-callers-forward-clock-and-optional-preformatted-distance",
            "real-radio-wall-clock-instants-with-system-display-zone-and-fixed-clock-tests",
            "authorized-valid-screen-local-phone-then-same-radio-hasLocation-device-then-nil-no-opening-permission-or-fix",
            "Room-node-only-fallback-no-app-wide-phone-cache-parity-claim",
            "App-existing-location-policy-road-distance-and-localized-away-wrapper-no-feature-dependency",
            "current-location-permission-device-locale-inputs-no-stale-formatter-capture",
            "all-matches-no-editor-cap-block-filter-recency-sort-or-key-truncation",
            "bounded-scroll-over20-compact-200percent-CJK-RTL-independently-reachable-Done",
            "dismiss-before-once-only-full-contact-detail-navigation-no-insertion-autofocus-or-draft-effects",
            "existing-App-support-separate-authority-no-new-App-core-provider-dependency-or-service-paths",
            "no-auth-service-radio-state-activation-or-provider-change",
            "rooms-actions-and-avatar-capabilities-unchanged",
            "semantic-collisions-serialize-or-transfer-current-producer",
            "trusted-main-admission-before-product-edit",
            "no-main-policy-landing-during-held-native-or-gradle-operation",
        })
        self.assertEqual(len(rule["validation"]), 7)

    def test_picker_same_owner_evolution_stale_foreign_and_semantic_collision_fences(self):
        wp = test_manifest("WP-502").wp("WP-502")
        binding = {"repository": self.rules["repository"], "work_package": "WP-502",
                   "base_sha": BASE, "head_sha": "2" * 40, "source_sha": "d" * 40,
                   "manifest_sha256": "e" * 64, "policy_revision": "f" * 64}
        owner = {"session_id": "existing-502", "branch": "existing-branch", "worktree": "C:\\existing"}
        self.ledger.claim(wp, binding, "local", "same-attempt", NOW, 1800, 5, 0, 100, 5,
                          initial_identity=Identity(session_id=owner["session_id"]),
                          branch=owner["branch"], worktree=owner["worktree"],
                          authorization_context={"authorization": "immutable original"})
        before = self.ledger.get("WP-502")
        admissions = self.engine.admit_scope("WP-502", wp["write_paths"], PICKER_PATHS, [PICKER_OPERATION])
        caps = {PICKER_CAPABILITY: [a.path for a in admissions]}
        for attempt, context in (
            ("foreign-attempt", owner),
            ("same-attempt", {**owner, "session_id": "foreign"}),
            ("same-attempt", {**owner, "branch": "foreign"}),
            ("same-attempt", {**owner, "worktree": "C:\\foreign"}),
        ):
            with self.subTest(attempt=attempt, context=context), self.assertRaises(PortError):
                self.ledger.evolve_scope("WP-502", attempt, before["revision"], caps,
                                         [PICKER_OPERATION], PICKER_PATHS, owner_context=context)
            self.assertEqual(self.ledger.get("WP-502"), before)
        evolved = self.ledger.evolve_scope(
            "WP-502", before["attempt"], before["revision"], caps,
            [PICKER_OPERATION], PICKER_PATHS, owner_context=owner,
        )
        for key in ("identity", "binding", "authorization_context", "attempt", "branch", "worktree", "state", "repairs"):
            self.assertEqual(evolved[key], before[key])
        self.assertEqual(evolved["capabilities"][PICKER_CAPABILITY], sorted(PICKER_PATHS))
        with self.assertRaisesRegex(PortError, "Stale capability reservation"):
            self.ledger.evolve_scope("WP-502", before["attempt"], before["revision"], caps,
                                     [PICKER_OPERATION], PICKER_PATHS, owner_context=owner)
        self.assertEqual(self.ledger.get("WP-502"), evolved)
        self.assertEqual(self.ledger.evolve_scope(
            "WP-502", before["attempt"], evolved["revision"], caps,
            [PICKER_OPERATION], PICKER_PATHS, owner_context=owner,
        ), evolved)
        intent = {"owner": "existing-502", "semantic_key": "picker-row",
                  "value_digest": "a" * 64, "evidence": "mounted-picker"}
        with self.assertRaisesRegex(PortError, "Current base changed"):
            self.engine.reconcile_intents(BASE, "9" * 40, PICKER_PATH, PICKER_CAPABILITY, [intent])
        with self.assertRaisesRegex(PortError, "Unresolved semantic ownership collision"):
            self.engine.reconcile_intents(BASE, BASE, PICKER_PATH, PICKER_CAPABILITY, [
                intent, {**intent, "owner": "other", "value_digest": "b" * 64},
            ])

    def test_translation_affordance_removal_admits_only_exact_wp_path_and_operation(self):
        manifest = test_manifest("WP-502")
        assigned = manifest.wp("WP-502")["write_paths"]
        admission = self.engine.admit("WP-502", assigned, TRANSLATION_ACTION_PATH, TRANSLATION_REMOVAL_OPERATION)
        self.assertEqual(admission.capability_id, TRANSLATION_REMOVAL_CAPABILITY)
        self.assertEqual((admission.path, admission.operation),
                         (TRANSLATION_ACTION_PATH, TRANSLATION_REMOVAL_OPERATION))
        self.assertFalse(any(TRANSLATION_ACTION_PATH.startswith(p) for p in assigned))
        self.assertTrue(any(TRANSLATION_ACTION_PATH.startswith(p)
                            for p in manifest.wp("WP-309")["write_paths"]))
        for wp, path, operation in (
            *[(wp, TRANSLATION_ACTION_PATH, TRANSLATION_REMOVAL_OPERATION)
              for wp in ("WP-003", "WP-309", "WP-407", "WP-501", "WP-503")],
            *[("WP-502", path, TRANSLATION_REMOVAL_OPERATION) for path in (
                TRANSLATION_ACTION_PATH.rsplit("/", 1)[0], TRANSLATION_ACTION_PATH + ".backup",
                TRANSLATION_ACTION_PATH.replace("MessageActionsUi.kt", "MessageActionsState.kt"),
                TRANSLATION_ACTION_PATH.replace("MessageActionsUi.kt", "MessageActionModels.kt"),
                PICKER_PATH, ROOM_RENDERER,
                "android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppMessageActions.kt",
                "android/core/model/src/main/kotlin/MessageDTO.kt",
                "android/feature/chats/build.gradle.kts",
                "android/feature/chats/src/main/res/values/strings.xml",
            )],
            *[("WP-502", TRANSLATION_ACTION_PATH, operation) for operation in
              ("modify", "create", "delete", PICKER_OPERATION, ROOM_ACTIONS_OPERATION, "unknown-operation")],
        ):
            with self.subTest(wp=wp, path=path, operation=operation), self.assertRaisesRegex(
                PortError, r"no trusted path\+operation rule"
            ):
                self.engine.admit(wp, manifest.wp(wp)["write_paths"], path, operation)

    def test_translation_affordance_invariants_and_actual_trusted_policy_prevent_self_admission(self):
        rule = next(r for r in self.rules["reservation_policy"]["capabilities"]
                    if r["id"] == TRANSLATION_REMOVAL_CAPABILITY)
        self.assertEqual(rule, MESSAGE_ACTIONS_TRANSLATION_REMOVAL_CAPABILITY)
        self.assertEqual(rule["work_packages"], ["WP-502"])
        self.assertEqual(rule["path_rules"], [TRANSLATION_ACTION_PATH])
        self.assertEqual(rule["operations"], [TRANSLATION_REMOVAL_OPERATION])
        self.assertEqual(set(rule["invariants"]), {
            "pinned-source-db14559b39d32322b06477c6ae676112f583db50",
            "WP-309-primary-provenance-unchanged", "existing-WP-502-owner-same-reservation",
            "remove-only-active-Translate-affordance-from-real-public-MessageActionsRoute",
            "no-renamed-hidden-or-alternate-reachable-translation-action",
            "all-other-real-actions-availability-theme-localized-labels-48dp-dismissal-state-and-side-effects-unchanged",
            "original-message-text-and-app-localization-preserved",
            "inert-Translate-enum-handler-helper-may-remain-no-App-State-core-build-resource-widening",
            "no-translation-provider-model-backend-billing-or-new-service",
            "no-fake-renderer-or-removal-of-action-section",
            "existing-Room-mention-picker-and-avatar-rules-unchanged",
            "semantic-collisions-serialize-or-transfer-current-producer",
            "trusted-main-admission-before-product-edit",
            "no-main-policy-landing-during-held-native-or-gradle-operation",
        })
        self.assertEqual(len(rule["validation"]), 5)
        for revision in ("aa4b0b425b3ee58485191c7ede39e69ca8a1acf5",
                         "dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c"):
            trusted = json.loads(subprocess.check_output([
                "git", "-C", str(REPO), "show", revision + ":docs/android/automation-policy.json",
            ]))
            before = copy.deepcopy(trusted)
            with self.subTest(revision=revision), self.assertRaisesRegex(
                PortError, r"no trusted path\+operation rule"
            ):
                CapabilityEngine(trusted).admit(
                    "WP-502", test_manifest("WP-502").wp("WP-502")["write_paths"],
                    TRANSLATION_ACTION_PATH, TRANSLATION_REMOVAL_OPERATION,
                )
            self.assertEqual(trusted, before)

    def test_translation_affordance_same_owner_evolution_and_semantic_collision_fences(self):
        wp = test_manifest("WP-502").wp("WP-502")
        owner = {"session_id": "existing-502", "branch": "existing-branch", "worktree": "C:\\existing"}
        self.ledger.claim(
            wp, {"base_sha": BASE}, "local", "existing-attempt", NOW, 1800, 5, 0, 100, 5,
            initial_identity=Identity(session_id=owner["session_id"]),
            branch=owner["branch"], worktree=owner["worktree"],
            authorization_context={"authorization": "immutable original"},
        )
        before = self.ledger.get("WP-502")
        caps = {TRANSLATION_REMOVAL_CAPABILITY: [TRANSLATION_ACTION_PATH]}
        for attempt, context in (
            ("foreign-attempt", owner),
            ("existing-attempt", {**owner, "session_id": "foreign"}),
            ("existing-attempt", {**owner, "branch": "foreign"}),
            ("existing-attempt", {**owner, "worktree": "C:\\foreign"}),
        ):
            with self.subTest(attempt=attempt, context=context), self.assertRaises(PortError):
                self.ledger.evolve_scope("WP-502", attempt, before["revision"], caps,
                                         [TRANSLATION_REMOVAL_OPERATION], [TRANSLATION_ACTION_PATH],
                                         owner_context=context)
            self.assertEqual(self.ledger.get("WP-502"), before)
        evolved = self.ledger.evolve_scope(
            "WP-502", before["attempt"], before["revision"], caps,
            [TRANSLATION_REMOVAL_OPERATION], [TRANSLATION_ACTION_PATH], owner_context=owner,
        )
        self.assertEqual(evolved["revision"], before["revision"] + 1)
        self.assertEqual(evolved["capabilities"][TRANSLATION_REMOVAL_CAPABILITY], [TRANSLATION_ACTION_PATH])
        for key in ("identity", "binding", "authorization_context", "attempt", "branch", "worktree", "state", "repairs"):
            self.assertEqual(evolved[key], before[key])
        with self.assertRaisesRegex(PortError, "Stale capability reservation"):
            self.ledger.evolve_scope("WP-502", before["attempt"], before["revision"], caps,
                                     [TRANSLATION_REMOVAL_OPERATION], [TRANSLATION_ACTION_PATH], owner_context=owner)
        self.assertEqual(self.ledger.get("WP-502"), evolved)
        self.assertEqual(self.ledger.evolve_scope(
            "WP-502", before["attempt"], evolved["revision"], caps,
            [TRANSLATION_REMOVAL_OPERATION], [TRANSLATION_ACTION_PATH], owner_context=owner,
        ), evolved)
        intent = {"owner": "existing-502", "semantic_key": "active-translation-affordance",
                  "value_digest": "a" * 64, "evidence": "future-mounted-real-route"}
        with self.assertRaisesRegex(PortError, "Current base changed"):
            self.engine.reconcile_intents(BASE, "9" * 40, TRANSLATION_ACTION_PATH,
                                          TRANSLATION_REMOVAL_CAPABILITY, [intent])
        with self.assertRaisesRegex(PortError, "Unresolved semantic ownership collision"):
            self.engine.reconcile_intents(BASE, BASE, TRANSLATION_ACTION_PATH, TRANSLATION_REMOVAL_CAPABILITY, [
                intent, {**intent, "owner": "other", "value_digest": "b" * 64},
            ])

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
