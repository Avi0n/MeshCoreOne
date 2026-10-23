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
DISPOSABLE_CAPABILITY = {
    "id": "disposable-native-guest",
    "work_packages": ["WP-503"],
    "path_rules": ["tools/android-port/controller/disposable.py"],
    "operations": ["execute-private-disposable-avd"],
    "invariants": [
        "fresh-exclusive-private-data-not-a-disposable-label", "canonical-repository-ledger-and-exact-owner-revision",
        "legacy-native-row-and-history-unchanged", "atomic-host-gradle-runtime-exclusion",
        "native-Windows-Job-or-Linux-pidfd-worker-and-private-adb-endpoint", "owned-live-emulator-transport-before-selected-guest-command",
        "bounded-owned-disposal-unknown-cleanup-retains-ownership", "no-old-guest-snapshot-wipe-or-force-release",
        "trusted-main-admission-and-reviewed-runtime-before-live-use",
    ],
    "validation": [
        "disposable lifecycle and legacy preservation regressions", "packaging exact source-task-certificate invariants",
        "source-safe declared controller verification", "separate actual native readiness and execution evidence",
    ],
}


def disposable_predecessor(policy):
    """Reverse only the exact disposable resource/capability pair."""
    result = parity_support_predecessor(policy)
    if not isinstance(result, dict):
        raise PortError("Disposable amendment requires a policy object")
    reservation = result.get("reservation_policy")
    if reservation is None and "reservation_policy" not in result:
        return result
    if not isinstance(reservation, dict):
        raise PortError("Malformed disposable reservation policy")
    resources, rules = reservation.get("hard_lock_resources"), reservation.get("capabilities")
    if not isinstance(resources, list) or not isinstance(rules, list):
        raise PortError("Disposable amendment requires the canonical resource/capability catalog")
    selected = [r for r in rules if isinstance(r, dict) and r.get("id") == DISPOSABLE_CAPABILITY["id"]]
    if not selected and "disposable-native-execution" not in resources:
        return result
    if (
        selected != [DISPOSABLE_CAPABILITY] or rules[0] != DISPOSABLE_CAPABILITY
        or resources[-1] != "disposable-native-execution" or resources.count("disposable-native-execution") != 1
    ):
        raise PortError("Disposable contract changed outside its exact resource/capability amendment")
    rules.pop(0)
    resources.pop()
    return result


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


