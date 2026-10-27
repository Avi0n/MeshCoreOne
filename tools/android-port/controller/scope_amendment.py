"""AndroidOnly: WP-000 Exact reversible user-approved message-translation removal."""

import copy
from pathlib import Path

from .capabilities import CapabilityEngine
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


ROOM_FAILED_EVENT_CAPABILITY = {
    "id": "wp502-room-failed-event-consumer",
    "work_packages": ["WP-502"],
    "path_rules": [
        "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/rooms/RoomContracts.kt",
        "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/rooms/RoomConversationStateHolder.kt",
    ],
    "operations": ["repair-room-failed-event-consumer"],
    "invariants": [
        "pinned-source-db14559b39d32322b06477c6ae676112f583db50-WP310-App-primary-provenance-unchanged",
        "preserve-failed-versus-status-event-distinction-known-current-session-messages-only",
        "immediate-markFailedSendsSeen-then-conversationsChanged-independent-of-held-or-failed-later-read",
        "unchanged-first-50ms-debounce-ordering-and-stale-generation-protection",
        "ordinary-status-unknown-message-and-foreign-session-events-no-immediate-failed-effects",
        "cancellation-propagates-real-mark-failure-reaches-existing-diagnostics-collector-survives",
        "no-send-retry-transport-translation-original-credit-or-native-acceptance-change",
        "existing-WP502-after-current-PR292-lands-same-owner-scope-evolution-no-authorization-rewrite",
        "trusted-main-landing-before-consumption-semantic-overlap-serialized-no-policy-landing-during-active-native-or-Gradle",
        "old473-native66bd-lease-runtime-hooks-hard-locks-and-history-unchanged",
    ],
    "validation": [
        "existing selector exact WP502 two-path named-operation positives and wrong-WP-path-operation negatives",
        "future owned WP502 real failed-versus-status adapter and consumer tests with held and failed reads, known unknown foreign messages, first50ms ordering and stale generations",
        "future real mark failure diagnostics and collector survival plus propagated cancellation; six existing Room accessibility originals receive no replayed credit",
        "affected declared verification and primary provenance accounting; source-policy tests do not claim native Room acceptance",
    ],
}


WP503_ACCESSIBILITY_SOURCE_BINDING_CAPABILITIES = [
    {
        "id": "wp503-accessibility-source-binding",
        "work_packages": ["WP-503"],
        "path_rules": ["android/accessibility-tests/language_binding.py"],
        "operations": ["add-or-update-validator-rule"],
        "invariants": [
            "immutable-historical-producer-path-blob",
            "distinct-current-candidate-path-blob-bound-to-candidate-head",
            "producer-ancestry-and-selected-fixture-method-identities",
            "reject-historical-pin-current-pin-and-unrelated-source-drift",
            "no-fake-refreshed-compatibility",
        ],
        "validation": [
            "positive historical and candidate source-binding assertions",
            "negative mutation tests for historical pins current pins and unrelated source drift",
            "exact-candidate validator checks and independent source review before policy landing",
            "native and Gradle execution locks remain unchanged",
        ],
    },
    {
        "id": "wp503-accessibility-source-binding-regression",
        "work_packages": ["WP-503"],
        "path_rules": ["android/accessibility-tests/test_language_binding.py"],
        "operations": ["add-regression-fixture-or-test"],
        "invariants": [
            "preserve-original-fixture-and-selected-method-assertions",
            "test-historical-and-current-source-bindings-independently",
            "reject-historical-current-and-unrelated-source-drift",
        ],
        "validation": [
            "positive historical and candidate binding cases",
            "negative historical pin current pin and unrelated source mutation cases",
            "nonzero execution of the declared WP-503 validator suite",
        ],
    },
]

