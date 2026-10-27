"""AndroidOnly: WP-000 Full-catalog scope removal, not message-translation acceptance."""

import copy
import json
import subprocess
import unittest
from unittest.mock import patch

from fixtures import REPO, base_manifest
from bootstrap import build_inventory
from controller.errors import PortError
from controller.gates import policy_revision, validate_cases
from controller.model import HUMAN_GATES, REFERENCE_SHA, validate_manifest
from controller.render import render
from controller.runtime_inputs import required_inputs, verify_tree_entries
from controller.schema import digest, load_json
from controller.scope_amendment import (
    AMENDMENT_PATH, amendment, apply_translation_scope, project_translation_scope,
    project_translation_exclusions,
    review_policy_predecessor,
    debug_packaging_predecessor, DEBUG_PACKAGING_CAPABILITY,
    rooms_avatar_predecessor, ROOMS_AVATAR_CAPABILITY,
    rooms_message_actions_predecessor, ROOMS_MESSAGE_ACTIONS_CAPABILITY,
    mention_picker_predecessor, MENTION_PICKER_CAPABILITY,
    message_actions_translation_predecessor, MESSAGE_ACTIONS_TRANSLATION_REMOVAL_CAPABILITY,
    disposable_predecessor,
    parity_support_predecessor, REPEAT_ROW_FALLBACK_CAPABILITY, CHANNEL_OPEN_FLOOD_SCOPE_CAPABILITY,
    receive_prefetch_predecessor, RECEIVE_PREFETCH_ADMISSION_CAPABILITY,
    room_cleanup_predecessor,
    bubble_consumer_predecessor, BUBBLE_CONSUMER_CAPABILITY,
    room_failed_event_predecessor, ROOM_FAILED_EVENT_CAPABILITY,
    wp503_accessibility_source_binding_predecessor,
    WP503_ACCESSIBILITY_SOURCE_BINDING_CAPABILITIES,
    consumer_support_predecessor,
    host_scoped_jvm_predecessor,
    backup_post_commit_predecessor,
    rx_projection_regression_predecessor,
    shared_location_cache_predecessor,
    reaction_wire_classification_predecessor,
    shortcut_lifecycle_test_predecessor,
)
from controller.verification_config import (
    apply_content_scope, apply_overlay, content_scope_manifest_revision,
    content_scope_predecessor, content_scope_revisions, project_content_scope,
)
from inventory_rules import TRANSLATION_PATHS, exclusion, production_owner
from controller.model import Manifest
from controller.software_verification import project as project_software