REPEAT_ROW_FALLBACK_CAPABILITY = {
    "id": "repeat-row-fallback-affordance",
    "work_packages": ["WP-502"],
    "path_rules": [
        "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/actions/MessageActionsUi.kt",
    ],
    "operations": ["repair-repeat-row-fallback-affordance"],
    "invariants": [
        "issue-117-comment-6324787817-frozen-RepeatRow-and-FallbackMatchIndicator-consumers",
        "pinned-source-db14559b39d32322b06477c6ae676112f583db50",
        "WP-309-primary-provenance-and-original-tests-unchanged",
        "existing-WP-502-owner-same-reservation",
        "real-private-RepeatRow-fallback-only-tappable-explanation-and-independent-accessibility-stop",
        "localized-label-hint-title-explanation-theme-48dp-and-dismissal",
        "exact-unresolved-resolution-order-identity-signal-hop-text-and-other-actions-unchanged",
        "no-resolver-primary-test-resource-App-State-core-or-service-widening",
        "no-substitute-consumer-or-fake-renderer",
        "existing-Room-avatar-mention-translation-rules-unchanged",
        "semantic-collisions-serialize-or-transfer-current-producer",
        "trusted-main-admission-before-product-edit",
        "exclude-active-conflicting-native-or-gradle-operations-no-inactivity-inferred-from-retained-receipt",
        "old-retained-guest-receipt-history-untouched-no-restore-release-or-cleanup-prerequisite",
    ],
    "validation": [
        "future mounted real MessageActionsRoute RepeatRow fallback indicator: tap and accessible action open localized title/explanation; dismissal and independent accessibility stop",
        "exact and unresolved rows do not expose fallback control; retained identity/signal/hops/actions and original resolver cases unchanged",
        "owned WP-502 tests/evidence exercise real consumer, not substitute renderer or primary-test rewrite",
        "affected manifest-declared verification and trusted ownership/traceability validators",
        "exact-head/current-base protected review; exclude active conflicting operations without requiring old retained guest cleanup",
    ],
}
CHANNEL_OPEN_FLOOD_SCOPE_CAPABILITY = {
    "id": "channel-open-flood-scope",
    "work_packages": ["WP-501"],
    "path_rules": [
        "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/timeline/TimelineContracts.kt",
        "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/timeline/ChatViewModel.kt",
        "android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatsFeature.kt",
    ],
    "operations": ["repair-channel-open-flood-scope"],
    "invariants": [
        "issue-115-comment-6324879936-frozen-ChatViewModel-Channels-real-open-chain",
        "pinned-source-db14559b39d32322b06477c6ae676112f583db50",
        "WP-307-and-WP-407-App-primary-provenance-and-original-tests-unchanged",
        "existing-WP-501-owner-same-reservation",
        "real-mounted-open-through-timeline-contract-and-App-to-conversation-radio-current-session",
        "channel-preference-device-default-firmware-capability-and-existing-resolver-preserved",
        "populate-then-syncFloodScope-before-loaded-result-and-unread-bookkeeping",
        "success-only-dedup-scoped-to-real-radio-session-and-desired-state",
        "failed-or-cancelled-send-not-cached-superseding-cancellation-benign-other-errors-explicit",
        "AllRegions-v12plus-unscoped-3601-older-firmware-Scope-Disabled",
        "no-prefetch-radio-side-effects-or-unrelated-DM-refresh-send-order-change",
        "no-resolver-primary-test-core-service-build-or-substitute-consumer-widening",
        "existing-App-support-and-all-product-rules-unchanged",
        "semantic-collisions-serialize-or-transfer-current-producer",
        "trusted-main-admission-before-product-edit",
        "exclude-active-conflicting-native-or-gradle-operations-no-inactivity-inferred-from-retained-receipt",
        "old-retained-guest-receipt-history-untouched-no-restore-release-or-cleanup-prerequisite",
    ],
    "validation": [
        "future mounted channel-open real ChatViewModel TimelineContracts AppChatsFeature consumer emits actual session command with matching radio/generation",
        "frozen resolver six originals/nine calls retained; AllRegions Germany v12plus emits 36 01 and older firmware Scope Disabled",
        "source ordering including failed population, success-only dedup, failed/cancelled retry, superseding load and changed radio/session/device default",
        "explicit diagnostics/cancellation and prefetch zero radio commands; owned WP-501 tests/evidence not resolver copies or primary-test rewrites",
        "affected manifest-declared verification and trusted ownership/traceability validators; exact-head/current-base protected review excluding active conflicts, not requiring old retained guest cleanup",
    ],
}