CONSUMER_SUPPORT_CAPABILITIES = [
    {
        "id": "wp405-uri-region-normalization",
        "work_packages": ["WP-405"],
        "path_rules": [
            "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/list/MeshCoreLinkParser.kt",
        ],
        "operations": ["repair-uri-region-normalization"],
        "invariants": [
            "pinned-source-db14559b39d32322b06477c6ae676112f583db50-primary-provenance-unchanged",
            "REGION-only-reuse-existing-utf8Prefix-graphemes-and-MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES-30",
            "trim-and-nil-empty-after-cap-preserved-no-broader-Unicode-trim-equivalence-claim",
            "contact-map-scheme-host-path-query-decoding-validation-encoding-secrets-and-failure-unchanged",
            "actual-external-chat-QR-join-and-local-region-persistence-not-parser-only-credit",
            "no-protocol-helper-test-resource-build-or-feature-subtree-expansion",
            "trusted-main-admission-before-product-edit-same-owner-advisory-evolution",
            "semantic-overlap-serialize-or-transfer-current-producer-no-native-or-Gradle-lock-change",
        ],
        "validation": [
            "exact WP405 path and named-operation admission; other-WP path and operation denial",
            "future WP405 owned App consumer tests: padded40ASCII becomes30; 29ASCII-plus-combining-grapheme stays29 through external chat and QR joins",
            "future nil-empty and explicit-secret consumers preserve actual persisted region and secret; no fabricated original-family credit",
            "affected declared module verification with nonzero discovery and trusted ownership/traceability validators",
        ],
    },
    {
        "id": "wp201-active-connection-method",
        "work_packages": ["WP-201"],
        "path_rules": [
            "android/core/runtime/src/main/kotlin/com/meshcoreone/android/core/runtime/ConnectionManager.kt",
        ],
        "operations": ["persist-active-connection-method"],
        "invariants": [
            "pinned-source-db14559b39d32322b06477c6ae676112f583db50-primary-provenance-unchanged",
            "establish-only-exhaustive-current-Bluetooth-target-deviceId-or-WiFi-host-port-method",
            "actual-session-identity-to-DeviceDTO-fromConnection-and-DevicePersisting-saveDevice-boundary",
            "preserve-WiFi-replace-stale-BLE-on-public-key-match-stable-radioId-distinct-from-endpoint",
            "one-saved-row-no-dropped-preferences-no-merger-lifecycle-service-input-or-heartbeat-rewrite",
            "WP201-first-ConnectionManager-producer-WP214-serializes-after-its-result",
            "trusted-main-admission-before-product-edit-same-owner-advisory-evolution",
            "semantic-overlap-serialize-or-transfer-current-producer-no-native-or-Gradle-lock-change",
        ],
        "validation": [
            "exact WP201 source path and named-operation admission; generic modify create delete and other-WP denial",
            "future real RuntimeFixture session-to-save tests retain fresh-BLE existing-WiFi and stale-BLE-replacement pinned families",
            "future endpoint-versus-radioId single-row preferences and existing WiFi behavior assertions",
            "affected declared runtime verification with nonzero discovery and trusted ownership/traceability validators",
        ],
    },
    {
        "id": "wp201-connection-method-regression",
        "work_packages": ["WP-201"],
        "path_rules": [
            "android/core/runtime/src/test/kotlin/com/meshcoreone/android/core/runtime/DeviceConnectionMethodPersistenceTest.kt",
        ],
        "operations": ["add-connection-method-persistence-regression"],
        "invariants": [
            "create-update-and-test-only-this-exact-regression-no-generic-runtime-test-authority",
            "real-existing-RuntimeFixture-MeshCoreSession-to-saveDevice-not-direct-DTO-only-proxy",
            "retain-three-pinned-DevicePublicKeyDeduplicationTests-families-no-assertion-weakening",
            "fresh-BLE-existing-WiFi-stale-BLE-replacement-radioId-endpoint-and-preferences-asserted",
            "no-RuntimeTestSupport-build-dependency-or-source-ownership-edit",
            "trusted-main-admission-before-product-edit-same-owner-advisory-evolution",
            "semantic-overlap-serialize-or-transfer-current-producer-no-native-or-Gradle-lock-change",
        ],
        "validation": [
            "exact WP201 test path and named-operation admission; source sibling other-WP and generic-operation denial",
            "future declared runtime test runner discovers and passes all three source families with real save-boundary assertions",
            "trusted ownership/traceability validators; policy regressions are not product acceptance",
        ],
    },
    {
        "id": "wp214-active-link-channel-sync-input",
        "work_packages": ["WP-214"],
        "path_rules": [
            "android/core/runtime/src/main/kotlin/com/meshcoreone/android/core/runtime/RuntimePorts.kt",
            "android/core/runtime/src/main/kotlin/com/meshcoreone/android/core/runtime/ConnectionManager.kt",
            "android/core/runtime/src/test/kotlin/com/meshcoreone/android/core/runtime/RuntimeOwnershipTest.kt",
        ],
        "operations": ["add-active-link-channel-sync-input"],
        "invariants": [
            "pinned-source-db14559b39d32322b06477c6ae676112f583db50-primary-provenance-unchanged",
            "RuntimeServiceInputs-required-transportType-TransportType-no-default-or-saved-endpoint-inference",
            "sole-real-ConnectionManager-establish-factory-call-forwards-actual-link-type-per-generation",
            "modify-existing-exact-source-and-RuntimeOwnershipTest-only-no-test-helper-or-runtime-subtree-expansion",
            "preserve-WP201-DeviceDTO-endpoint-semantics-platform-detection-and-unknown-WiFi-fallback",
            "preserve-ownership-teardown-cancellation-cooldowns-READY-only-heartbeat-and-saved-endpoints",
            "WP214-waits-for-WP201-ConnectionManager-producer-result-before-overlapping-product-edit",
            "trusted-main-admission-before-product-edit-same-owner-advisory-evolution",
            "semantic-overlap-serialize-or-transfer-current-producer-no-native-or-Gradle-lock-change",
        ],
        "validation": [
            "exact WP214 three-path named-operation admission; other-WP sibling path generic-operation and cross-purpose denial",
            "future RuntimeOwnershipTest factory-input assertions observe actual BLE WiFi and replacement-generation RuntimeLink.type",
            "future real App channel-sync consumer retains typed-error and pipeline behavior; no source-family credit from support policy",
            "affected declared module verification with nonzero discovery and trusted ownership/traceability validators",
        ],
    },
    {
        "id": "wp211-regional-label-localization",
        "work_packages": ["WP-211"],
        "path_rules": [
            "android/app/src/main/kotlin/com/meshcoreone/android/app/container/onboarding/OnboardingRegionPresets.kt",
            "android/app/src/main/kotlin/com/meshcoreone/android/app/container/onboarding/AppOnboarding.kt",
            "android/app/src/main/kotlin/com/meshcoreone/android/app/container/AndroidAppContainerFactory.kt",
            "android/app/src/test/kotlin/com/meshcoreone/android/app/container/onboarding/OnboardingRegionPresetAdapterTest.kt",
            "android/ui-tests/src/test/kotlin/com/meshcoreone/android/uitests/OnboardingRegionPresetComposeTest.kt",
        ],
        "operations": ["repair-regional-label-localization"],
        "invariants": [
            "pinned-source-db14559b39d32322b06477c6ae676112f583db50-primary-provenance-unchanged",
            "modify-five-existing-files-only-actual-app-locale-resources-to-mounted-ServicesRegionCatalog",
            "factory-OnboardingPlatform-regional-resource-provider-and-import-only-other-factory-blocks-unchanged",
            "current-app-localized-resources-per-call-including-API31-32-AppLanguages-attach-no-frozen-Application-default-locale",
            "reuse-generated-RegionalSubdivisionNames-and-core-RegionalAreas-injection-no-shared-resource-edit",
            "localized-subdivision-sort-label-and-selected-region-display-through-real-picker",
            "preserve-stable-country-subdivision-IDs-selection-persistence-presets-US-CA-short-form",
            "preserve-nonUS-AU-disambiguation-Pennsylvania-Texas-and-English-fallback-original-text",
            "no-new-strings-generator-services-Android-dependency-translation-provider-or-onboarding-redesign",
            "no-RxLog-ConnectedDeviceEditor-WP212-or-general-App-write-expansion",
            "trusted-main-admission-before-product-edit-same-owner-advisory-evolution",
            "semantic-overlap-serialize-or-transfer-current-producer-no-native-or-Gradle-lock-change",
        ],
        "validation": [
            "exact WP211 five-path named-operation admission; other-WP sibling path generic-operation and cross-purpose denial",
            "future App adapter and mounted OnboardingRegionPresetComposeTest assert retained French German labels through production catalog and picker",
            "future affected declared module build lint tests with positive nonzero discovery and trusted ownership/traceability validators",
            "policy regressions are not mounted UI native accessibility or original-family acceptance",
        ],
    },
    {
        "id": "wp003-scoped-local-verification",
        "work_packages": ["WP-003"],
        "path_rules": [
            "tools/android-port/local/check.py",
            "tools/android-port/local/fast.py",
            "tools/android-port/local/run.sh",
            "tools/android-port/local/hook_test.py",
        ],
        "operations": ["wire-scoped-local-verification"],
        "invariants": [
            "modify-four-existing-runner-regression-files-only-no-blanket-local-tree",
            "preserve-trusted-repository-and-installed-runtime-namespace-no-installation-or-namespace-fallback",
            "preserve-native-Gradle-install-hard-lock-admission-supervision-owned-cleanup-and-execution-identity",
            "actual-complete-candidate-diff-immutable-current-trusted-base-head-tree-binding",
            "requested-scopes-cannot-omit-changed-paths-or-verification-obligations",
            "fixed-declared-task-selections-only-no-arbitrary-Gradle-override",
            "strict-nonzero-test-discovery-no-failures-errors-skips-or-success-shaped-fallback",
            "default-full-prepush-and-result-semantics-unchanged-unclassifiable-or-build-change-full-fallback",
            "existing-hook_test-and-owned-test_local_scope-regressions-no-test-weakening",
            "pinned-source-db14559b39d32322b06477c6ae676112f583db50-primary-provenance-unchanged",
            "trusted-main-admission-before-consumer-edit-same-owner-advisory-evolution",
            "semantic-overlap-serialize-or-transfer-current-producer-no-native-or-Gradle-lock-change",
        ],
        "validation": [
            "exact WP003 four-path named-operation admission; other-WP sibling path generic-operation and cross-purpose denial",
            "future actual candidate-diff selector and hook regressions retain default full and nonblocking prepush results",
            "future stale-base-head-tree namespace interlock supervision cleanup omitted-path arbitrary-task and zero-test negative regressions",
            "declared controller and local runner suites with nonzero discovery; no policy self-consumption runtime deployment or native execution",
        ],
    },
]


