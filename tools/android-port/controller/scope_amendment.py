"""AndroidOnly: WP-000 Exact reversible user-approved message-translation removal."""

import copy
from pathlib import Path

from .errors import PortError
from .schema import digest, load_json

REPO = Path(__file__).resolve().parents[3]
AMENDMENT_PATH = "docs/android/scope-amendments/translation-removal.json"
AMENDMENT_SHA256 = "32b5f19e1a04da77e64168fbd101cac93c4453a1eb4fbb329eb75822db633fb1"
PLAN_SHA256 = "e5c838d2566d0466175e2be4036d50e44be548319c9fcd8b83bccd4fc03930cd"
REVIEW_POLICY = {
    "code_review_authority": "trusted-read-only-review",
    "authorized_coordinators": ["cbattlegear"],
    "human_acceptance_work_packages": ["WP-001", "WP-505", "WP-506"],
    "human_acceptance_paths": ["android/release/"],
    "human_acceptance_patterns": [
        "android/**/signing*", "android/**/keystore*", "**/*license*", "**/*LICENSE*", "**/*legal*",
    ],
}
DEBUG_PACKAGING_CAPABILITY = {
    "id": "debug-test-packaging",
    "work_packages": ["WP-003", "WP-503"],
    "path_rules": ["tools/android-port/local/debug_packaging.py", "tools/android-port/tests/test_debug_packaging.py"],
    "operations": ["implement-debug-packaging-adapter", "build-debug-test-apks-with-existing-local-identity"],
    "invariants": [
        "WP-503-execution-only", "exact-clean-source-and-fixtures", "existing-native-and-exclusive-gradle-lanes",
        "existing-local-default-debug-identity-only", "public-app-test-installed-certificate-match",
        "no-private-key-read-export-copy-or-change", "no-retained-apk-resign",
        "no-install-uninstall-wipe-or-release-task",
    ],
    "validation": [
        "debug-packaging regression tests", "actual source-build-output hashes",
        "public certificate verification before any separately guarded install",
    ],
}
ROOMS_AVATAR_CAPABILITY = {
    "id": "rooms-incoming-avatar-repair",
    "work_packages": ["WP-502"],
    "path_rules": [
        "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/rooms/RoomConversationScreen.kt",
    ],
    "operations": ["repair-room-incoming-avatar-bookends"],
    "invariants": [
        "issue-253-renderer-only", "pinned-source-db14559b39d32322b06477c6ae676112f583db50",
        "WP-310-primary-provenance-unchanged", "existing-WP-502-owner-same-reservation",
        "incoming-gutter-and-showAvatar-bookends-no-self-avatar", "reuse-shared-native-identity-and-theme",
        "once-only-spoken-message-label", "no-auth-service-state-ordering-or-activation-change",
        "semantic-collisions-serialize-or-transfer-current-producer",
        "trusted-main-admission-before-product-edit",
        "no-main-policy-landing-during-held-native-or-gradle-operation",
    ],
    "validation": [
        "mounted Rooms renderer regression including same-prefix burst, retained-row reconfiguration and self rows",
        "once-only accessibility label and shared identity/theme assertions",
        "affected manifest-declared software verification", "trusted ownership and traceability validators",
        "exact-head/current-base protected merge review after verified native cleanup",
    ],
}
ROOMS_MESSAGE_ACTIONS_CAPABILITY = {
    "id": "rooms-message-actions",
    "work_packages": ["WP-502"],
    "path_rules": [
        "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/rooms/" + name
        for name in (
            "RoomConversationScreen.kt", "RoomConversationStateHolder.kt", "RoomContracts.kt",
            "RoomMessageAction.kt", "RoomMessageActionAvailability.kt", "RoomMessageActionsSheet.kt",
            "RoomSendDMSheet.kt",
        )
    ],
    "operations": ["implement-room-message-actions"],
    "invariants": [
        "pinned-source-db14559b39d32322b06477c6ae676112f583db50",
        "WP-310-primary-provenance-unchanged", "existing-WP-502-owner-same-reservation",
        "original-copy-reply-mention-sendDM-sendAgain-availability-and-metadata",
        "presentation-time-availability-stable-until-dismissal",
        "real-clipboard-preference-composer-focus-navigation-and-room-post-effects",
        "sendAgain-post-original-text-without-clearing-existing-draft",
        "radio-scoped-fresh-contacts-excludeBlocked-and-room-DTO-not-DM-conversion",
        "source-dismissal-delay-before-focus-or-sendDM-presentation",
        "once-only-accessibility-label-and-long-press-link-suppression",
        "reuse-shared-native-identity-theme-and-localized-actions", "translation-removed-no-provider-or-action",
        "no-unrelated-auth-service-ordering-or-activation-change", "avatar-only-capability-unchanged",
        "semantic-collisions-serialize-or-transfer-current-producer",
        "trusted-main-admission-before-product-edit",
        "no-main-policy-landing-during-held-native-or-gradle-operation",
    ],
    "validation": [
        "mounted Room actions availability, stable presentation, incoming/outgoing metadata and retained-row regressions",
        "real Copy, Reply/Mention preference/composer/focus, SendDM contact/navigation and SendAgain room-post consumer assertions",
        "dismissal timing, once-only accessibility, link suppression and translation-absence assertions",
        "affected manifest-declared software verification and trusted ownership/traceability validators",
        "exact-head/current-base protected merge review after verified native cleanup",
    ],
}