RECEIVE_PREFETCH_ADMISSION_CAPABILITY = {
    "id": "receive-prefetch-admission",
    "work_packages": ["WP-501"],
    "path_rules": [
        "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/timeline/TimelineContracts.kt",
        "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/timeline/ChatViewModel.kt",
        "android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatsFeature.kt",
        "android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatContent.kt",
    ],
    "operations": ["repair-receive-prefetch-admission"],
    "invariants": [
        "pinned-source-db14559b39d32322b06477c6ae676112f583db50",
        "WP-307-WP-407-WP-218-primary-provenance-and-original-accounting-unchanged",
        "existing-WP-501-owner-same-reservation",
        "real-mounted-App-feature-process-cache-receive-admission-no-substitute-consumer",
        "plain-text-or-master-off-immediate-admission-zero-prefetch-collaborator-calls",
        "URL-prefetch-before-incoming-admission-production-3s-bounded-cancellable-race",
        "nil-optional-seam-fast-path-not-unwired-production-permission",
        "g-abc123-expands-to-https-media-giphy-com-media-abc123-giphy-gif",
        "positive-200x100-dimensions-update-matching-actual-row-aspect-2point0",
        "outgoing-immediate-visibility-with-asynchronous-prefetch",
        "HTML-image-card-reentry-not-stranded-and-actual-late-content-row-refresh-or-reviewed-native-equivalent",
        "master-DM-channel-privacy-process-store-cache-independent-worker-lifetime-preserved",
        "cancellation-no-stale-conversation-or-radio-admission",
        "original-text-identity-order-unread-send-semantics-and-zero-radio-commands",
        "no-core-service-primary-test-build-unrelated-App-or-primer-authority",
        "no-broad-catches-fabricated-network-Room-device-claims-or-primary-accounting-inflation",
        "all-incumbent-including-fallback-and-channel-open-rules-unchanged",
        "semantic-collisions-serialize-or-transfer-current-producer",
        "trusted-main-admission-before-product-edit",
        "exclude-active-conflicting-native-or-gradle-operations-no-inactivity-inferred-from-retained-receipt",
        "old-retained-guest-receipt-history-untouched-no-restore-release-or-cleanup-prerequisite",
    ],
    "validation": [
        "future owned WP-501 tests mount real App feature cache chain and preserve all nine frozen ChatViewModelAdmissionTests cases/helpers/stubs without primary rewrites",
        "plain/master-off zero collaborator calls; URL prefetch precedes incoming row; injected 200ms timeout admits in under1s against 5s cancellable probe while production remains3s",
        "g:abc123 probes https://media.giphy.com/media/abc123/giphy.gif; positive200x100 dimensions produce2.0 in matching actual row; nil seam not unwired production",
        "outgoing immediate plus asynchronous prefetch; HTML-serving image card reentry and actual late-content refresh; master/DM/channel privacy and independent process-cache lifetime",
        "cancelled or stale conversation/radio cannot admit; original text/identity/order/unread/send and zero radio commands; no fake renderer cloned evaluator fabricated network Room or device evidence",
        "affected manifest-declared verification and trusted ownership/traceability validators; exact-head/current-base protected review excluding active conflicts without old guest recovery prerequisite",
    ],
}


ROOM_CLEANUP_CAPABILITY = {
    "id": "wp303-room-cleanup-companion",
    "work_packages": ["WP-303"],
    "path_rules": [
        "android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/repository/RoomPersistenceStore.kt",
        "android/core/data/src/test/kotlin/com/meshcoreone/android/core/data/repository/RoomSourceTest.kt",
    ],
    "operations": ["modify"],
    "invariants": [
        "WP-202-primary-provenance-and-original-cases-unchanged",
        "WP-303-AppContainer-cleanup-only-through-core-data-owned-Room-companion",
        "no-App-main-Room-dependency-or-build-graph-widening",
        "real-Room-close-and-process-store-lifetime-no-fake-success",
        "preserve-original-errors-cancellation-persistence-and-connection-lifetimes",
        "no-unrelated-core-data-schema-migration-query-or-product-scope",
        "semantic-collisions-serialize-or-transfer-current-producer",
        "immutable-stored-session-branch-worktree-authorization-no-impersonation",
        "trusted-main-admission-before-consumption",
        "retained-native-history-unchanged-no-runtime-install-or-lock-release",
    ],
    "validation": [
        "real Room cleanup companion regression in RoomSourceTest plus affected AppContainer cleanup assertions",
        "affected declared module verification and original-case accounting; source tests do not claim Room execution",
        "exact two-path WP-303 modify admission and wrong-WP-path-operation rejection",
        "exact-head-current-base protected review and genuine owner identity reconciliation",
    ],
}