HOST_SCOPED_JVM_CAPABILITY = {
    "id": "wp003-host-scoped-jvm-parallelism",
    "work_packages": ["WP-003"],
    "path_rules": [
        "tools/android-port/local/check.py",
        "tools/android-port/local/fast.py",
        "tools/android-port/local/run.sh",
        "tools/android-port/local/hook_test.py",
        "tools/android-port/local/advisory.py",
    ],
    "operations": ["implement-host-scoped-jvm-lanes"],
    "invariants": [
        "source-only-host-lane-JVM-interlock-repair-no-runtime-installation-or-live-execution-authority",
        "one-canonical-shared-durable-ledger-and-repository-no-private-clone-database",
        "actual-machine-host-and-lane-identities-no-caller-nonce-host-label-bypass",
        "atomic-bounded-host-slots-from-measured-safe-CPU-and-RAM-capacity-no-fixed-two-slot-policy-ceiling",
        "serial-default-and-fail-closed-unknown-capacity-host-or-lane-identity",
        "five-existing-local-paths-only-advisory-optional-real-host-slot-through-inherited-fd8-default-six-key-serial-admission-unchanged",
        "existing-hard_locks-atomic-allocation-no-new-database-schema-registry-or-reserve-py-change",
        "per-slot-bundle-repository-report-private-and-Gradle-cache-isolation",
        "native-and-install-two-sided-exclusion-with-all-JVM-lanes-preserve-canonical-hard-lock-guards",
        "pidfd-owned-process-supervision-and-bounded-cleanup-no-unrelated-process-termination",
        "exact-immutable-candidate-base-head-tree-diff-and-declared-task-consumer-closure",
        "stripped-credentials-isolated-candidate-reports-and-strict-nonzero-discovery-no-failure-error-or-skip",
        "preserve-default-full-prepush-and-report-validation-no-arbitrary-task-override-or-fake-success",
        "no-166-row-lease-rewrite-release-recreate-historical-identity-or-authorization-change",
        "existing-scoped-local-verification-capability-and-no-lock-change-invariant-unchanged",
        "trusted-main-admission-before-consumer-edit-same-owner-advisory-evolution",
        "pinned-source-db14559b39d32322b06477c6ae676112f583db50-unchanged",
    ],
    "validation": [
        "exact WP003 path and named-operation positives; generic cross-purpose other-WP and sibling negatives",
        "future actual same-host independent JVM lanes bounded by atomic measured CPU/RAM slots; serial-default and malformed-capacity denial",
        "future canonical shared-ledger real-machine identity tests reject private-clone ledger and caller-host-label bypass",
        "future native-and-install acquire versus held-JVM-lane and JVM acquire versus held-native-install denial in both directions",
        "future pidfd owned-child cleanup credential stripping exact-candidate task-closure and strict report failure/zero/skip regressions",
        "declared controller policy and admission suites discover nonzero tests; no policy fixture counts as real remote parallel execution",
    ],
}


