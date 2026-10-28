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
    REPEAT_ROW_FALLBACK_CAPABILITY, CHANNEL_OPEN_FLOOD_SCOPE_CAPABILITY,
    parity_support_predecessor,
    RECEIVE_PREFETCH_ADMISSION_CAPABILITY, receive_prefetch_predecessor,
    ROOM_CLEANUP_CAPABILITY, room_cleanup_predecessor,
    BUBBLE_CONSUMER_CAPABILITY, bubble_consumer_predecessor,
    ROOM_FAILED_EVENT_CAPABILITY, room_failed_event_predecessor,
    routine_support_predecessor,
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

    def test_related_support_is_shared_but_path_and_operation_bounded(self):
        for wp in ("WP-208", "WP-213", "WP-501"):
            for path, operation, capability in (
                ("android/core/data/src/test/kotlin/RoomTest.kt", "add-or-update-related-test",
                 "reviewed-support-test"),
                ("android/core/data/src/testDebug/kotlin/RoomTest.kt", "add-or-update-related-test",
                 "reviewed-support-test"),
                (ROOM_RENDERER, "modify-related-consumer", "reviewed-support-consumer"),
            ):
                with self.subTest(wp=wp, path=path):
                    self.assertEqual(self.engine.admit(wp, [], path, operation).capability_id, capability)
        for path, operation in (
            ("android/core/data/build.gradle.kts", "modify-related-consumer"),
            ("android/core/data/src/main/kotlin/Foo.kt", "add-or-update-related-test"),
            ("android/core/data/src/test/kotlin/Foo.kt", "modify-related-consumer"),
            ("android/core/data/src/test/kotlin/Foo.kt", "delete"),
            ("android/build-logic/src/test/kotlin/Foo.kt", "add-or-update-related-test"),
            ("tools/android-port/controller/engine.py", "modify-related-consumer"),
        ):
            with self.subTest(path=path, operation=operation), self.assertRaises(PortError):
                self.engine.admit("WP-208", [], path, operation)

    def test_related_support_checks_actual_base_and_candidate(self):
        consumer = "android/feature/chats/src/main/kotlin/Consumer.kt"
        test = "android/core/data/src/test/kotlin/RoomTest.kt"
        operations = {consumer: "modify-related-consumer", test: "add-or-update-related-test"}
        CapabilityEngine.validate_support_changes(operations, {consumer: "base"}, {consumer: "head", test: "head"})
        for base, head in (
            ({}, {consumer: "head", test: "head"}),
            ({consumer: "base"}, {test: "head"}),
            ({consumer: "base", test: "base"}, {consumer: "head"}),
        ):
            with self.subTest(base=base, head=head), self.assertRaises(PortError):
                CapabilityEngine.validate_support_changes(operations, base, head)

    def test_related_support_preserves_and_checks_historical_policy(self):
        current = json.loads((REPO / "docs/android/automation-policy.json").read_text(encoding="utf-8"))
        historical = routine_support_predecessor(current)
        actual = json.loads(subprocess.check_output(
            ["git", "-C", str(REPO), "show",
             "a9b30473e1285c55ada38a191f808563aca57005:docs/android/automation-policy.json"],
            encoding="utf-8",
        ))
        self.assertEqual(historical, actual)
        self.assertEqual(routine_support_predecessor(actual), actual)
        for field in ("path_rules", "operations", "invariants", "validation"):
            altered = copy.deepcopy(self.rules)
            altered["reservation_policy"]["capabilities"][0][field] = (
                ["MC1/**"] if field == "path_rules" else []
            )
            with self.subTest(field=field), self.assertRaises(PortError):
                routine_support_predecessor(altered)
        extended = copy.deepcopy(current)
        extra = copy.deepcopy(extended["reservation_policy"]["capabilities"][0])
        extra["id"] = "reviewed-support-new-fixture"
        extra["path_rules"] = ["tools/android-port/tests/*.py"]
        extended["reservation_policy"]["capabilities"].insert(0, extra)
        self.assertEqual(routine_support_predecessor(extended), actual)

    def test_wp502_failed_event_exact_two_paths_operation_and_existing_app_adapter_support(self):
        rule = ROOM_FAILED_EVENT_CAPABILITY
        operation = "repair-room-failed-event-consumer"
        root = "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/rooms/"
        self.assertEqual(rule["work_packages"], ["WP-502"])
        self.assertEqual(rule["operations"], [operation])
        self.assertEqual(rule["path_rules"], [root + "RoomContracts.kt", root + "RoomConversationStateHolder.kt"])
        self.assertEqual(next(r for r in self.rules["reservation_policy"]["capabilities"]
                              if r["id"] == rule["id"]), rule)
        manifest = test_manifest("WP-502")
        for path in rule["path_rules"]:
            admitted = self.engine.admit("WP-502", manifest.wp("WP-502")["write_paths"], path, operation)
            self.assertEqual((admitted.capability_id, admitted.path, admitted.operation),
                             (rule["id"], path, operation))
            for wp in ("WP-003", "WP-310", "WP-501", "WP-503"):
                with self.subTest(wp=wp, path=path), self.assertRaises(PortError):
                    self.engine.admit(wp, manifest.wp(wp)["write_paths"], path, operation)
            for wrong in ("modify", "create", "delete", "repair-original-message-bubble-accessibility-consumer", "unknown"):
                with self.subTest(operation=wrong), self.assertRaises(PortError):
                    self.engine.admit("WP-502", manifest.wp("WP-502")["write_paths"], path, wrong)
            for wrong in (path + ".backup", root.rstrip("/"), root + "Other.kt",
                          path.replace("/src/main/", "/src/test/"), path.replace("/src/main/", "/src/androidTest/")):
                with self.subTest(path=wrong), self.assertRaises(PortError):
                    self.engine.admit("WP-502", manifest.wp("WP-502")["write_paths"], wrong, operation)
            with self.assertRaises(PortError):
                CapabilityEngine(room_failed_event_predecessor(self.rules)).admit("WP-502", [], path, operation)
        app = "android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatsFeature.kt"
        self.assertEqual(self.engine.admit("WP-502", manifest.wp("WP-502")["write_paths"], app,
                                          "update-launcher-or-composition").capability_id, "app-build-launcher")
        for wrong in (app, root + "RoomConversationScreen.kt",
                      "android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/MessageEventDispatcher.kt"):
            with self.subTest(path=wrong), self.assertRaises(PortError):
                self.engine.admit("WP-502", manifest.wp("WP-502")["write_paths"], wrong, operation)
        source = "MC1/Views/RemoteNodes/Rooms/RoomConversationViewModel.swift"
        self.assertEqual(next(e for e in manifest.data["inventory"] if e["path"] == source)["primary_owner"], "WP-310")

    def test_wp501_bubble_consumer_exact_path_named_operation_and_existing_companions(self):
        rule = BUBBLE_CONSUMER_CAPABILITY
        path = "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/timeline/ui/ChatTimeline.kt"
        operation = "repair-original-message-bubble-accessibility-consumer"
        self.assertEqual(rule["work_packages"], ["WP-501"])
        self.assertEqual(rule["path_rules"], [path])
        self.assertEqual(rule["operations"], [operation])
        self.assertEqual(next(r for r in self.rules["reservation_policy"]["capabilities"]
                              if r["id"] == rule["id"]), rule)
        manifest = test_manifest("WP-501")
        admitted = self.engine.admit("WP-501", manifest.wp("WP-501")["write_paths"], path, operation)
        self.assertEqual((admitted.capability_id, admitted.path, admitted.operation),
                         (rule["id"], path, operation))
        for wp in ("WP-003", "WP-307", "WP-213", "WP-502", "WP-503"):
            with self.subTest(wp=wp), self.assertRaises(PortError):
                self.engine.admit(wp, manifest.wp(wp)["write_paths"], path, operation)
        for wrong in ("modify", "create", "delete", "repair-receive-prefetch-admission",
                      "update-launcher-or-composition", "unknown"):
            with self.subTest(operation=wrong), self.assertRaises(PortError):
                self.engine.admit("WP-501", manifest.wp("WP-501")["write_paths"], path, wrong)
        for wrong in (
            path + ".backup", path.rsplit("/", 1)[0], path.replace("ChatTimeline.kt", "Other.kt"),
            path.replace("/src/main/", "/src/test/"), path.replace("/src/main/", "/src/androidTest/"),
            path.replace("/ui/ChatTimeline.kt", "/TimelineContracts.kt"),
            "android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/rendering/MessageFragmentBuilder.kt",
            "android/app/build.gradle.kts",
        ):
            with self.subTest(path=wrong), self.assertRaises(PortError):
                self.engine.admit("WP-501", manifest.wp("WP-501")["write_paths"], wrong, operation)
        with self.assertRaises(PortError):
            CapabilityEngine(bubble_consumer_predecessor(self.rules)).admit("WP-501", [], path, operation)
        self.assertEqual(self.engine.admit(
            "WP-501", manifest.wp("WP-501")["write_paths"],
            "android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatConversationScreen.kt",
            "update-launcher-or-composition",
        ).capability_id, "app-build-launcher")
        self.assertEqual(self.engine.admit(
            "WP-501", manifest.wp("WP-501")["write_paths"],
            "android/parity-tests/BubbleConsumerTest.kt", "test",
        ).capability_id, "wp-owned")
        for original, owner in (
            ("MC1/Views/Chats/Components/UnifiedMessageBubble.swift", "WP-307"),
            ("MC1Tests/Views/Chats/Components/UnifiedMessageBubbleA11yLabelTests.swift", "WP-307"),
            ("MC1Services/Sources/MC1Services/Services/MessageFragmentBuilder.swift", "WP-213"),
        ):
            self.assertEqual(next(e for e in manifest.data["inventory"]
                                  if e["path"] == original)["primary_owner"], owner)

    def test_wp503_accessibility_source_binding_rules_are_exact_and_separate(self):
        rules = {
            rule["id"]: rule
            for rule in self.rules["reservation_policy"]["capabilities"]
        }
        validator_rule = rules["wp503-accessibility-source-binding"]
        regression_rule = rules["wp503-accessibility-source-binding-regression"]
        self.assertEqual(
            self.rules["reservation_policy"]["capabilities"][-2:],
            [validator_rule, regression_rule],
        )
        self.assertEqual(validator_rule["work_packages"], ["WP-503"])
        self.assertEqual(
            validator_rule["path_rules"],
            ["android/accessibility-tests/language_binding.py"],
        )
        self.assertEqual(validator_rule["operations"], ["add-or-update-validator-rule"])
        self.assertEqual(
            validator_rule["invariants"],
            [
                "immutable-historical-producer-path-blob",
                "distinct-current-candidate-path-blob-bound-to-candidate-head",
                "producer-ancestry-and-selected-fixture-method-identities",
                "reject-historical-pin-current-pin-and-unrelated-source-drift",
                "no-fake-refreshed-compatibility",
            ],
        )
        self.assertEqual(regression_rule["work_packages"], ["WP-503"])
        self.assertEqual(
            regression_rule["path_rules"],
            ["android/accessibility-tests/test_language_binding.py"],
        )
        self.assertEqual(regression_rule["operations"], ["add-regression-fixture-or-test"])
        self.assertEqual(
            regression_rule["invariants"],
            [
                "preserve-original-fixture-and-selected-method-assertions",
                "test-historical-and-current-source-bindings-independently",
                "reject-historical-current-and-unrelated-source-drift",
            ],
        )
        self.assertNotIn("add-or-update-validator-rule", rules["wp-owned"]["operations"])
        self.assertNotIn("add-regression-fixture-or-test", rules["wp-owned"]["operations"])

        manifest = test_manifest("WP-503")
        assigned_paths = manifest.wp("WP-503")["write_paths"]
        validator_path = validator_rule["path_rules"][0]
        regression_path = regression_rule["path_rules"][0]
        admitted_validator = self.engine.admit(
            "WP-503", assigned_paths, validator_path, "add-or-update-validator-rule",
        )
        self.assertEqual(
            (admitted_validator.capability_id, admitted_validator.path, admitted_validator.operation),
            (validator_rule["id"], validator_path, "add-or-update-validator-rule"),
        )
        self.assertEqual(
            admitted_validator.invariants, tuple(validator_rule["invariants"]),
        )
        admitted_regression = self.engine.admit(
            "WP-503", assigned_paths, regression_path, "add-regression-fixture-or-test",
        )
        self.assertEqual(
            (admitted_regression.capability_id, admitted_regression.path, admitted_regression.operation),
            (regression_rule["id"], regression_path, "add-regression-fixture-or-test"),
        )
        self.assertEqual(admitted_regression.invariants, tuple(regression_rule["invariants"]))

        for wp in ("WP-000", "WP-316", "WP-501"):
            with self.subTest(wp=wp), self.assertRaises(PortError):
                self.engine.admit(
                    wp, test_manifest(wp).wp(wp)["write_paths"],
                    validator_path, "add-or-update-validator-rule",
                )
        for path, operation in (
            (validator_path, "add-regression-fixture-or-test"),
            (regression_path, "add-or-update-validator-rule"),
            (validator_path + ".backup", "add-or-update-validator-rule"),
            ("android/accessibility-tests/OtherBinding.py", "add-or-update-validator-rule"),
            ("android/accessibility-tests/NativeNodesFlowAccessibilityTest.kt",
             "add-regression-fixture-or-test"),
        ):
            with self.subTest(path=path, operation=operation), self.assertRaises(PortError):
                self.engine.admit("WP-503", assigned_paths, path, operation)

    def test_wp303_room_cleanup_is_exact_two_paths_modify_only_and_reversible(self):
        rule = ROOM_CLEANUP_CAPABILITY
        self.assertEqual(next(r for r in self.rules["reservation_policy"]["capabilities"]
                              if r["id"] == rule["id"]), rule)
        manifest = test_manifest("WP-303")
        self.assertEqual(len(rule["path_rules"]), 2)
        for path in rule["path_rules"]:
            self.assertEqual(self.engine.admit(
                "WP-303", manifest.wp("WP-303")["write_paths"], path, "modify",
            ).capability_id, rule["id"])
            for wp in ("WP-003", "WP-208", "WP-501", "WP-502"):
                with self.subTest(wp=wp, path=path), self.assertRaises(PortError):
                    self.engine.admit(wp, [], path, "modify")
            for operation in ("create", "delete", "update-build-or-manifest", "unknown"):
                with self.subTest(operation=operation), self.assertRaises(PortError):
                    self.engine.admit("WP-303", [], path, operation)
            for sibling in (path + ".backup", path.rsplit("/", 1)[0],
                            path.rsplit("/", 1)[0] + "/Other.kt",
                            path.replace("/src/test/", "/src/androidTest/")):
                if sibling == path:
                    continue
                with self.subTest(path=sibling), self.assertRaises(PortError):
                    self.engine.admit("WP-303", [], sibling, "modify")
            with self.assertRaises(PortError):
                CapabilityEngine(room_cleanup_predecessor(self.rules)).admit("WP-303", [], path, "modify")
        original = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "a5dd0fc6c521d09d59d28ca75169e4bdbd56a6cd:docs/android/automation-policy.json",
        ], encoding="utf-8"))
        actual = json.loads((REPO / "docs/android/automation-policy.json").read_text(encoding="utf-8"))
        self.assertEqual(room_cleanup_predecessor(actual), original)
        for field, value in (("work_packages", ["WP-303", "WP-501"]),
                             ("operations", ["modify", "delete"]),
                             ("path_rules", ["android/core/data/**"])):
            altered = copy.deepcopy(self.rules)
            selected = next(r for r in altered["reservation_policy"]["capabilities"] if r["id"] == rule["id"])
            selected[field] = value
            with self.subTest(field=field), self.assertRaises(PortError):
                room_cleanup_predecessor(altered)

    def test_receive_prefetch_exact_four_paths_only_assigned_wp_and_named_operation(self):
        rule = RECEIVE_PREFETCH_ADMISSION_CAPABILITY
        operation = "repair-receive-prefetch-admission"
        self.assertEqual(rule["work_packages"], ["WP-501"])
        self.assertEqual(rule["operations"], [operation])
        timeline = "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/timeline/"
        app = "android/app/src/main/kotlin/com/meshcoreone/android/app/container/"
        self.assertEqual(rule["path_rules"], [
            timeline + "TimelineContracts.kt", timeline + "ChatViewModel.kt",
            app + "AppChatsFeature.kt", app + "AppChatContent.kt",
        ])
        actual = next(r for r in self.rules["reservation_policy"]["capabilities"] if r["id"] == rule["id"])
        self.assertEqual(actual, rule)
        manifest = test_manifest("WP-501")
        for path in rule["path_rules"]:
            admitted = self.engine.admit("WP-501", manifest.wp("WP-501")["write_paths"], path, operation)
            self.assertEqual((admitted.capability_id, admitted.path, admitted.operation), (rule["id"], path, operation))
            for wp in ("WP-003", "WP-502", "WP-503", "WP-307", "WP-407", "WP-218"):
                with self.subTest(wp=wp, path=path), self.assertRaises(PortError):
                    self.engine.admit(wp, manifest.wp(wp)["write_paths"], path, operation)
            for wrong in ("modify", "create", "delete", "repair-channel-open-flood-scope",
                          "prime-initial-messages", "unknown"):
                if wrong == "repair-channel-open-flood-scope" and path != app + "AppChatContent.kt":
                    self.assertEqual(self.engine.admit("WP-501", [], path, wrong).capability_id,
                                     "channel-open-flood-scope")
                    continue
                with self.subTest(path=path, operation=wrong), self.assertRaises(PortError):
                    self.engine.admit("WP-501", manifest.wp("WP-501")["write_paths"], path, wrong)
            for sibling in (path + ".backup", path.rsplit("/", 1)[0],
                            path.rsplit("/", 1)[0] + "/Other.kt",
                            path.replace("/src/main/", "/src/test/"),
                            path.replace("/src/main/", "/src/androidTest/")):
                with self.subTest(path=sibling), self.assertRaises(PortError):
                    self.engine.admit("WP-501", [], sibling, operation)
        for path in (
            app + "AppContainer.kt", app + "AppChatMapPreviews.kt",
            timeline + "ChatTimelineState.kt",
            "android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/content/InlineImagePrefetcher.kt",
            "android/feature/chats/src/test/kotlin/com/meshcoreone/android/feature/chats/timeline/ChatViewModelTest.kt",
            "android/app/src/test/kotlin/com/meshcoreone/android/app/container/ChatPrewarmTest.kt",
            "android/app/build.gradle.kts", "MC1Tests/Views/Chats/ChatViewModelAdmissionTests.swift",
        ):
            with self.subTest(path=path), self.assertRaises(PortError):
                self.engine.admit("WP-501", manifest.wp("WP-501")["write_paths"], path, operation)
        trusted = CapabilityEngine(receive_prefetch_predecessor(self.rules))
        for path in rule["path_rules"]:
            with self.assertRaises(PortError):
                trusted.admit("WP-501", [], path, operation)

    def test_receive_prefetch_preserves_predicates_primary_attribution_and_semantic_fences(self):
        rule = RECEIVE_PREFETCH_ADMISSION_CAPABILITY
        self.assertTrue({
            "plain-text-or-master-off-immediate-admission-zero-prefetch-collaborator-calls",
            "URL-prefetch-before-incoming-admission-production-3s-bounded-cancellable-race",
            "nil-optional-seam-fast-path-not-unwired-production-permission",
            "positive-200x100-dimensions-update-matching-actual-row-aspect-2point0",
            "outgoing-immediate-visibility-with-asynchronous-prefetch",
            "original-text-identity-order-unread-send-semantics-and-zero-radio-commands",
            "trusted-main-admission-before-product-edit",
            "exclude-active-conflicting-native-or-gradle-operations-no-inactivity-inferred-from-retained-receipt",
            "old-retained-guest-receipt-history-untouched-no-restore-release-or-cleanup-prerequisite",
        }.issubset(rule["invariants"]))
        self.assertIn("injected 200ms timeout admits in under1s against 5s cancellable probe", " ".join(rule["validation"]))
        self.assertIn("https://media.giphy.com/media/abc123/giphy.gif", " ".join(rule["validation"]))
        manifest = test_manifest("WP-501")
        for path, owner in (
            ("MC1/Views/Chats/ViewModel/ChatViewModel+Prefetch.swift", "WP-307"),
            ("MC1Tests/Views/Chats/ChatViewModelAdmissionTests.swift", "WP-307"),
            ("MC1/Services/InlineImagePrefetcher.swift", "WP-218"),
        ):
            self.assertEqual(next(e for e in manifest.data["inventory"] if e["path"] == path)["primary_owner"], owner)
        origin = {"wp": "WP-307", "session": "primary", "evidence": "frozen-prefetch-and-nine-originals"}
        producer = {"wp": "WP-501", "session": "existing-parity", "evidence": "future-mounted-App-cache"}
        for path in rule["path_rules"]:
            transfer = self.engine.transfer_intent(
                origin, producer, path, rule["id"], rule["operations"][0],
            )
            self.assertEqual(transfer["origin"], origin)
            self.assertEqual(transfer["producer"], producer)
            intent = {"owner": origin["wp"], "semantic_key": "receive-admission",
                      "value_digest": "a" * 64, "evidence": origin["evidence"]}
            with self.assertRaisesRegex(PortError, "Unresolved semantic ownership collision"):
                self.engine.reconcile_intents(BASE, BASE, path, rule["id"], [
                    intent, {**intent, "owner": "WP-501", "value_digest": "b" * 64},
                ])
            with self.assertRaisesRegex(PortError, "Current base changed"):
                self.engine.reconcile_intents(BASE, "9" * 40, path, rule["id"], [intent])
            for wrong_rule in (ROOM_CAPABILITY, "rooms-message-actions", "channel-open-flood-scope"):
                if wrong_rule == "channel-open-flood-scope" and path in CHANNEL_OPEN_FLOOD_SCOPE_CAPABILITY["path_rules"]:
                    continue
                with self.subTest(rule=wrong_rule, path=path), self.assertRaises(PortError):
                    wrong_operation = next(
                        item for item in self.rules["reservation_policy"]["capabilities"]
                        if item["id"] == wrong_rule
                    )["operations"][0]
                    self.engine.transfer_intent(
                        origin, producer, path, wrong_rule, wrong_operation,
                    )

    def test_receive_prefetch_source_proposal_does_not_recover_retained_guest_or_weaken_active_conflicts(self):
        self.ledger.acquire_hard_lock(
            "native-execution", "unknown-retained-fixture-owner", {"state": "unverified-not-idle"}, NOW,
        )
        with self.ledger.transaction() as connection:
            before = [tuple(row) for row in connection.execute("SELECT rowid,* FROM hard_locks")]
        rule = RECEIVE_PREFETCH_ADMISSION_CAPABILITY
        self.assertNotIn("no-main-policy-landing-during-held-native-or-gradle-operation", rule["invariants"])
        self.assertTrue(all("after verified native cleanup" not in text for text in rule["validation"]))
        for path in rule["path_rules"]:
            self.assertEqual(self.engine.admit("WP-501", [], path, rule["operations"][0]).capability_id, rule["id"])
        with self.ledger.transaction() as connection:
            self.assertEqual([tuple(row) for row in connection.execute("SELECT rowid,* FROM hard_locks")], before)
        with self.assertRaisesRegex(PortError, "held|conflict"):
            self.ledger.acquire_hard_lock("gradle-execution", "foreign-fixture-owner", {}, NOW + 1)
        with self.ledger.transaction() as connection:
            self.assertEqual([tuple(row) for row in connection.execute("SELECT rowid,* FROM hard_locks")], before)

    def test_parity_support_exact_tuples_do_not_authorize_wrong_owner_path_operation_or_primary_tests(self):
        manifest = test_manifest("WP-501")
        for rule in (REPEAT_ROW_FALLBACK_CAPABILITY, CHANNEL_OPEN_FLOOD_SCOPE_CAPABILITY):
            wp = rule["work_packages"][0]
            operation = rule["operations"][0]
            actual = next(r for r in self.rules["reservation_policy"]["capabilities"] if r["id"] == rule["id"])
            self.assertEqual(actual, rule)
            for path in rule["path_rules"]:
                admission = self.engine.admit(wp, manifest.wp(wp)["write_paths"], path, operation)
                self.assertEqual(admission.capability_id, rule["id"])
                self.assertEqual((admission.path, admission.operation), (path, operation))
                self.assertFalse(any(CapabilityEngine._assigned_path(p, path)
                                     for p in manifest.wp(wp)["write_paths"]))
                for wrong_wp in ("WP-003", "WP-501" if wp == "WP-502" else "WP-502", "WP-503",
                                 "WP-307", "WP-309", "WP-407"):
                    with self.subTest(wp=wrong_wp, path=path), self.assertRaises(PortError):
                        self.engine.admit(wrong_wp, manifest.wp(wrong_wp)["write_paths"], path, operation)
                for wrong_path in (
                    path + ".backup", path.rsplit("/", 1)[0],
                    path.rsplit("/", 1)[0] + "/Other.kt",
                    path.replace("/src/main/", "/src/test/"),
                    path.replace("/src/main/", "/src/androidTest/"),
                    "android/feature/chats/src/test/kotlin/com/meshcoreone/android/feature/chats/timeline/ChatViewModelTest.kt",
                    "android/feature/chats/src/test/kotlin/com/meshcoreone/android/feature/chats/actions/MessagePathModelsTest.kt",
                    "android/feature/chats/src/test/kotlin/com/meshcoreone/android/feature/chats/actions/OriginalCaseInventoryTest.kt",
                    "android/app/src/test/kotlin/com/meshcoreone/android/app/container/ChatPrewarmTest.kt",
                    "android/core/services/ChannelFloodScopeResolver.kt",
                    ROOM_RENDERER,
                ):
                    with self.subTest(path=wrong_path, operation=operation), self.assertRaises(PortError):
                        self.engine.admit(wp, manifest.wp(wp)["write_paths"], wrong_path, operation)
                for wrong_operation in ("modify", "create", "delete", "unknown", ROOM_REPAIR,
                                        ROOM_ACTIONS_OPERATION, PICKER_OPERATION):
                    with self.subTest(path=path, operation=wrong_operation), self.assertRaises(PortError):
                        self.engine.admit(wp, manifest.wp(wp)["write_paths"], path, wrong_operation)
                for rooms_rule in (ROOM_CAPABILITY, "rooms-message-actions"):
                    origin = {"wp": "WP-310", "session": "primary", "evidence": "primary-source"}
                    producer = {"wp": wp, "session": "parity", "evidence": "future-consumer"}
                    with self.subTest(rule=rooms_rule, path=path), self.assertRaises(PortError):
                        transfer_operation = next(
                            item for item in self.rules["reservation_policy"]["capabilities"]
                            if item["id"] == rooms_rule
                        )["operations"][0]
                        self.engine.transfer_intent(
                            origin, producer, path, rooms_rule, transfer_operation,
                        )

    def test_parity_support_preserves_primary_attribution_and_real_semantic_overlap_fences(self):
        manifest = test_manifest("WP-501")
        primary = {
            "MC1/Views/Chats/Components/RepeatRowView.swift": "WP-309",
            "MC1/Views/Chats/Components/FallbackMatchIndicatorView.swift": "WP-307",
            "MC1/Views/Chats/ViewModel/ChatViewModel+Channels.swift": "WP-307",
        }
        for path, owner in primary.items():
            entry = next(e for e in manifest.data["inventory"] if e["path"] == path)
            self.assertEqual(entry["primary_owner"], owner)
        for rule in (REPEAT_ROW_FALLBACK_CAPABILITY, CHANNEL_OPEN_FLOOD_SCOPE_CAPABILITY):
            path, wp = rule["path_rules"][0], rule["work_packages"][0]
            origin = {"wp": "WP-309" if wp == "WP-502" else "WP-307",
                      "session": "original-producer", "evidence": "frozen-originals"}
            producer = {"wp": wp, "session": "same-parity-owner", "evidence": "future-real-consumer"}
            result = self.engine.transfer_intent(
                origin, producer, path, rule["id"], rule["operations"][0],
            )
            self.assertEqual(result["origin"], origin)
            self.assertEqual(result["producer"], producer)
            intent = {"owner": origin["wp"], "semantic_key": "real-consumer",
                      "value_digest": "a" * 64, "evidence": origin["evidence"]}
            with self.assertRaisesRegex(PortError, "Unresolved semantic ownership collision"):
                self.engine.reconcile_intents(BASE, BASE, path, rule["id"], [
                    intent, {**intent, "owner": wp, "value_digest": "b" * 64},
                ])
            with self.assertRaisesRegex(PortError, "Current base changed"):
                self.engine.reconcile_intents(BASE, "9" * 40, path, rule["id"], [intent])
            self.assertIn("trusted-main-admission-before-product-edit", rule["invariants"])
            self.assertIn("exclude-active-conflicting-native-or-gradle-operations-no-inactivity-inferred-from-retained-receipt",
                          rule["invariants"])
            self.assertIn("old-retained-guest-receipt-history-untouched-no-restore-release-or-cleanup-prerequisite",
                          rule["invariants"])
        app = next(r for r in self.rules["reservation_policy"]["capabilities"] if r["id"] == "app-build-launcher")
        self.assertNotIn("repair-channel-open-flood-scope", app["operations"])
        self.assertIn("populate-then-syncFloodScope-before-loaded-result-and-unread-bookkeeping",
                      CHANNEL_OPEN_FLOOD_SCOPE_CAPABILITY["invariants"])
        trusted = CapabilityEngine(parity_support_predecessor(self.rules))
        for rule in (REPEAT_ROW_FALLBACK_CAPABILITY, CHANNEL_OPEN_FLOOD_SCOPE_CAPABILITY):
            for path in rule["path_rules"]:
                with self.assertRaises(PortError):
                    trusted.admit(rule["work_packages"][0], [], path, rule["operations"][0])

    def test_parity_proposal_never_requires_old_guest_recovery_or_disposes_unknown_retained_receipt(self):
        self.ledger.acquire_hard_lock(
            "native-execution", "old-private-fixture-owner",
            {"endpoint": "old-private-fixture", "state": "unverified-retained-not-idle"}, NOW,
        )
        with self.ledger.transaction() as connection:
            before = [tuple(row) for row in connection.execute("SELECT rowid,* FROM hard_locks")]
        for rule in (REPEAT_ROW_FALLBACK_CAPABILITY, CHANNEL_OPEN_FLOOD_SCOPE_CAPABILITY):
            self.assertNotIn("no-main-policy-landing-during-held-native-or-gradle-operation", rule["invariants"])
            self.assertTrue(all("after verified native cleanup" not in text for text in rule["validation"]))
            for path in rule["path_rules"]:
                admission = self.engine.admit(rule["work_packages"][0], [], path, rule["operations"][0])
                self.assertEqual(admission.capability_id, rule["id"])
        # Hypothetical source admission is not installation, resource acquisition or inactivity proof.
        with self.ledger.transaction() as connection:
            self.assertEqual([tuple(row) for row in connection.execute("SELECT rowid,* FROM hard_locks")], before)
        with self.assertRaisesRegex(PortError, "held|conflict"):
            self.ledger.acquire_hard_lock("gradle-execution", "new-fixture-owner", {}, NOW + 1)
        with self.ledger.transaction() as connection:
            self.assertEqual([tuple(row) for row in connection.execute("SELECT rowid,* FROM hard_locks")], before)

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
        transfer = self.engine.transfer_intent(
            origin, producer, ROOM_RENDERER, ROOM_CAPABILITY, ROOM_REPAIR,
        )
        self.assertEqual(transfer["origin"], origin)
        self.assertEqual(transfer["producer"], producer)
        self.assertTrue(transfer["advisory"])
        with self.assertRaisesRegex(PortError, "distinct owner sessions"):
            self.engine.transfer_intent(origin, {**producer, "session": origin["session"]},
                                        ROOM_RENDERER, ROOM_CAPABILITY, ROOM_REPAIR)

    def test_wp_owned_transfer_resolves_only_the_same_assigned_wps_manifest_paths(self):
        manifest = test_manifest("WP-308")
        assigned_paths = manifest.wp("WP-308")["write_paths"]
        path = assigned_paths[0]
        origin = {"wp": "WP-308", "session": "previous-owner", "evidence": "prior-review"}
        producer = {"wp": "WP-308", "session": "current-owner", "evidence": "new-assignment"}

        transfer = self.engine.transfer_intent(
            origin, producer, path, "wp-owned", "modify",
            assigned_paths=assigned_paths,
        )
        self.assertEqual(transfer["path"], path)
        self.assertEqual(transfer["capability"], "wp-owned")
        self.assertEqual(transfer["operation"], "modify")
        self.assertEqual(
            transfer["attributions"],
            sorted([origin, producer], key=lambda value: (value["wp"], value["session"])),
        )
        self.assertTrue(transfer["advisory"])
        self.assertFalse(transfer["file_edit_blocked"])

        with self.assertRaisesRegex(PortError, "trusted assigned-WP paths"):
            self.engine.transfer_intent(origin, producer, path, "wp-owned", "modify")
        with self.assertRaisesRegex(PortError, "same assigned WP"):
            self.engine.transfer_intent(
                origin, {**producer, "wp": "WP-307"}, path, "wp-owned", "modify",
                assigned_paths=assigned_paths,
            )
        with self.assertRaisesRegex(PortError, "does not admit transfer path"):
            self.engine.transfer_intent(
                origin, producer, "android/core/protocol/session/Other.kt",
                "wp-owned", "modify",
                assigned_paths=assigned_paths,
            )
        with self.assertRaisesRegex(PortError, "does not admit transfer operation"):
            self.engine.transfer_intent(
                origin, producer, path, "wp-owned", "unsupported-operation",
                assigned_paths=assigned_paths,
            )

    def test_transfer_intent_rejects_capability_operations_and_producer_wps_not_in_policy(self):
        rule = next(
            item for item in self.rules["reservation_policy"]["capabilities"]
            if item["id"] == "channel-open-flood-scope"
        )
        origin = {"wp": "WP-307", "session": "original-owner", "evidence": "source-review"}
        producer = {
            "wp": rule["work_packages"][0], "session": "current-owner", "evidence": "consumer-review",
        }
        path = rule["path_rules"][0]
        operation = rule["operations"][0]
        transfer = self.engine.transfer_intent(origin, producer, path, rule["id"], operation)
        self.assertEqual(transfer["operation"], operation)
        with self.assertRaisesRegex(PortError, "does not admit transfer operation"):
            self.engine.transfer_intent(origin, producer, path, rule["id"], "modify")
        with self.assertRaisesRegex(PortError, "does not admit producer WP"):
            self.engine.transfer_intent(
                origin, {**producer, "wp": "WP-503"}, path, rule["id"], operation,
            )

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

    def test_translation_affordance_invariants_and_hypothetical_versus_trusted_engine_inputs(self):
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
            "android/app/src/unapproved/kotlin/Fixture.kt",
            "secrets/Fixture.kt",
        ):
            with self.subTest(path=path):
                self.assertFalse(capability_support_admitted(manifest, "WP-313", path))

    def test_related_cross_owner_tests_have_traceability_admission(self):
        manifest = test_manifest("WP-208")
        for path in (
            "android/core/data/src/test/kotlin/RoomTest.kt",
            "android/feature/chats/src/androidTest/kotlin/ConsumerTest.kt",
            "android/feature/chats/src/main/kotlin/Consumer.kt",
        ):
            with self.subTest(path=path):
                self.assertTrue(capability_support_admitted(manifest, "WP-208", path))
        for path in (
            "android/core/data/src/test/resources/RoomTest.kt",
            "android/core/data/src/main/resources/Consumer.kt",
            "android/build-logic/src/test/kotlin/PolicyTest.kt",
        ):
            with self.subTest(path=path):
                self.assertFalse(capability_support_admitted(manifest, "WP-208", path))

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
            "android/app/build.gradle.kts", "app-build-launcher", "update-build-or-manifest",
        )
        self.assertFalse(result["file_edit_blocked"])
        self.assertEqual([owner["wp"] for owner in result["attributions"]], ["WP-218", "WP-302"])

    def test_validator2_handoff_retains_both_producers_evidence(self):
        result = self.engine.transfer_intent(
            {"wp": "WP-218", "session": "content", "evidence": "validator-origin.json"},
            {"wp": "WP-003", "session": "controller", "evidence": "validator-producer.json"},
            "tools/android-port/tests/test_cli.py", "traceability-validation",
            "add-regression-fixture-or-test",
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