MENTION_PICKER_CAPABILITY = {
    "id": "shared-mention-picker-repair",
    "work_packages": ["WP-502"],
    "path_rules": [
        "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/composer/ui/MentionSuggestions.kt",
        "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/composer/ui/ChatConversationScreen.kt",
        "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/rooms/RoomConversationScreen.kt",
    ],
    "operations": ["repair-shared-mention-picker"],
    "invariants": [
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
    ],
    "validation": [
        "mounted Room action through holder/dispatcher/route/shared picker: real LAST_ADVERT not recency, localized type, full spaced key, location marker and Tap button 48dp assertions",
        "all three real call sites including App DM/channel forward fixed clock with system display zone and actual optional formatted distance",
        "App actual authorized cached phone/same-radio node/nil selection: denied invalid cross-radio absent inputs, no opening fix/prompt, independent zero-distance and Swift-derived metric/US/UK approximate road vectors with localized away wrapper",
        "over20 compact/200percent/CJK/RTL ordered identities scroll to final DTO once through NavigationCoordinator contact detail; independently reachable Done/Back/outside zero navigation and unchanged Room/draft/effects",
        "self/unknown/focus containment and byte/behavior-unchanged editor suggestions; all35 incumbent methods/helpers/Fixture and frozen multiple-Alice defaults/test identities retained",
        "affected manifest-declared software verification and trusted ownership/traceability validators",
        "exact-head/current-base protected merge review after verified native cleanup",
    ],
}


def mention_picker_predecessor(policy):
    """Remove only the exact independent picker rule, retaining the Room action preimage."""
    result = copy.deepcopy(policy)
    if "reservation_policy" not in result:
        return result
    reservation = result.get("reservation_policy")
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("Mention picker amendment requires the canonical capability catalog")
    selected = [index for index, rule in enumerate(rules)
                if isinstance(rule, dict) and rule.get("id") == MENTION_PICKER_CAPABILITY["id"]]
    if not selected:
        return result
    if (
        len(selected) != 1 or rules[selected[0]] != MENTION_PICKER_CAPABILITY
        or selected[0] == 0 or rules[selected[0] - 1] != ROOMS_MESSAGE_ACTIONS_CAPABILITY
        or selected[0] + 1 >= len(rules)
        or not isinstance(rules[selected[0] + 1], dict)
        or rules[selected[0] + 1].get("id") != "merge-integration"
    ):
        raise PortError("Mention picker capability changed outside the exact authorized amendment")
    rules.pop(selected[0])
    return result


def rooms_message_actions_predecessor(policy):
    """Project the complete exact action-support delta, never historical result identities."""
    result = copy.deepcopy(policy)
    if "reservation_policy" not in result:
        return result
    reservation = result.get("reservation_policy")
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("Rooms actions amendment requires the canonical capability catalog")
    selected = [index for index, rule in enumerate(rules)
                if isinstance(rule, dict) and rule.get("id") == ROOMS_MESSAGE_ACTIONS_CAPABILITY["id"]]
    if not selected:
        return result
    if (
        len(selected) != 1 or rules[selected[0]] != ROOMS_MESSAGE_ACTIONS_CAPABILITY
        or selected[0] == 0 or rules[selected[0] - 1] != ROOMS_AVATAR_CAPABILITY
        or selected[0] + 1 >= len(rules)
        or not isinstance(rules[selected[0] + 1], dict)
        or rules[selected[0] + 1].get("id") != "merge-integration"
    ):
        raise PortError("Rooms actions capability changed outside the exact authorized amendment")
    rules.pop(selected[0])
    return result


def rooms_avatar_predecessor(policy):
    """Reverse only the exact Rooms support rule; never refresh historical acceptance."""
    result = copy.deepcopy(policy)
    if "reservation_policy" not in result:
        return result
    reservation = result.get("reservation_policy")
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("Rooms support amendment requires the canonical capability catalog")
    selected = [index for index, rule in enumerate(rules)
                if isinstance(rule, dict) and rule.get("id") == ROOMS_AVATAR_CAPABILITY["id"]]
    if not selected:
        return result
    if (
        len(selected) != 1 or rules[selected[0]] != ROOMS_AVATAR_CAPABILITY
        or selected[0] == 0 or rules[selected[0] - 1] != DEBUG_PACKAGING_CAPABILITY
        or selected[0] + 1 >= len(rules)
        or not isinstance(rules[selected[0] + 1], dict)
        or rules[selected[0] + 1].get("id") != "merge-integration"
    ):
        raise PortError("Rooms support capability changed outside the exact authorized amendment")
    rules.pop(selected[0])
    return result