class TranslationScopeAmendmentTests(unittest.TestCase):
    def policy_before_room_cleanup_text(self):
        text = (REPO / "docs/android/automation-policy.json").read_text(encoding="utf-8")
        start = text.index('      {\n        "id": "wp404-shortcut-lifecycle-consumer-test",')
        end = text.index('      {\n        "id": "wp216-reaction-wire-classification",', start)
        previous = text[:start] + text[end:]
        self.assertEqual(json.loads(previous), shortcut_lifecycle_test_predecessor(json.loads(text)))
        self.assertEqual(json.loads(previous), json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "61fc048e1780173e2603013a8b6d0233d0d63c3b:docs/android/automation-policy.json",
        ])))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=previous.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "61fc048e1780173e2603013a8b6d0233d0d63c3b:docs/android/automation-policy.json",
        ]).strip())
        text = previous
        start = text.index('      {\n        "id": "wp216-reaction-wire-classification",')
        end = text.index('      {\n        "id": "wp404-shared-location-producer-cache",', start)
        previous = text[:start] + text[end:]
        self.assertEqual(json.loads(previous), reaction_wire_classification_predecessor(json.loads(text)))
        self.assertEqual(json.loads(previous), json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "de20d1a6f3ff97068076204504ca7c854044bb77:docs/android/automation-policy.json",
        ])))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=previous.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "de20d1a6f3ff97068076204504ca7c854044bb77:docs/android/automation-policy.json",
        ]).strip())
        text = previous
        start = text.index('      {\n        "id": "wp404-shared-location-producer-cache",')
        end = text.index('      {\n        "id": "wp201-rx-projection-source-regressions",', start)
        previous = text[:start] + text[end:]
        self.assertEqual(json.loads(previous), shared_location_cache_predecessor(json.loads(text)))
        self.assertEqual(json.loads(previous), json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "d2b649421ae7f31b2ef94b7dee20894abe658d22:docs/android/automation-policy.json",
        ])))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=previous.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "d2b649421ae7f31b2ef94b7dee20894abe658d22:docs/android/automation-policy.json",
        ]).strip())
        text = previous
        start = text.index('      {\n        "id": "wp201-rx-projection-source-regressions",')
        end = text.index('      {\n        "id": "wp203-backup-post-commit-delivery",', start)
        previous = text[:start] + text[end:]
        self.assertEqual(json.loads(previous), rx_projection_regression_predecessor(json.loads(text)))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=previous.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "3498dead5e5cad13d71936d95ddd6860b7217c8c:docs/android/automation-policy.json",
        ]).strip())
        text = previous
        start = text.index('      {\n        "id": "wp203-backup-post-commit-delivery",')
        end = text.index('      {\n        "id": "persistence-schemas",', start)
        previous = text[:start] + text[end:]
        self.assertEqual(json.loads(previous), backup_post_commit_predecessor(json.loads(text)))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=previous.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "610f606ba:docs/android/automation-policy.json",
        ]).strip())
        text = previous
        start = text.index('      {\n        "id": "wp003-host-scoped-jvm-parallelism",')
        end = text.index('      {\n        "id": "disposable-native-guest",', start)
        previous = text[:start] + text[end:]
        self.assertEqual(json.loads(previous), host_scoped_jvm_predecessor(json.loads(text)))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=previous.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "a8c624bcb095f81a30e5fee671099fa519f608b7:docs/android/automation-policy.json",
        ]).strip())
        text = previous
        start = text.index(',\n      {\n        "id": "wp503-accessibility-source-binding",')
        end = text.index("\n    ]", start)
        previous = text[:start] + text[end:]
        self.assertEqual(json.loads(previous), wp503_accessibility_source_binding_predecessor(json.loads(text)))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=previous.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "f2f481d2e7c2a9029808943529c98c98ef879299:docs/android/automation-policy.json",
        ]).strip())
        text = previous
        start = text.index('      {\n        "id": "wp405-uri-region-normalization",')
        end = text.index('      {\n        "id": "wp-owned",', start)
        previous = text[:start] + text[end:]
        self.assertEqual(json.loads(previous), consumer_support_predecessor(json.loads(text)))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=previous.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "263d30332940f09082709b9d38ee8877dfb9fedd:docs/android/automation-policy.json",
        ]).strip())
        text = previous
        start = text.index('      {\n        "id": "wp502-room-failed-event-consumer",')
        end = text.index('      {\n        "id": "dependency-resolution-metadata",', start)
        previous = text[:start] + text[end:]
        self.assertEqual(json.loads(previous), room_failed_event_predecessor(json.loads(text)))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=previous.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "6a5492b6814db737a8caab898fb8f0b72e798191:docs/android/automation-policy.json",
        ]).strip())
        text = previous
        start = text.index('      {\n        "id": "wp501-bubble-consumer-support",')
        end = text.index('      {\n        "id": "app-build-launcher",', start)
        previous = text[:start] + text[end:]
        self.assertEqual(json.loads(previous), bubble_consumer_predecessor(json.loads(text)))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=previous.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "31557a16ca4492bbd669a7ad0358e702c5c54031:docs/android/automation-policy.json",
        ]).strip())
        text = previous
        start = text.index('      {\n        "id": "wp303-room-cleanup-companion",')
        end = text.index('      {\n        "id": "traceability-validation",', start)
        previous = text[:start] + text[end:]
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=previous.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "a5dd0fc6c521d09d59d28ca75169e4bdbd56a6cd:docs/android/automation-policy.json",
        ]).strip())
        self.assertEqual(json.loads(previous), room_cleanup_predecessor(json.loads(text)))
        return previous

    def test_bubble_consumer_inverse_rejects_widening_and_preserves_entire_current_main(self):
        current = load_json(REPO / "docs/android/automation-policy.json")
        before = copy.deepcopy(current)
        previous = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "31557a16ca4492bbd669a7ad0358e702c5c54031:docs/android/automation-policy.json",
        ]))
        self.assertEqual(bubble_consumer_predecessor(current), previous)
        self.assertEqual(current, before)
        self.assertEqual(bubble_consumer_predecessor(previous), previous)
        for mode in ("wp", "path", "operation", "invariant", "validation", "extra", "duplicate", "order"):
            changed = copy.deepcopy(current)
            rules = changed["reservation_policy"]["capabilities"]
            rule = next(r for r in rules if r["id"] == BUBBLE_CONSUMER_CAPABILITY["id"])
            if mode in ("wp", "path", "operation"):
                field, value = {
                    "wp": ("work_packages", "WP-502"), "path": ("path_rules", "android/**"),
                    "operation": ("operations", "modify"),
                }[mode]
                rule[field].append(value)
            elif mode in ("invariant", "validation"):
                rule["invariants" if mode == "invariant" else "validation"] = []
            elif mode == "extra":
                rule["extra"] = True
            elif mode == "duplicate":
                rules.append(copy.deepcopy(rule))
            else:
                rules.remove(rule)
                rules.insert(0, rule)
            with self.subTest(mode=mode), self.assertRaises(PortError):
                bubble_consumer_predecessor(changed)
        for malformed in (None, [], {"reservation_policy": {"capabilities": None}}):
            with self.subTest(malformed=malformed), self.assertRaises(PortError):
                bubble_consumer_predecessor(malformed)
        for offset in (-1, 1):
            changed = copy.deepcopy(current)
            rules = changed["reservation_policy"]["capabilities"]
            index = next(i for i, r in enumerate(rules) if r["id"] == BUBBLE_CONSUMER_CAPABILITY["id"])
            rules[index + offset] = None
            with self.subTest(neighbor=offset), self.assertRaises(PortError):
                bubble_consumer_predecessor(changed)

    def test_room_failed_event_inverse_restores_whole_main_and_rejects_rule_drift(self):
        current = load_json(REPO / "docs/android/automation-policy.json")
        before = copy.deepcopy(current)
        previous = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "6a5492b6814db737a8caab898fb8f0b72e798191:docs/android/automation-policy.json",
        ]))
        self.assertEqual(room_failed_event_predecessor(current), previous)
        self.assertEqual(current, before)
        self.assertEqual(room_failed_event_predecessor(previous), previous)
        for mode in ("wp", "path", "operation", "invariant", "validation", "extra", "duplicate", "order", "neighbor"):
            changed = copy.deepcopy(current)
            rules = changed["reservation_policy"]["capabilities"]
            rule = next(r for r in rules if r["id"] == ROOM_FAILED_EVENT_CAPABILITY["id"])
            if mode in ("wp", "path", "operation"):
                field, value = {"wp": ("work_packages", "WP-501"), "path": ("path_rules", "android/**"),
                                "operation": ("operations", "modify")}[mode]
                rule[field].append(value)
            elif mode in ("invariant", "validation"):
                rule["invariants" if mode == "invariant" else "validation"] = []
            elif mode == "extra":
                rule["extra"] = True
            elif mode == "duplicate":
                rules.append(copy.deepcopy(rule))
            elif mode == "neighbor":
                rules[rules.index(rule) - 1] = None
            else:
                rules.remove(rule)
                rules.insert(0, rule)
            with self.subTest(mode=mode), self.assertRaises(PortError):
                room_failed_event_predecessor(changed)
        for malformed in (None, [], {"reservation_policy": {"capabilities": None}}):
            with self.subTest(malformed=malformed), self.assertRaises(PortError):
                room_failed_event_predecessor(malformed)

    def test_wp503_accessibility_binding_projection_is_exact_reversible_and_drift_safe(self):
        current = host_scoped_jvm_predecessor(load_json(REPO / "docs/android/automation-policy.json"))
        before = copy.deepcopy(current)
        previous = wp503_accessibility_source_binding_predecessor(current)
        base = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "f2f481d2e7c2a9029808943529c98c98ef879299:docs/android/automation-policy.json",
        ]))
        self.assertEqual(previous, base)
        original_base = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "263d30332940f09082709b9d38ee8877dfb9fedd:docs/android/automation-policy.json",
        ]))
        self.assertEqual(consumer_support_predecessor(previous), original_base)
        self.assertEqual(current, before)
        self.assertEqual(wp503_accessibility_source_binding_predecessor(previous), previous)
        restored = copy.deepcopy(previous)
        restored["reservation_policy"]["capabilities"].extend(
            copy.deepcopy(WP503_ACCESSIBILITY_SOURCE_BINDING_CAPABILITIES)
        )
        self.assertEqual(restored, current)

        for mode in ("work-package", "path", "operation", "invariant", "validation",
                     "extra", "duplicate", "reordered", "unknown", "neighbor",
                     "overlapping-path-rule"):
            changed = copy.deepcopy(current)
            rules = changed["reservation_policy"]["capabilities"]
            rule = next(r for r in rules if r["id"] == WP503_ACCESSIBILITY_SOURCE_BINDING_CAPABILITIES[0]["id"])
            if mode == "work-package":
                rule["work_packages"].append("WP-316")
            elif mode == "path":
                rule["path_rules"].append("android/accessibility-tests/**")
            elif mode == "operation":
                rule["operations"].append("modify")
            elif mode == "invariant":
                rule["invariants"].remove("no-fake-refreshed-compatibility")
            elif mode == "validation":
                rule["validation"] = []
            elif mode == "extra":
                rule["extra"] = True
            elif mode == "duplicate":
                rules.append(copy.deepcopy(rule))
            elif mode == "reordered":
                rules[-2:] = reversed(rules[-2:])
            elif mode == "unknown":
                rule["id"] = "unregistered-wp503-source-binding"
            elif mode == "overlapping-path-rule":
                overlapping = copy.deepcopy(rule)
                overlapping["id"] = "unexpected-accessibility-overlap"
                overlapping["path_rules"] = ["android/accessibility-tests/**"]
                rules.insert(0, overlapping)
            else:
                rules[-3] = None
            with self.subTest(mode=mode), self.assertRaises(PortError):
                wp503_accessibility_source_binding_predecessor(changed)

        unrelated = copy.deepcopy(current)
        unrelated["branch_rules_proven"] = True
        with self.assertRaises(PortError):
            content_scope_revisions(base_manifest(), unrelated)

    def test_receive_prefetch_single_rule_inverse_restores_entire_b677_object_and_raw_git_blob(self):
        current = room_cleanup_predecessor(load_json(REPO / "docs/android/automation-policy.json"))
        before = copy.deepcopy(current)
        previous = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "b677fd681533fbbe461a07ddf7fcf237d61885dc:docs/android/automation-policy.json",
        ]))
        self.assertEqual(receive_prefetch_predecessor(current), previous)
        self.assertEqual(receive_prefetch_predecessor(previous), previous)
        self.assertEqual(current, before)
        restored = copy.deepcopy(previous)
        restored["reservation_policy"]["capabilities"].append(copy.deepcopy(RECEIVE_PREFETCH_ADMISSION_CAPABILITY))
        self.assertEqual(restored, current)
        text = self.policy_before_room_cleanup_text()
        start = text.index(',\n      {\n        "id": "receive-prefetch-admission",')
        end = text.index("\n    ]", start)
        reversed_text = text[:start] + text[end:]
        self.assertEqual(json.loads(reversed_text), previous)
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=reversed_text.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "b677fd681533fbbe461a07ddf7fcf237d61885dc:docs/android/automation-policy.json",
        ]).strip())
        self.assertEqual((REPO / "docs/android/port-manifest.json").read_bytes().replace(b"\r\n", b"\n"),
                         subprocess.check_output([
                             "git", "-C", str(REPO), "show",
                             "b677fd681533fbbe461a07ddf7fcf237d61885dc:docs/android/port-manifest.json",
                         ]).replace(b"\r\n", b"\n"))
        self.assertEqual(content_scope_revisions(base_manifest(), current)["policy_revision"],
                         policy_revision(base_manifest(), current))

    def test_receive_prefetch_inverse_rejects_widening_weakened_predicates_and_order_drift(self):
        current = consumer_support_predecessor(
            wp503_accessibility_source_binding_predecessor(
                load_json(REPO / "docs/android/automation-policy.json")))
        for mode in ("wp", "path", "operation", "invariant", "validation", "duplicate", "reorder", "predecessor"):
            changed = copy.deepcopy(current)
            rules = changed["reservation_policy"]["capabilities"]
            rule = rules[-1]
            if mode == "wp":
                rule["work_packages"].append("WP-502")
            elif mode == "path":
                rule["path_rules"].append("android/app/**")
            elif mode == "operation":
                rule["operations"].append("modify")
            elif mode == "invariant":
                rule["invariants"].remove("nil-optional-seam-fast-path-not-unwired-production-permission")
            elif mode == "validation":
                rule["validation"] = []
            elif mode == "duplicate":
                rules.append(copy.deepcopy(rule))
            elif mode == "reorder":
                rules.remove(rule)
                rules.insert(0, rule)
            else:
                rules[-2]["operations"].append("repair-receive-prefetch-admission")
            with self.subTest(mode=mode), self.assertRaises(PortError):
                receive_prefetch_predecessor(changed)
        for malformed in (None, [], {"reservation_policy": None}, {"reservation_policy": {"capabilities": None}}):
            with self.subTest(malformed=malformed), self.assertRaises(PortError):
                receive_prefetch_predecessor(malformed)
        changed = copy.deepcopy(current)
        changed["admin_bypass"] = True
        with self.assertRaises(PortError):
            content_scope_revisions(base_manifest(), changed)

    def test_parity_support_inverse_restores_entire_exact_6b_policy_and_manifest(self):
        current = receive_prefetch_predecessor(load_json(REPO / "docs/android/automation-policy.json"))
        before = copy.deepcopy(current)
        previous = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "6b394bbabe2c4210e37bb2c401f2bcb2e1443032:docs/android/automation-policy.json",
        ]))
        self.assertEqual(parity_support_predecessor(current), previous)
        self.assertEqual(parity_support_predecessor(previous), previous)
        self.assertEqual(current, before)
        self.assertEqual(current["reservation_policy"]["capabilities"][:-2],
                         previous["reservation_policy"]["capabilities"])
        restored = copy.deepcopy(previous)
        restored["reservation_policy"]["capabilities"].extend([
            copy.deepcopy(REPEAT_ROW_FALLBACK_CAPABILITY), copy.deepcopy(CHANNEL_OPEN_FLOOD_SCOPE_CAPABILITY),
        ])
        self.assertEqual(restored, current)
        text = self.policy_before_room_cleanup_text()
        start = text.index(',\n      {\n        "id": "repeat-row-fallback-affordance",')
        end = text.index("\n    ]", start)
        reversed_text = text[:start] + text[end:]
        self.assertEqual(json.loads(reversed_text), previous)
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=reversed_text.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "6b394bbabe2c4210e37bb2c401f2bcb2e1443032:docs/android/automation-policy.json",
        ]).strip())
        self.assertEqual(json.loads((REPO / "docs/android/port-manifest.json").read_text(encoding="utf-8")),
                         json.loads(subprocess.check_output([
                             "git", "-C", str(REPO), "show",
                             "6b394bbabe2c4210e37bb2c401f2bcb2e1443032:docs/android/port-manifest.json",
                         ])))
        self.assertEqual(content_scope_revisions(base_manifest(), current)["policy_revision"],
                         policy_revision(base_manifest(), current))
        self.assertFalse(current["paused"])
        self.assertEqual(current["dispatch_mode"], "off")
        self.assertFalse(current["activation_approved"])

    def test_parity_support_projection_rejects_widening_partial_reordering_and_unrelated_drift(self):
        current = receive_prefetch_predecessor(load_json(REPO / "docs/android/automation-policy.json"))
        for index in (-2, -1):
            for mode in ("owner", "path", "operation", "invariant", "validation", "duplicate", "missing", "order"):
                changed = copy.deepcopy(current)
                rules = changed["reservation_policy"]["capabilities"]
                rule = rules[index]
                if mode == "owner":
                    rule["work_packages"].append("WP-003")
                elif mode == "path":
                    rule["path_rules"].append("android/feature/chats/**")
                elif mode == "operation":
                    rule["operations"].append("modify")
                elif mode == "invariant":
                    rule["invariants"].remove("trusted-main-admission-before-product-edit")
                elif mode == "validation":
                    rule["validation"] = []
                elif mode == "duplicate":
                    rules.append(copy.deepcopy(rule))
                elif mode == "missing":
                    rules.remove(rule)
                else:
                    rules.remove(rule)
                    rules.insert(1, rule)
                with self.subTest(index=index, mode=mode), self.assertRaises(PortError):
                    parity_support_predecessor(changed)
        for malformed in (None, [], {"reservation_policy": None},
                          {"reservation_policy": {"capabilities": None}}):
            with self.subTest(malformed=malformed), self.assertRaises(PortError):
                parity_support_predecessor(malformed)
        changed = copy.deepcopy(current)
        changed["branch_rules_proven"] = True
        with self.assertRaises(PortError):
            content_scope_revisions(base_manifest(), changed)

    def test_debug_support_projection_preserves_the_actual_pre_capability_policy(self):
        historical = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "7e2835bad2c03dfb5a088063655f9fc4dbafd00f:docs/android/automation-policy.json",
        ]))
        self.assertNotIn("reservation_policy", historical)
        self.assertEqual(debug_packaging_predecessor(historical), historical)
        for malformed in (None, {}, {"capabilities": None}):
            with self.subTest(malformed=malformed), self.assertRaises(PortError):
                debug_packaging_predecessor({**historical, "reservation_policy": malformed})

    def test_debug_support_projection_is_exact_reversible_and_never_rebinds_history(self):
        manifest = base_manifest()
        current = message_actions_translation_predecessor(disposable_predecessor(
            load_json(REPO / "docs/android/automation-policy.json")))
        current = rooms_avatar_predecessor(rooms_message_actions_predecessor(mention_picker_predecessor(current)))
        before = copy.deepcopy(current)
        previous = debug_packaging_predecessor(current)
        self.assertEqual(current, before)
        self.assertFalse(any(rule["id"] == "debug-test-packaging"
                             for rule in previous["reservation_policy"]["capabilities"]))
        restored = copy.deepcopy(previous)
        rules = restored["reservation_policy"]["capabilities"]
        index = next(i for i, rule in enumerate(rules) if rule["id"] == "merge-integration")
        rules.insert(index, copy.deepcopy(DEBUG_PACKAGING_CAPABILITY))
        self.assertEqual(restored, current)
        self.assertEqual(content_scope_revisions(manifest, current)["policy_revision"],
                         policy_revision(manifest, current))
        historical = Manifest(project_software(manifest.data), manifest.exclusions, REPO)
        self.assertEqual(policy_revision(historical, review_policy_predecessor(current)),
                         amendment()["current_policy_revision"])
        for mode in ("owner", "path", "operation", "invariant", "validation", "duplicate", "reordered", "unrelated"):
            changed = copy.deepcopy(current)
            rules = changed["reservation_policy"]["capabilities"]
            rule = next(rule for rule in rules if rule["id"] == "debug-test-packaging")
            if mode == "owner":
                rule["work_packages"].append("WP-316")
            elif mode == "path":
                rule["path_rules"].append("android/release/**")
            elif mode == "operation":
                rule["operations"].append("resign-retained-apk")
            elif mode == "invariant":
                rule["invariants"].remove("no-private-key-read-export-copy-or-change")
            elif mode == "validation":
                rule["validation"] = []
            elif mode == "duplicate":
                rules.append(copy.deepcopy(rule))
            elif mode == "reordered":
                rules.remove(rule)
                rules.insert(0, rule)
            else:
                changed["branch_rules_proven"] = True
            with self.subTest(mode=mode), self.assertRaises(PortError):
                content_scope_revisions(manifest, changed)

    def test_rooms_support_projection_matches_real_predecessor_and_preserves_historical_binding(self):
        manifest = base_manifest()
        current = message_actions_translation_predecessor(disposable_predecessor(
            load_json(REPO / "docs/android/automation-policy.json")))
        current = rooms_message_actions_predecessor(mention_picker_predecessor(current))
        before = copy.deepcopy(current)
        previous = rooms_avatar_predecessor(current)
        historical = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "c9ed1f9b78bcc322fb1ec281d211f99852cfec3b:docs/android/automation-policy.json",
        ]))
        self.assertEqual(previous, historical)
        self.assertEqual(current, before)
        self.assertEqual(rooms_avatar_predecessor(previous), previous)
        restored = copy.deepcopy(previous)
        rules = restored["reservation_policy"]["capabilities"]
        index = next(i for i, rule in enumerate(rules) if rule["id"] == "merge-integration")
        rules.insert(index, copy.deepcopy(ROOMS_AVATAR_CAPABILITY))
        self.assertEqual(restored, current)
        self.assertNotEqual(policy_revision(manifest, current), policy_revision(manifest, previous))
        self.assertEqual(content_scope_revisions(manifest, current), {
            "manifest_sha256": manifest.sha256, "policy_revision": policy_revision(manifest, current),
        })
        self.assertEqual(review_policy_predecessor(current), review_policy_predecessor(previous))
        historical_manifest = Manifest(project_software(manifest.data), manifest.exclusions, REPO)
        self.assertEqual(policy_revision(historical_manifest, review_policy_predecessor(current)),
                         amendment()["current_policy_revision"])
        self.assertEqual(rooms_avatar_predecessor({"historical": True}), {"historical": True})

    def test_rooms_support_projection_rejects_broadened_duplicate_reordered_and_unrelated_changes(self):
        current = load_json(REPO / "docs/android/automation-policy.json")
        for mode in ("owner", "path", "operation", "invariant", "validation",
                     "duplicate", "reordered", "unknown", "unrelated"):
            changed = copy.deepcopy(current)
            rules = changed["reservation_policy"]["capabilities"]
            rule = next(rule for rule in rules if rule["id"] == ROOMS_AVATAR_CAPABILITY["id"])
            if mode == "owner":
                rule["work_packages"].append("WP-503")
            elif mode == "path":
                rule["path_rules"].append("android/feature/chats/**")
            elif mode == "operation":
                rule["operations"].append("modify")
            elif mode == "invariant":
                rule["invariants"].remove("once-only-spoken-message-label")
            elif mode == "validation":
                rule["validation"] = []
            elif mode == "duplicate":
                rules.append(copy.deepcopy(rule))
            elif mode == "reordered":
                rules.remove(rule)
                rules.insert(0, rule)
            elif mode == "unknown":
                rule["id"] = "unregistered-room-capability"
            else:
                changed["branch_rules_proven"] = True
            before = copy.deepcopy(changed)
            with self.subTest(mode=mode), self.assertRaises(PortError):
                content_scope_revisions(base_manifest(), changed)
            self.assertEqual(changed, before)
        for malformed in (None, {}, {"capabilities": None}):
            with self.subTest(malformed=malformed), self.assertRaises(PortError):
                rooms_avatar_predecessor({"reservation_policy": malformed})

    def test_rooms_actions_projection_is_exact_reversible_and_keeps_actual_and_historical_bindings(self):
        manifest = base_manifest()
        current = mention_picker_predecessor(message_actions_translation_predecessor(disposable_predecessor(
            load_json(REPO / "docs/android/automation-policy.json"))))
        before = copy.deepcopy(current)
        previous = rooms_message_actions_predecessor(current)
        historical = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c:docs/android/automation-policy.json",
        ]))
        self.assertEqual(previous, historical)
        self.assertEqual(current, before)
        self.assertEqual(rooms_message_actions_predecessor(previous), previous)
        restored = copy.deepcopy(previous)
        rules = restored["reservation_policy"]["capabilities"]
        rules.insert(next(i for i, rule in enumerate(rules) if rule["id"] == "merge-integration"),
                     copy.deepcopy(ROOMS_MESSAGE_ACTIONS_CAPABILITY))
        self.assertEqual(restored, current)
        self.assertNotEqual(policy_revision(manifest, current), policy_revision(manifest, previous))
        self.assertEqual(content_scope_revisions(manifest, current), {
            "manifest_sha256": manifest.sha256, "policy_revision": policy_revision(manifest, current),
        })
        self.assertEqual(review_policy_predecessor(current), review_policy_predecessor(previous))
        historical_manifest = Manifest(project_software(manifest.data), manifest.exclusions, REPO)
        self.assertEqual(policy_revision(historical_manifest, review_policy_predecessor(current)),
                         amendment()["current_policy_revision"])

    def test_rooms_actions_projection_rejects_catalog_drift_and_unrelated_gate_changes(self):
        current = load_json(REPO / "docs/android/automation-policy.json")
        for mode in ("owner", "path", "operation", "invariant", "validation",
                     "duplicate", "reordered", "renamed", "avatar", "unrelated", "unknown"):
            changed = copy.deepcopy(current)
            rules = changed["reservation_policy"]["capabilities"]
            rule = next(rule for rule in rules if rule["id"] == "rooms-message-actions")
            if mode == "owner":
                rule["work_packages"].append("WP-503")
            elif mode == "path":
                rule["path_rules"].append("android/feature/chats/**")
            elif mode == "operation":
                rule["operations"].append("modify")
            elif mode == "invariant":
                rule["invariants"].remove("translation-removed-no-provider-or-action")
            elif mode == "validation":
                rule["validation"] = []
            elif mode == "duplicate":
                rules.append(copy.deepcopy(rule))
            elif mode == "reordered":
                rules.remove(rule)
                rules.insert(0, rule)
            elif mode == "renamed":
                rule["id"] = "unregistered-action-capability"
            elif mode == "avatar":
                next(r for r in rules if r["id"] == "rooms-incoming-avatar-repair")["operations"].append("modify")
            elif mode == "unknown":
                rules.append({**copy.deepcopy(rule), "id": "unknown-feature-support"})
            else:
                changed["branch_rules_proven"] = True
            before = copy.deepcopy(changed)
            with self.subTest(mode=mode), self.assertRaises(PortError):
                content_scope_revisions(base_manifest(), changed)
            self.assertEqual(changed, before)
        self.assertEqual(rooms_message_actions_predecessor({"historical": True}), {"historical": True})
        for malformed in (None, {}, {"capabilities": None}):
            with self.subTest(malformed=malformed), self.assertRaises(PortError):
                rooms_message_actions_predecessor({"reservation_policy": malformed})

    def test_picker_projection_restores_exact_reviewed_candidate_then_real_trusted_policy(self):
        manifest = base_manifest()
        current = message_actions_translation_predecessor(disposable_predecessor(
            load_json(REPO / "docs/android/automation-policy.json")))
        before = copy.deepcopy(current)
        previous = mention_picker_predecessor(current)
        candidate = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "ae583338feee3b8ad266280c01261d5c8c9ecba1:docs/android/automation-policy.json",
        ]))
        deployed = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c:docs/android/automation-policy.json",
        ]))
        self.assertEqual(previous, candidate)
        self.assertEqual(rooms_message_actions_predecessor(previous), deployed)
        self.assertEqual(policy_revision(manifest, deployed),
                         "e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3")
        self.assertEqual(current, before)
        self.assertEqual(mention_picker_predecessor(previous), previous)
        restored = copy.deepcopy(previous)
        rules = restored["reservation_policy"]["capabilities"]
        rules.insert(next(i for i, rule in enumerate(rules) if rule["id"] == "merge-integration"),
                     copy.deepcopy(MENTION_PICKER_CAPABILITY))
        self.assertEqual(restored, current)
        self.assertNotEqual(policy_revision(manifest, current), policy_revision(manifest, previous))
        self.assertEqual(content_scope_revisions(manifest, current), {
            "manifest_sha256": manifest.sha256, "policy_revision": policy_revision(manifest, current),
        })
        self.assertEqual(review_policy_predecessor(current), review_policy_predecessor(candidate))
        self.assertEqual(review_policy_predecessor(current), review_policy_predecessor(deployed))

    def test_picker_projection_rejects_any_rule_drift_or_unrelated_semantic_change(self):
        current = load_json(REPO / "docs/android/automation-policy.json")
        for mode in ("owner", "path", "duplicate-path", "reordered-paths", "missing-caller",
                     "operation", "invariant", "validation", "duplicate",
                     "reordered", "unknown", "malformed-neighbor", "rooms", "gate"):
            changed = copy.deepcopy(current)
            rules = changed["reservation_policy"]["capabilities"]
            rule = next(r for r in rules if r["id"] == MENTION_PICKER_CAPABILITY["id"])
            if mode == "owner":
                rule["work_packages"].append("WP-308")
            elif mode == "path":
                rule["path_rules"].append("android/feature/chats/**")
            elif mode == "duplicate-path":
                rule["path_rules"].append(rule["path_rules"][0])
            elif mode == "reordered-paths":
                rule["path_rules"].reverse()
            elif mode == "missing-caller":
                rule["path_rules"].pop()
            elif mode == "operation":
                rule["operations"].append("modify")
            elif mode == "invariant":
                rule["invariants"].remove("no-fabricated-location-distance-or-clock-no-full-location-parity-from-nil")
            elif mode == "validation":
                rule["validation"] = []
            elif mode == "duplicate":
                rules.append(copy.deepcopy(rule))
            elif mode == "reordered":
                rules.remove(rule)
                rules.insert(0, rule)
            elif mode == "unknown":
                rule["id"] = "unknown-picker-support"
            elif mode == "malformed-neighbor":
                rules[rules.index(rule) + 1] = None
            elif mode == "rooms":
                next(r for r in rules if r["id"] == "rooms-message-actions")["operations"].append("modify")
            else:
                changed["branch_rules_proven"] = True
            before = copy.deepcopy(changed)
            with self.subTest(mode=mode), self.assertRaises(PortError):
                content_scope_revisions(base_manifest(), changed)
            self.assertEqual(changed, before)
        self.assertEqual(mention_picker_predecessor({"historical": True}), {"historical": True})
        for malformed in (None, {}, {"capabilities": None}):
            with self.subTest(malformed=malformed), self.assertRaises(PortError):
                mention_picker_predecessor({"reservation_policy": malformed})

    def test_active_translation_affordance_projection_restores_entire_reviewed_aa4_and_binding(self):
        manifest = base_manifest()
        current = disposable_predecessor(load_json(REPO / "docs/android/automation-policy.json"))
        before = copy.deepcopy(current)
        previous = message_actions_translation_predecessor(current)
        historical_bytes = subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "aa4b0b425b3ee58485191c7ede39e69ca8a1acf5:docs/android/automation-policy.json",
        ])
        self.assertEqual(previous, json.loads(historical_bytes))
        self.assertEqual(current, before)
        self.assertEqual(message_actions_translation_predecessor(previous), previous)
        restored = copy.deepcopy(previous)
        rules = restored["reservation_policy"]["capabilities"]
        rules.insert(next(i for i, rule in enumerate(rules) if rule["id"] == "merge-integration"),
                     copy.deepcopy(MESSAGE_ACTIONS_TRANSLATION_REMOVAL_CAPABILITY))
        self.assertEqual(restored, current)
        text = subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "ad40165c93d1bc21e073f91102971719b078ad02:docs/android/automation-policy.json",
        ])
        newline = b"\r\n" if b"\r\n" in text else b"\n"
        start = text.index(b'      {' + newline + b'        "id": "message-actions-translation-removal",')
        end = text.index(b'      {' + newline + b'        "id": "merge-integration",', start)
        # Compare canonical Git blobs, preserving Windows checkout newline conversion.
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=text[:start] + text[end:]).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "aa4b0b425b3ee58485191c7ede39e69ca8a1acf5:docs/android/automation-policy.json",
        ]).strip())
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "docs/android/port-manifest.json",
        ]).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "aa4b0b425b3ee58485191c7ede39e69ca8a1acf5:docs/android/port-manifest.json",
        ]).strip())
        self.assertNotEqual(policy_revision(manifest, current), policy_revision(manifest, previous))
        self.assertEqual(content_scope_revisions(manifest, current), {
            "manifest_sha256": manifest.sha256, "policy_revision": policy_revision(manifest, current),
        })
        self.assertEqual(review_policy_predecessor(current), review_policy_predecessor(previous))
        self.assertEqual(message_actions_translation_predecessor({"historical": True}), {"historical": True})

    def test_active_translation_affordance_projection_rejects_drift_without_mutating_input(self):
        current = load_json(REPO / "docs/android/automation-policy.json")
        for mode in ("owner", "path", "duplicate-path", "operation", "invariant", "validation",
                     "duplicate", "reordered", "unknown", "malformed-neighbor", "picker", "gate"):
            changed = copy.deepcopy(current)
            rules = changed["reservation_policy"]["capabilities"]
            rule = next(r for r in rules if r["id"] == MESSAGE_ACTIONS_TRANSLATION_REMOVAL_CAPABILITY["id"])
            if mode == "owner":
                rule["work_packages"].append("WP-309")
            elif mode == "path":
                rule["path_rules"].append("android/feature/chats/**")
            elif mode == "duplicate-path":
                rule["path_rules"].append(rule["path_rules"][0])
            elif mode == "operation":
                rule["operations"].append("modify")
            elif mode == "invariant":
                rule["invariants"].remove("no-fake-renderer-or-removal-of-action-section")
            elif mode == "validation":
                rule["validation"] = []
            elif mode == "duplicate":
                rules.append(copy.deepcopy(rule))
            elif mode == "reordered":
                rules.remove(rule)
                rules.insert(0, rule)
            elif mode == "unknown":
                rule["id"] = "unknown-translation-support"
            elif mode == "malformed-neighbor":
                rules[rules.index(rule) + 1] = None
            elif mode == "picker":
                next(r for r in rules if r["id"] == "shared-mention-picker-repair")["operations"].append("modify")
            else:
                changed["branch_rules_proven"] = True
            before = copy.deepcopy(changed)
            with self.subTest(mode=mode), self.assertRaises(PortError):
                content_scope_revisions(base_manifest(), changed)
            self.assertEqual(changed, before)
        for malformed in (None, [], {"reservation_policy": None}, {"reservation_policy": {}},
                          {"reservation_policy": {"capabilities": None}}):
            with self.subTest(malformed=malformed), self.assertRaises(PortError):
                message_actions_translation_predecessor(malformed)

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
            for field in ("owner", "supervised", "planned_state", "write_paths"):
                self.assertEqual(wp[field], old[identity][field])
            projected = next(w for w in project_software(current.data)["work_packages"] if w["id"] == identity)
            self.assertEqual(projected["verification"], old[identity]["verification"])
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
                                    "policy_revision": policy_revision(manifest, policy)})
        historical = Manifest(project_software(manifest.data), manifest.exclusions, REPO)
        self.assertEqual(policy_revision(historical, review_policy_predecessor(policy)),
                         amendment()["current_policy_revision"])
        self.assertEqual(content_scope_manifest_revision(manifest), manifest.sha256)
        self.assertNotEqual(revisions["manifest_sha256"], amendment()["revisions"]["final"]["previous"])
        self.assertEqual(digest(project_content_scope(manifest.data)),
                         "1dd7bc4f74f10e566b66fc8bc3822bb2bd66068053e1aa866e520326aab4dde6")
        changed = copy.deepcopy(policy)
        changed["trusted_check_app_ids"] = [999999]
        with self.assertRaises(PortError):
            content_scope_revisions(manifest, changed)

    def test_review_amendment_preserves_history_and_rejects_unrelated_policy_drift(self):
        policy = load_json(REPO / "docs/android/automation-policy.json")
        before = copy.deepcopy(policy)
        previous = review_policy_predecessor(policy)
        self.assertEqual(policy, before)
        self.assertNotIn("review_policy", previous)
        self.assertTrue(previous["merge_gate_policy"]["protected_paths_require_human_approval_for_main_merge"])
        self.assertNotEqual(policy_revision(base_manifest(), policy),
                            policy_revision(base_manifest(), previous))
        for mode in ("reviewer", "hardware", "merge", "publisher", "pin"):
            changed = copy.deepcopy(policy)
            if mode == "reviewer":
                changed["review_policy"]["authorized_coordinators"].append("unregistered")
            elif mode == "hardware":
                changed["review_policy"]["human_acceptance_work_packages"].remove("WP-505")
            elif mode == "merge":
                changed["merge_gate_policy"]["human_hardware_legal_signing_acceptance_required"] = False
            elif mode == "publisher":
                changed["trusted_gate_publisher_app_id"] = 999
            else:
                changed["read_only_paths"] = []
            with self.subTest(mode=mode), self.assertRaises(PortError):
                content_scope_revisions(base_manifest(), changed)

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