BACKUP_POST_COMMIT_CAPABILITY = {
    "id": "wp203-backup-post-commit-delivery",
    "work_packages": ["WP-203"],
    "path_rules": [
        "android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppBackupFeature.kt",
        "android/app/src/test/kotlin/com/meshcoreone/android/app/platform/BackupRegistrationTest.kt",
        "android/feature/settings/src/main/kotlin/com/meshcoreone/android/feature/settings/app/backup/AppBackupStateHolder.kt",
        "android/feature/settings/src/main/kotlin/com/meshcoreone/android/feature/settings/app/backup/BackupPorts.kt",
    ],
    "operations": ["repair-backup-post-commit-delivery"],
    "invariants": [
        "DATA-05-D149-App-backup-adapter-post-commit-delivery-only-no-unrelated-App-or-backup-feature-authority",
        "pinned-AppBackupService-swift-db14559b39d32322b06477c6ae676112f583db50-ignore-cancellation-after-database-commit",
        "pre-commit-cancellation-propagates-with-atomic-rollback-no-success-before-real-commit",
        "post-commit-cancellation-cannot-convert-committed-success-to-error-or-cancellation-receipt",
        "state-holder-preserves-success-after-engine-return-when-UI-effects-cancel-rethrow-CancellationException-no-nothing-changed-claim",
        "BackupPorts-contract-doc-only-pre-commit-rollback-versus-committed-result-and-typed-preference-failure",
        "existing-restored-process-effects-once-only-no-database-reimport-or-duplicate-effects",
        "typed-CommittedBackupPreferenceException-retains-committed-result-snapshot-and-original-StorageFailure",
        "preference-only-retry-and-acknowledgement-guard-preserved-no-masked-real-storage-or-refresh-failure",
        "envelope-v1-Unix-seconds-Codable-bytes-UUIDs-source-compression-50MiB-compressed-512MiB-expanded-unchanged",
        "real-Room-DataStore-adapter-regression-not-cancellation-swallowing-or-self-derived-success",
        "WP-203-WP-318-WP-407-primary-provenance-and-original-test-family-accounting-unchanged-no-replayed-credit",
        "same-owner-stored-identity-and-authorization-immutable-no-impersonation-release-recreate-or-ledger-rewrite",
        "trusted-main-admission-before-consumer-edit-semantic-overlap-serialized-with-current-producer",
        "source-only-no-runtime-installation-lock-resource-settings-dispatch-or-merge-authority",
        "backup-secrets-original-data-errors-and-license-obligations-preserved-no-generic-sensitive-logging",
    ],
    "validation": [
        "exact WP203 four-path named-operation admission; generic cross-purpose all-other-WP and sibling denials",
        "future actual pre-commit cancellation rollback versus afterCommit cancellation committed success and once-only process effects",
        "future actual typed locked-preference committed receipt and preference-only retry retain rows and do not duplicate counts or effects",
        "future genuine post-restore refresh failure remains explicit and cannot be hidden by cancellation repair",
        "future actual Room-service-AppBackupStateHolder test retains Success after UI-effect cancellation while pre-return cancellation is Cancelled and rethrown",
        "declared affected backup and App typed regressions with nonzero discovery and no failures errors or skips",
        "exact complete predecessor object and raw Git-blob equality; policy fixtures are not backup or iOS-Android compatibility evidence",
    ],
}