def debug_packaging_predecessor(policy):
    """Reverse only the exact admitted debug-support delta, preserving all historical receipts."""
    result = copy.deepcopy(policy)
    if "reservation_policy" not in result:
        return result
    reservation = result.get("reservation_policy")
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("Debug support amendment requires the canonical capability catalog")
    selected = [index for index, rule in enumerate(rules)
                if isinstance(rule, dict) and rule.get("id") == "debug-test-packaging"]
    if not selected:
        return result
    if (
        len(selected) != 1 or rules[selected[0]] != DEBUG_PACKAGING_CAPABILITY
        or selected[0] + 1 >= len(rules) or rules[selected[0] + 1].get("id") != "merge-integration"
    ):
        raise PortError("Debug support capability changed outside the exact authorized amendment")
    rules.pop(selected[0])
    return result


def review_policy_predecessor(policy):
    """Reverse exact authorized process deltas; historical policy receipts stay immutable."""
    result = debug_packaging_predecessor(rooms_avatar_predecessor(
        rooms_message_actions_predecessor(mention_picker_predecessor(policy))))
    if "review_policy" not in result:
        return result
    if result.pop("review_policy") != REVIEW_POLICY:
        raise PortError("Review authority changed outside the authorized policy amendment")
    gates = result.get("merge_gate_policy")
    if not isinstance(gates, dict) or (
        gates.pop("protected_paths_require_trusted_code_review_for_main_merge", None) is not True
        or gates.pop("human_hardware_legal_signing_acceptance_required", None) is not True
        or "protected_paths_require_human_approval_for_main_merge" in gates
    ):
        raise PortError("Review authority merge-gate amendment drift")
    gates["protected_paths_require_human_approval_for_main_merge"] = True
    return result


def amendment(repo=REPO):
    record = load_json(repo / AMENDMENT_PATH, 512 * 1024)
    if digest(record) != AMENDMENT_SHA256:
        raise PortError("Protected translation scope amendment identity drift")
    return record


def is_translation_scope(data):
    return isinstance(data, dict) and isinstance(data.get("reference"), dict) \
        and data["reference"].get("approved_plan_sha256") == PLAN_SHA256


def transform(data, *, reverse=False, repo=REPO):
    from .software_verification import apply, project

    if reverse:
        data = project(data, repo)
    record = amendment(repo)
    source, target = ("current", "previous") if reverse else ("previous", "current")
    matches = [stage for stage in record["revisions"].values() if stage[source] == digest(data)]
    if len(matches) != 1:
        raise PortError("Translation scope requires the complete exact catalog preimage")
    result = copy.deepcopy(data)
    if result["reference"] != record["reference"][source]:
        raise PortError("Translation scope reference metadata drift")
    result["reference"] = copy.deepcopy(record["reference"][target])
    for section, key in (("agents", "name"), ("work_packages", "id"), ("inventory", "path")):
        for change in record[section]:
            before, after = change[source], change[target]
            found = [item for item in result[section] if item[key] == change[key]]
            if found != ([] if before is None else [before]):
                raise PortError(f"Translation scope exact delta mismatch: {change[key]}")
            if before is not None:
                index = result[section].index(found[0])
                if after is None:
                    result[section].pop(index)
                else:
                    result[section][index] = copy.deepcopy(after)
            elif after is not None:
                result[section].insert(change["index"], copy.deepcopy(after))
    if digest(result) != matches[0][target]:
        raise PortError("Translation scope changed the complete catalog outside its exact delta")
    return result if reverse else apply(result, repo)


def apply_translation_scope(data, repo=REPO):
    return transform(data, repo=repo)


def project_translation_scope(data, repo=REPO):
    return transform(data, reverse=True, repo=repo)


def project_translation_exclusions(exclusions, repo=REPO):
    record = amendment(repo)
    if digest(exclusions) != record["current_exclusions_sha256"]:
        raise PortError("Translation exclusions require the complete exact current catalog")
    result = copy.deepcopy(exclusions)
    for entry in record["added_exclusions"]:
        result["entries"].remove(entry)
    if digest(result) != record["previous_exclusions_sha256"]:
        raise PortError("Translation exclusions changed the historical catalog outside the exact delta")
    return result


def current_revisions(manifest, policy):
    from .gates import policy_revision
    from .model import Manifest
    from .software_verification import project

    project_translation_scope(manifest.data, manifest.repo)
    actual = policy_revision(manifest, policy)
    predecessor = review_policy_predecessor(policy)
    historical = Manifest(project(manifest.data, manifest.repo), manifest.exclusions, manifest.repo)
    if policy_revision(historical, predecessor) != amendment(manifest.repo)["current_policy_revision"]:
        raise PortError("Translation scope current catalog/policy cannot be cross-bound")
    return {"manifest_sha256": manifest.sha256, "policy_revision": actual}