BUBBLE_CONSUMER_CAPABILITY = {
    "id": "wp501-bubble-consumer-support",
    "work_packages": ["WP-501"],
    "path_rules": ["android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/timeline/ui/ChatTimeline.kt"],
    "operations": ["repair-original-message-bubble-accessibility-consumer"],
    "invariants": [
        "pinned-source-db14559b39d32322b06477c6ae676112f583db50",
        "WP-307-WP-213-primary-provenance-and-original-cases-unchanged",
        "real-mounted-App-ChatTimeline-consumer-existing-builder-sender-resolution-and-preferences-no-fabricated-defaults",
        "resolved-sender-colon-fallback-possible-match-and-unverified-disclosure-original-text-outgoing-status-incoming-hop-path-region",
        "once-only-labels-existing-retry-link-media-actions-and-virtualized-message-identity-preserved",
        "no-message-translation-or-unrelated-UI-service-build-authority",
        "same-owner-advisory-scope-evolution-semantic-collisions-serialize-or-transfer-current-producer",
        "trusted-main-landing-before-product-consumption-no-policy-landing-during-actual-native-or-gradle-operation",
        "old473-native66bd-runtime-hooks-hard-locks-and-history-unchanged",
    ],
    "validation": [
        "existing capability selector admits only WP-501 exact ChatTimeline path and named operation; rejects wrong WP path and operation",
        "future owned WP-501 mounted-consumer tests preserve all frozen UnifiedMessageBubbleA11yLabelTests families plus unverified disclosure and once-only action semantics",
        "affected declared module verification and source ownership accounting; policy tests are not native accessibility acceptance",
        "exact-head current-base source review and active native/Gradle serialization before landing",
    ],
}


def bubble_consumer_predecessor(policy):
    if not isinstance(policy, dict):
        raise PortError("Bubble consumer amendment requires a policy object")
    result = copy.deepcopy(policy)
    reservation = result.get("reservation_policy")
    if reservation is None:
        return result
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("Bubble consumer amendment requires the canonical capability catalog")
    selected = [i for i, rule in enumerate(rules)
                if isinstance(rule, dict) and rule.get("id") == BUBBLE_CONSUMER_CAPABILITY["id"]]
    if not selected:
        return result
    index = selected[0]
    if (
        len(selected) != 1 or rules[index] != BUBBLE_CONSUMER_CAPABILITY
        or index == 0 or not isinstance(rules[index - 1], dict)
        or rules[index - 1].get("id") != "dependency-resolution-metadata"
        or index + 1 >= len(rules) or not isinstance(rules[index + 1], dict)
        or rules[index + 1].get("id") != "app-build-launcher"
    ):
        raise PortError("Bubble consumer capability changed outside the exact single-path amendment")
    rules.pop(index)
    return result


def room_cleanup_predecessor(policy):
    if not isinstance(policy, dict):
        raise PortError("Room cleanup amendment requires a policy object")
    result = bubble_consumer_predecessor(policy)
    reservation = result.get("reservation_policy")
    if reservation is None:
        return result
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("Room cleanup amendment requires the canonical capability catalog")
    selected = [i for i, rule in enumerate(rules)
                if isinstance(rule, dict) and rule.get("id") == ROOM_CLEANUP_CAPABILITY["id"]]
    if not selected:
        return result
    if (
        len(selected) != 1 or rules[selected[0]] != ROOM_CLEANUP_CAPABILITY
        or selected[0] == 0 or not isinstance(rules[selected[0] - 1], dict)
        or rules[selected[0] - 1].get("id") != "app-build-launcher"
        or selected[0] + 1 >= len(rules) or not isinstance(rules[selected[0] + 1], dict)
        or rules[selected[0] + 1].get("id") != "traceability-validation"
    ):
        raise PortError("Room cleanup capability changed outside the exact two-path amendment")
    rules.pop(selected[0])
    return result