RX_PROJECTION_REGRESSION_CAPABILITY = {
    "id": "wp201-rx-projection-source-regressions",
    "work_packages": ["WP-201"],
    "path_rules": [
        "android/core/data/src/test/kotlin/com/meshcoreone/android/core/data/repository/RxLogSourceTest.kt",
        "android/core/data/src/test/kotlin/com/meshcoreone/android/core/data/repository/ParityRxMaintenanceOriginalPredicateTest.kt",
    ],
    "operations": ["restore-rx-projection-source-regressions"],
    "invariants": [
        "DATA-08-pinned-PersistenceStoreTests-and-RxLogEntryDTO-db14559b39d32322b06477c6ae676112f583db50-source-baseline-not-new-adaptation",
        "two-existing-repository-tests-only-first-corruption-method-and-OriginalCase-annotation-unused-import-cleanup-only",
        "route999-to-flood-payloadMinus1-to-unknown-version5000-to-zero-path400-to-zero-channel9999-to-null-timestampMinus10-to-null",
        "exact-checked-failable-projection-no-wrapping-clamping-or-defaults-outside-source-approved-fields",
        "real-RoomPersistenceStore-fetch-retains-entry-id-radio-raw-bytes-healthy-companion-and-nonempty-result",
        "compound-and-six-isolated-parameter-families-retained-with-independent-absolute-expected-values",
        "repeated-reads-preserve-entire-stored-raw-row-no-write-delete-recreate-or-spurious-issue-report",
        "all-other-test-methods-pruning-batching-retention-ordering-and-primary-WP202-provenance-unchanged",
        "reject-unapproved-strict-corrupt-projection-adaptation-not-expectation-weakening-or-replayed-source-credit",
        "RxLogService-source-catch-log-empty-list-and-CancellationException-propagation-unchanged-no-service-or-UI-authority",
        "no-new-test-create-no-test-subtree-no-converter-schema-migration-build-dependency-or-runtime-authority",
        "same-owner-stored-identity-and-authorization-preserved-no-impersonation-release-recreate-or-ledger-rewrite",
        "trusted-main-admission-before-consumer-edit-semantic-overlap-serialized-with-current-producer",
        "original-raw-data-secrets-and-license-obligations-preserved-no-sensitive-generic-logging",
    ],
    "validation": [
        "exact WP201 two-existing-test named-operation positives; generic create modify cross-purpose all-other-WP and sibling denials",
        "future actual compound and six-isolated source fallbacks through real Room repository with same identities bytes and healthy companion",
        "future repeated repository reads prove entire raw-row equality no mutation deletion or spurious issues",
        "future affected declared original-case tests retain all non-corruption methods and nonzero discovery without failure error or skip",
        "exact complete trusted predecessor object and raw Git-blob equality; no service production or new test admission",
        "policy fixtures prove admission only not actual Room service UI Android or original-family acceptance",
    ],
}


