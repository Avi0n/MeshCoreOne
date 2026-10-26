"""AndroidOnly: WP-000 Exact consumer-support admission, denial and reversible projection."""

import copy
import json
import subprocess
import unittest

from fixtures import REPO, policy, test_manifest
from controller.capabilities import CapabilityEngine
from controller.errors import PortError
from controller.scope_amendment import (
    CONSUMER_SUPPORT_CAPABILITIES, consumer_support_predecessor,
    wp503_accessibility_source_binding_predecessor,
)


BASE = "263d30332940f09082709b9d38ee8877dfb9fedd"
CONSUMER_SUPPORT_MERGE = "f2f481d2e7c2a9029808943529c98c98ef879299"
ROOT = "android/core/runtime/src/"
PACKAGE = "kotlin/com/meshcoreone/android/core/runtime/"


class ConsumerSupportCapabilityTests(unittest.TestCase):
    def setUp(self):
        self.rules = policy()
        self.engine = CapabilityEngine(self.rules)
        self.manifest = test_manifest("WP-000")

    def test_exact_purposes_paths_and_operations(self):
        expected = [
            ("wp405-uri-region-normalization", "WP-405", "repair-uri-region-normalization", [
                "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/list/MeshCoreLinkParser.kt",
            ]),
            ("wp201-active-connection-method", "WP-201", "persist-active-connection-method", [
                ROOT + "main/" + PACKAGE + "ConnectionManager.kt",
            ]),
            ("wp201-connection-method-regression", "WP-201", "add-connection-method-persistence-regression", [
                ROOT + "test/" + PACKAGE + "DeviceConnectionMethodPersistenceTest.kt",
            ]),
            ("wp214-active-link-channel-sync-input", "WP-214", "add-active-link-channel-sync-input", [
                ROOT + "main/" + PACKAGE + "RuntimePorts.kt",
                ROOT + "main/" + PACKAGE + "ConnectionManager.kt",
                ROOT + "test/" + PACKAGE + "RuntimeOwnershipTest.kt",
            ]),
            ("wp211-regional-label-localization", "WP-211", "repair-regional-label-localization", [
                "android/app/src/main/kotlin/com/meshcoreone/android/app/container/onboarding/OnboardingRegionPresets.kt",
                "android/app/src/main/kotlin/com/meshcoreone/android/app/container/onboarding/AppOnboarding.kt",
                "android/app/src/main/kotlin/com/meshcoreone/android/app/container/AndroidAppContainerFactory.kt",
                "android/app/src/test/kotlin/com/meshcoreone/android/app/container/onboarding/OnboardingRegionPresetAdapterTest.kt",
                "android/ui-tests/src/test/kotlin/com/meshcoreone/android/uitests/OnboardingRegionPresetComposeTest.kt",
            ]),
            ("wp003-scoped-local-verification", "WP-003", "wire-scoped-local-verification", [
                "tools/android-port/local/check.py",
                "tools/android-port/local/fast.py",
                "tools/android-port/local/run.sh",
                "tools/android-port/local/hook_test.py",
            ]),
        ]
        self.assertEqual(len(CONSUMER_SUPPORT_CAPABILITIES), len(expected))
        for rule, (identity, wp, operation, paths) in zip(CONSUMER_SUPPORT_CAPABILITIES, expected):
            with self.subTest(capability=identity):
                self.assertEqual((rule["id"], rule["work_packages"], rule["operations"], rule["path_rules"]),
                                 (identity, [wp], [operation], paths))
                self.assertEqual(next(r for r in self.rules["reservation_policy"]["capabilities"]
                                      if r["id"] == identity), rule)
                for path in paths:
                    admission = self.engine.admit(wp, self.manifest.wp(wp)["write_paths"], path, operation)
                    self.assertEqual((admission.capability_id, admission.path, admission.operation),
                                     (identity, path, operation))
                    self.assertEqual(admission.invariants, tuple(rule["invariants"]))
                    self.assertEqual(admission.validation, tuple(rule["validation"]))

    def test_other_work_packages_and_cross_purpose_operations_fail_closed(self):
        for rule in CONSUMER_SUPPORT_CAPABILITIES:
            for path in rule["path_rules"]:
                for wp in self.manifest.work_packages:
                    if wp in rule["work_packages"]:
                        continue
                    with self.subTest(rule=rule["id"], path=path, wp=wp), self.assertRaises(PortError):
                        self.engine.admit(wp, self.manifest.wp(wp)["write_paths"], path, rule["operations"][0])
                for other in CONSUMER_SUPPORT_CAPABILITIES:
                    if other["operations"] == rule["operations"]:
                        continue
                    wp = rule["work_packages"][0]
                    with self.subTest(rule=rule["id"], operation=other["operations"]), self.assertRaises(PortError):
                        self.engine.admit(wp, self.manifest.wp(wp)["write_paths"], path, other["operations"][0])

    def test_generic_operations_and_nearby_paths_fail_closed(self):
        for rule in CONSUMER_SUPPORT_CAPABILITIES:
            wp, operation = rule["work_packages"][0], rule["operations"][0]
            for path in rule["path_rules"]:
                for wrong in ("modify", "create", "delete", "test", "generate", "unknown"):
                    with self.subTest(rule=rule["id"], path=path, operation=wrong), self.assertRaises(PortError):
                        self.engine.admit(wp, self.manifest.wp(wp)["write_paths"], path, wrong)
                for wrong in (
                    path + ".backup", path.rsplit("/", 1)[0],
                    path.rsplit("/", 1)[0] + "/Other.kt",
                    (path.replace("/src/main/", "/src/androidTest/").replace("/src/test/", "/src/androidTest/")
                     if "/src/" in path else path + ".test"),
                ):
                    with self.subTest(rule=rule["id"], path=wrong), self.assertRaises(PortError):
                        self.engine.admit(wp, self.manifest.wp(wp)["write_paths"], wrong, operation)

    def test_complete_predecessor_equals_trusted_base_without_foreign_changes(self):
        original = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show", BASE + ":docs/android/automation-policy.json",
        ], encoding="utf-8"))
        actual = json.loads((REPO / "docs/android/automation-policy.json").read_text(encoding="utf-8"))
        without_wp503 = wp503_accessibility_source_binding_predecessor(actual)
        merged_support = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            CONSUMER_SUPPORT_MERGE + ":docs/android/automation-policy.json",
        ], encoding="utf-8"))
        self.assertEqual(without_wp503, merged_support)
        self.assertEqual(consumer_support_predecessor(without_wp503), original)
        self.assertEqual(consumer_support_predecessor(original), original)
        for rule in CONSUMER_SUPPORT_CAPABILITIES:
            for path in rule["path_rules"]:
                with self.subTest(rule=rule["id"], path=path), self.assertRaises(PortError):
                    CapabilityEngine(original).admit(rule["work_packages"][0], [], path, rule["operations"][0])

    def test_projection_rejects_partial_duplicate_reordered_and_mutated_blocks(self):
        for rule in CONSUMER_SUPPORT_CAPABILITIES:
            for key, value in (
                ("work_packages", rule["work_packages"] + ["WP-000"]),
                ("operations", rule["operations"] + ["modify"]),
                ("path_rules", ["android/**"]),
                ("invariants", rule["invariants"][:-1]),
                ("validation", []),
            ):
                altered = copy.deepcopy(self.rules)
                target = next(r for r in altered["reservation_policy"]["capabilities"] if r["id"] == rule["id"])
                target[key] = value
                with self.subTest(rule=rule["id"], key=key), self.assertRaises(PortError):
                    consumer_support_predecessor(altered)
        identities = {r["id"] for r in CONSUMER_SUPPORT_CAPABILITIES}
        for mode in ("partial", "duplicate", "reorder", "move"):
            altered = copy.deepcopy(self.rules)
            rules = altered["reservation_policy"]["capabilities"]
            start = next(i for i, r in enumerate(rules) if r["id"] in identities)
            if mode == "partial":
                rules.pop(start)
            elif mode == "duplicate":
                rules.append(copy.deepcopy(rules[start]))
            elif mode == "reorder":
                rules[start], rules[start + 1] = rules[start + 1], rules[start]
            else:
                block = rules[start:start + len(identities)]
                del rules[start:start + len(identities)]
                rules.extend(block)
            with self.subTest(mode=mode), self.assertRaises(PortError):
                consumer_support_predecessor(altered)
        for malformed in (None, [], {"reservation_policy": None}, {"reservation_policy": {}}):
            with self.subTest(malformed=malformed), self.assertRaises(PortError):
                consumer_support_predecessor(malformed)

    def test_existing_owned_tests_and_helpers_remain_separate_authority(self):
        owned = "android/app/src/test/kotlin/com/meshcoreone/android/app/deeplinks/ChannelRegionConsumerTest.kt"
        self.assertEqual(self.engine.admit("WP-405", self.manifest.wp("WP-405")["write_paths"],
                                          owned, "create").capability_id, "wp-owned")
        excluded = [
            "android/core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/bytes/Bytes.kt",
            ROOT + "test/" + PACKAGE + "RuntimeTestSupport.kt",
            "android/app/src/main/kotlin/com/meshcoreone/android/app/l10n/AppLanguages.kt",
            "android/core/l10n/src/main/kotlin/com/meshcoreone/android/core/l10n/generated/RegionalSubdivisionNames.kt",
            "android/accessibility-tests/language_binding.py",
            "tools/android-port/local/reserve.py",
            "tools/android-port/local/advisory.py",
            "tools/android-port/local/test_reserve.py",
        ]
        for rule in CONSUMER_SUPPORT_CAPABILITIES:
            for path in excluded:
                with self.subTest(rule=rule["id"], path=path), self.assertRaises(PortError):
                    self.engine.admit(rule["work_packages"][0], [], path, rule["operations"][0])

    def test_purpose_fences_remain_independent(self):
        text = {r["id"]: " ".join(r["invariants"] + r["validation"]) for r in CONSUMER_SUPPORT_CAPABILITIES}
        for identity, required in (
            ("wp405-uri-region-normalization", ("30", "grapheme", "nil-empty", "secrets", "QR", "29ASCII")),
            ("wp201-active-connection-method", ("establish-only", "radioId", "stale-BLE", "preferences", "WP201-first")),
            ("wp201-connection-method-regression", ("three-pinned", "RuntimeFixture", "saveDevice")),
            ("wp214-active-link-channel-sync-input", ("required-transportType", "no-default", "link-type", "READY-only", "WP201")),
            ("wp211-regional-label-localization", ("French German", "API31-32", "per-call", "RegionalSubdivisionNames", "original-text")),
            ("wp003-scoped-local-verification", ("namespace", "hard-lock", "supervision", "candidate-diff",
                                                "trusted-base-head-tree", "nonzero", "full-fallback", "no-arbitrary-Gradle")),
        ):
            for fence in required:
                with self.subTest(identity=identity, fence=fence):
                    self.assertIn(fence, text[identity])