def receive_prefetch_predecessor(policy):
    """Remove only the exact appended receive-admission rule, restoring the whole b677 policy object."""
    if not isinstance(policy, dict):
        raise PortError("Receive prefetch amendment requires a policy object")
    result = room_cleanup_predecessor(policy)
    if "reservation_policy" not in result:
        return result
    reservation = result.get("reservation_policy")
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("Receive prefetch amendment requires the canonical capability catalog")
    selected = [r for r in rules if isinstance(r, dict)
                and r.get("id") == RECEIVE_PREFETCH_ADMISSION_CAPABILITY["id"]]
    if not selected:
        return result
    if (
        selected != [RECEIVE_PREFETCH_ADMISSION_CAPABILITY]
        or rules[-1] != RECEIVE_PREFETCH_ADMISSION_CAPABILITY
        or len(rules) < 2 or rules[-2] != CHANNEL_OPEN_FLOOD_SCOPE_CAPABILITY
    ):
        raise PortError("Receive prefetch capability changed outside the exact single-rule amendment")
    rules.pop()
    return result


def parity_support_predecessor(policy):
    """Reverse only the exact two-rule parity-support proposal appended after 6b."""
    if not isinstance(policy, dict):
        raise PortError("Parity support amendment requires a policy object")
    result = receive_prefetch_predecessor(policy)
    if "reservation_policy" not in result:
        return result
    reservation = result.get("reservation_policy")
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("Parity support amendment requires the canonical capability catalog")
    expected = [REPEAT_ROW_FALLBACK_CAPABILITY, CHANNEL_OPEN_FLOOD_SCOPE_CAPABILITY]
    selected = [rule for rule in rules if isinstance(rule, dict)
                and rule.get("id") in {item["id"] for item in expected}]
    if not selected:
        return result
    if (
        selected != expected or rules[-2:] != expected or len(rules) < 3
        or not isinstance(rules[-3], dict) or rules[-3].get("id") != "merge-integration"
    ):
        raise PortError("Parity support capabilities changed outside the exact two-rule amendment")
    del rules[-2:]
    return result


MESSAGE_ACTIONS_TRANSLATION_REMOVAL_CAPABILITY = {
    "id": "message-actions-translation-removal",
    "work_packages": ["WP-502"],
    "path_rules": [
        "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/actions/MessageActionsUi.kt",
    ],
    "operations": ["remove-active-message-translation-affordance"],
    "invariants": [
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
    ],
    "validation": [
        "future mounted real public MessageActionsRoute: no reachable Translate action in English and Chinese, not merely hidden or renamed text",
        "independent retained Reply/Copy/SendAgain/SendDM/React/Delete and block/mute/path/details render and dispatch according to original availability",
        "real localized labels/theme/48dp/dismissal/state/consumer side effects and original message text unchanged; no reachable not-registered translation error or fake renderer",
        "affected manifest-declared software verification and trusted ownership/traceability validators",
        "exact-head/current-base protected merge review after verified native cleanup",
    ],
}


def message_actions_translation_predecessor(policy):
    """Reverse only the exact active-affordance support rule, not removed product scope."""
    if not isinstance(policy, dict):
        raise PortError("Message actions translation amendment requires a policy object")
    result = copy.deepcopy(policy)
    if "reservation_policy" not in result:
        return result
    reservation = result.get("reservation_policy")
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("Message actions translation amendment requires the canonical capability catalog")
    selected = [index for index, rule in enumerate(rules)
                if isinstance(rule, dict) and rule.get("id") == MESSAGE_ACTIONS_TRANSLATION_REMOVAL_CAPABILITY["id"]]
    if not selected:
        return result
    if (
        len(selected) != 1 or rules[selected[0]] != MESSAGE_ACTIONS_TRANSLATION_REMOVAL_CAPABILITY
        or selected[0] == 0 or rules[selected[0] - 1] != MENTION_PICKER_CAPABILITY
        or selected[0] + 1 >= len(rules)
        or not isinstance(rules[selected[0] + 1], dict)
        or rules[selected[0] + 1].get("id") != "merge-integration"
    ):
        raise PortError("Message actions translation capability changed outside the exact authorized amendment")
    rules.pop(selected[0])
    return result


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
        rooms_message_actions_predecessor(mention_picker_predecessor(
            message_actions_translation_predecessor(disposable_predecessor(policy))))))
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