def rx_projection_regression_predecessor(policy):
    """Reverse only the exact existing-test source-baseline support rule."""
    if not isinstance(policy, dict):
        raise PortError("RX projection regression amendment requires a policy object")
    result = copy.deepcopy(policy)
    if "reservation_policy" not in result:
        return result
    reservation = result["reservation_policy"]
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("RX projection regression amendment requires the canonical capability catalog")
    selected = [i for i, rule in enumerate(rules)
                if isinstance(rule, dict) and rule.get("id") == RX_PROJECTION_REGRESSION_CAPABILITY["id"]]
    path_matches = [
        rule for rule in rules
        if isinstance(rule, dict) and isinstance(rule.get("path_rules"), list)
        and isinstance(rule.get("operations"), list)
        and "restore-rx-projection-source-regressions" in rule["operations"]
        and ("work_packages" not in rule or (
            isinstance(rule["work_packages"], list) and "WP-201" in rule["work_packages"]
        ))
        and any(CapabilityEngine._matches(pattern, path)
                for path in RX_PROJECTION_REGRESSION_CAPABILITY["path_rules"]
                for pattern in rule["path_rules"] if isinstance(pattern, str))
    ]
    if not selected and not path_matches:
        return result
    if len(selected) != 1:
        raise PortError("RX projection capability changed outside the exact two-existing-test amendment")
    index = selected[0]
    if (
        rules[index] != RX_PROJECTION_REGRESSION_CAPABILITY
        or path_matches != [RX_PROJECTION_REGRESSION_CAPABILITY]
        or index == 0 or not isinstance(rules[index - 1], dict)
        or rules[index - 1].get("id") != "generated-resources"
        or index + 1 >= len(rules) or rules[index + 1] != BACKUP_POST_COMMIT_CAPABILITY
    ):
        raise PortError("RX projection capability changed outside the exact two-existing-test amendment")
    rules.pop(index)
    return result


