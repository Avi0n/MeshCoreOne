# WP-501 original incoming-DM region-label consumer cases

## Frozen cases and concrete missing assertions

Source remains `db14559b39d32322b06477c6ae676112f583db50`.
`MC1Tests/Views/Chats/Components/MessageBubblePredicateTests.swift:152-194`
contains two separate standalone consumer originals:

| Original | Source helper call | Source assertion |
| --- | --- | --- |
| `accessibilityMessageLabel: direct-routed message must not include region fragment` | DIRECT, scope United States; hoptrue, regiontrue | Actual bubble label contains localized region fragment == false |
| `accessibilityMessageLabel: flood-routed message includes region fragment` | FLOOD, scope United States; regiontrue, default hopfalse | Actual bubble label contains localized region fragment == true |

The complete local `makeMessage` and `MessageBubbleTestData.messageItem` helpers,
Swift `UnifiedMessageBubble.accessibilityMessageLabel` and `.directMessage`
configuration, native DTO/environment defaults, actual footer builder and live
App label function were compared. Selected Swift suite/helper/consumer/
configuration/builder remain unchanged from the frozen pin.

Existing `MessageBubbleAccessibilityAcceptanceTest` asserts positive region
labels and preference updates on channel1/nil-route fixtures; its mounted DM is
an outgoing failed send without region. Those inputs do not assert the enabled,
populated incoming DIRECT-DM exclusion or incoming FLOOD-DM inclusion.
The delivered eight-row region family reads only `footer.regionToShow`, not
its label consumption. DTO routing and footer-OR assertions likewise cannot
replace these consumer predicates. This is not total absence of region coverage,
an annotation-only replay, another eight-row family or a presumed product defect.

## Exact input and assertion mapping

Two separately annotated methods in existing
`MessageBubbleAccessibilityAcceptanceTest` retain the original cases:

- `directRoutedOriginalIncomingDmLabelOmitsRegionFragment`: DIRECT,
  `sourceItem(message, hop = true, region = true)`, localized containmentfalse.
- `floodRoutedOriginalIncomingDmLabelIncludesRegionFragment`: FLOOD,
  `sourceItem(message, region = true)`, retaining helper hopfalse,
  localized containmenttrue.

New `predicateIncomingDm` preserves fresh message/radio UUIDs, explicit nil
contact/channel, Test text, timestamp0, incoming/delivered/plain/read,
pathLength2/A37F, nil sender/key/ack/SNR/reply/RTT, zero heard/retry/max-retry,
sendCount1, empty region matches and remaining original DTO defaults.
Only irrelevant ambient `Date()` becomes deterministic `Instant.EPOCH`.
The incumbent channel `incoming` helper and all existing methods stay unchanged.

Existing `sourceItem` is reused unchanged: real public production
`MessageFragmentBuilder.makeItem`, idle/nil previews/URL/images/formatted text/
path, incoming base color, Unknown/unresolved sender, sender-groupingtrue,
other grouping flagsfalse, previewsEnabledtrue/currentUserNameMe and
source-equivalent environment defaults. Removed translation offers remain false.
The row-specific preference arguments are not normalized or combined.

Both returned real items feed actual production
`messageBubbleAccessibilityLabel(message, item, false, resources)`.
The false argument preserves `.directMessage.showSenderName == false`, distinct
from the helper's grouping flag. Swift's supplied `contactName: Alice` does not
participate in this selected label path; no Alice resolution is fabricated.
Expected fragments come independently from the resource-derived
`Chats.chatsMessageRegionAccessibilityLabel(resources, "United States")`, as the
original localization call does, not from footer output or a candidate predicate.
Both original Boolean containment assertions remain; they are not replaced by
DTO routing, full-label assumptions or a fake label evaluator.

The live App already calls the same item/label functions through
`AppChatsContent -> AppChatConversationScreen -> ChatTimeline` private bubble
semantics. Production footer and label behavior match source; no product repair
is needed. These two tests do not newly mount that screen, run TalkBack or certify
native accessibility. Source image resolver/layout construction maps to direct
invocation of the real label function in the existing API31 Robolectric host;
neither selected predicate reads an image or layout payload with translation.

## Ownership, base and local execution boundary

Implementation base is actual merged main
`e1b9f42c43ef60d592efe38e039984100e02c061`, parents
`7a42bb666d593e64df49d0267d27f3d87a7e125f` and
`f0dfa052211417e5c3688da8e7a56f9629f9b52a`, tree
`8a8f1dce1ddadb853997b4e7f681fa48465e2e18`.
The managed branch fast-forwarded cleanly from f0 to its authoritative merge,
with identical trees and zero incoming file changes. Delivered PR308/303 and
all prior source/assertion/result histories remain; no foreign transfer is needed.

Same attempt/session `d6056690-3a5d-453e-b67e-36866c676ac5` evolved
receipt130->131 through trusted `wp-owned`/`modify` before exactly four owned
paths: existing test, this evidence, additive proposal and WP-501 deviation.
Immutable identity, authorization and original binding remain; no release/
recreate, manual ledger edit or product capability expansion. No product, runner,
build, existing assertion/helper body or generated accounting archive is changed.
The additive proposal separately joins two originals to their two native methods.

Publication is held for coordinator source review and actual PR309 integration
after landing; PR309 results are not borrowed. Required exact runner identities
are class `com.meshcoreone.android.parity.MessageBubbleAccessibilityAcceptanceTest`
plus the two methods above in the existing App JUnit4 test source set.
Two original cases/two declared methods are not execution discoveries before a
real candidate run. Kotlin compilation, actual discovery/execution and independent
review remain **UNVERIFIED**. No native/Gradle resource work, CI dispatch/rerun,
CI-log reread/result bundle, new campaign or whole-WP acceptance is introduced.

## Declared non-native validation

`python tools\android-port\controller\ci.py preflight` is **BLOCKED**, exit2:
explicit `--state/ANDROID_CI_STATE` absent. No state, runtime, hook or lock is
changed or bypassed. Separate declared non-native results:

| Command | Actual local result |
| --- | --- |
| `python tools\android-port\controller\validate.py` | PASS: pinned source/ownership/dependencies valid; canonical manifest SHA256 `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8` |
| `python tools\android-port\portmap.py` | PASS: 1878 provenance entries, not behavioral parity |
| `python android\parity-tests\semantic.py --check` | PASS: read-only source/native assertion and runner joins; two new original-case proposals, not approval or execution |
| `python tools\android-port\controller\test_runner.py --tests android\parity-tests --quiet` | PASS: 108 discovered/run/passed, zero failures/errors/skips, 6.953s; controller fixtures only |
| `git diff --check` | PASS |

Selected frozen source equality and product/incumbent proposal preservation
checks also passed. No generated accounting/groups/bindings were rewritten.
Current trusted policy revision is
`46a1aebeace81c8719901030e859569df4c2008e19cb841d80e887b5801077d8`;
the unchanged historical receipt binding is not this candidate's base or digest.
These source/controller results address only bounded `WP-501-behavior`,
`WP-501-boundaries` and `WP-501-source-test-parity`; they do not supply Kotlin
execution, passing original-case counts or macro-WP completion.