def backup_post_commit_predecessor(policy):
    """Reverse only the exact WP-203 adapter support rule."""
    if not isinstance(policy, dict):
        raise PortError("Backup post-commit amendment requires a policy object")
    result = rx_projection_regression_predecessor(policy)
    if "reservation_policy" not in result:
        return result
    reservation = result["reservation_policy"]
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("Backup post-commit amendment requires the canonical capability catalog")
    selected = [i for i, rule in enumerate(rules)
                if isinstance(rule, dict) and rule.get("id") == BACKUP_POST_COMMIT_CAPABILITY["id"]]
    path_matches = [
        rule for rule in rules
        if isinstance(rule, dict) and isinstance(rule.get("path_rules"), list)
        and isinstance(rule.get("operations"), list)
        and "repair-backup-post-commit-delivery" in rule["operations"]
        and ("work_packages" not in rule or (
            isinstance(rule["work_packages"], list) and "WP-203" in rule["work_packages"]
        ))
        and any(CapabilityEngine._matches(pattern, path)
                for path in BACKUP_POST_COMMIT_CAPABILITY["path_rules"]
                for pattern in rule["path_rules"] if isinstance(pattern, str))
    ]
    if not selected and not path_matches:
        return result
    if len(selected) != 1:
        raise PortError("Backup post-commit capability changed outside the exact four-path amendment")
    index = selected[0]
    if (
        rules[index] != BACKUP_POST_COMMIT_CAPABILITY
        or path_matches != [BACKUP_POST_COMMIT_CAPABILITY]
        or index == 0 or not isinstance(rules[index - 1], dict)
        or rules[index - 1].get("id") != "generated-resources"
        or index + 1 >= len(rules) or not isinstance(rules[index + 1], dict)
        or rules[index + 1].get("id") != "persistence-schemas"
    ):
        raise PortError("Backup post-commit capability changed outside the exact four-path amendment")
    rules.pop(index)
    return result


def host_scoped_jvm_predecessor(policy):
    """Remove only the exact source-support rule, retaining the complete previous policy."""
    if not isinstance(policy, dict):
        raise PortError("Host-scoped JVM amendment requires a policy object")
    result = backup_post_commit_predecessor(policy)
    if "reservation_policy" not in result:
        return result
    reservation = result["reservation_policy"]
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("Host-scoped JVM amendment requires the canonical capability catalog")
    selected = [i for i, rule in enumerate(rules)
                if isinstance(rule, dict) and rule.get("id") == HOST_SCOPED_JVM_CAPABILITY["id"]]
    path_matches = [
        rule for rule in rules
        if isinstance(rule, dict) and isinstance(rule.get("path_rules"), list)
        and isinstance(rule.get("operations"), list)
        and "implement-host-scoped-jvm-lanes" in rule["operations"]
        and ("work_packages" not in rule or (
            isinstance(rule["work_packages"], list) and "WP-003" in rule["work_packages"]
        ))
        and any(CapabilityEngine._matches(pattern, path)
                for path in HOST_SCOPED_JVM_CAPABILITY["path_rules"]
                for pattern in rule["path_rules"] if isinstance(pattern, str))
    ]
    if not selected and not path_matches:
        return result
    if (
        selected != [0] or rules[0] != HOST_SCOPED_JVM_CAPABILITY
        or path_matches != [HOST_SCOPED_JVM_CAPABILITY]
        or len(rules) < 2 or rules[1] != DISPOSABLE_CAPABILITY
    ):
        raise PortError("Host-scoped JVM capability changed outside the exact bounded amendment")
    rules.pop(0)
    return result


def wp503_accessibility_source_binding_predecessor(policy):
    """Reverse only the exact WP-503 validator/test capability pair."""
    if not isinstance(policy, dict):
        raise PortError("WP-503 accessibility binding amendment requires a policy object")
    result = host_scoped_jvm_predecessor(policy)
    reservation = result.get("reservation_policy")
    if reservation is None:
        return result
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("WP-503 accessibility binding amendment requires the canonical capability catalog")
    expected_ids = {rule["id"] for rule in WP503_ACCESSIBILITY_SOURCE_BINDING_CAPABILITIES}
    selected = [
        rule for rule in rules
        if isinstance(rule, dict) and rule.get("id") in expected_ids
    ]
    path_matches = [
        rule for rule in rules
        if isinstance(rule, dict)
        and isinstance(rule.get("path_rules"), list)
        and isinstance(rule.get("operations"), list)
        and ("work_packages" not in rule or (
            isinstance(rule["work_packages"], list) and "WP-503" in rule["work_packages"]
        ))
        and any(
            operation in rule["operations"] and CapabilityEngine._matches(pattern, path)
            for expected in WP503_ACCESSIBILITY_SOURCE_BINDING_CAPABILITIES
            for operation in expected["operations"]
            for path in expected["path_rules"]
            for pattern in rule["path_rules"] if isinstance(pattern, str)
        )
    ]
    if not selected and not path_matches:
        return result
    if (
        selected != WP503_ACCESSIBILITY_SOURCE_BINDING_CAPABILITIES
        or path_matches != WP503_ACCESSIBILITY_SOURCE_BINDING_CAPABILITIES
        or len(rules) < 3 or rules[-2:] != WP503_ACCESSIBILITY_SOURCE_BINDING_CAPABILITIES
        or rules[-3] != RECEIVE_PREFETCH_ADMISSION_CAPABILITY
    ):
        raise PortError("WP-503 accessibility binding capabilities changed outside the exact two-rule amendment")
    del rules[-2:]
    return result


def consumer_support_predecessor(policy):
    """Reverse only the complete exact consumer-support block; preserve all other policy bytes."""
    if not isinstance(policy, dict):
        raise PortError("Consumer support amendment requires a policy object")
    result = copy.deepcopy(policy)
    if "reservation_policy" not in result:
        return result
    reservation = result["reservation_policy"]
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("Consumer support amendment requires the canonical capability catalog")
    identities = {rule["id"] for rule in CONSUMER_SUPPORT_CAPABILITIES}
    selected = [i for i, rule in enumerate(rules)
                if isinstance(rule, dict) and rule.get("id") in identities]
    if not selected:
        return result
    start, end = selected[0], selected[-1] + 1
    if (
        rules[start:end] != CONSUMER_SUPPORT_CAPABILITIES
        or len(selected) != len(CONSUMER_SUPPORT_CAPABILITIES)
        or start == 0 or not isinstance(rules[start - 1], dict)
        or rules[start - 1].get("id") != "disposable-native-guest"
        or end >= len(rules) or not isinstance(rules[end], dict)
        or rules[end].get("id") != "wp-owned"
    ):
        raise PortError("Consumer support capabilities changed outside the exact bounded amendment")
    del rules[start:end]
    return result


def room_failed_event_predecessor(policy):
    if not isinstance(policy, dict):
        raise PortError("Room failed-event amendment requires a policy object")
    result = wp503_accessibility_source_binding_predecessor(policy)
    result = consumer_support_predecessor(result)
    reservation = result.get("reservation_policy")
    if reservation is None:
        return result
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("Room failed-event amendment requires the canonical capability catalog")
    selected = [i for i, rule in enumerate(rules)
                if isinstance(rule, dict) and rule.get("id") == ROOM_FAILED_EVENT_CAPABILITY["id"]]
    if not selected:
        return result
    index = selected[0]
    if (
        len(selected) != 1 or rules[index] != ROOM_FAILED_EVENT_CAPABILITY
        or index == 0 or not isinstance(rules[index - 1], dict)
        or rules[index - 1].get("id") != "wp-owned"
        or index + 1 >= len(rules) or not isinstance(rules[index + 1], dict)
        or rules[index + 1].get("id") != "dependency-resolution-metadata"
    ):
        raise PortError("Room failed-event capability changed outside the exact two-path amendment")
    rules.pop(index)
    return result


def bubble_consumer_predecessor(policy):
    if not isinstance(policy, dict):
        raise PortError("Bubble consumer amendment requires a policy object")
    result = room_failed_event_predecessor(policy)
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
