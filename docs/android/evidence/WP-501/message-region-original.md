# WP-501 original eight-row region-footer gate family

## Bounded assertion gap and frozen comparison

Reference remains `db14559b39d32322b06477c6ae676112f583db50`.
The complete selected original is `MessageBubblePredicateTests.regionToShow:
nil unless flag AND isFloodRouted AND scope present`, lines 48-69 of
`MC1Tests/Views/Chats/Components/MessageBubblePredicateTests.swift`.
The full suite/local `makeMessage`, complete `MessageBubbleTestData.messageItem`,
Swift/native DTO routing, region coalescing and production footer builders were
compared directly. The selected original, helper, Swift builder and DTO source
are unchanged between the reference pin and actual main7a42.

Existing `RenderingModelsTest` has channel, legacy nil-route/path255, ambiguous,
scope-only and preference-off checks, but no explicit TC route region result.
The delivered seven-row `hasFooter` OR lacks TC variants and exact region-string
equality; co-contributors can mask the selected output. `DomainBoundaryTest`
checks routing only and the delivered hop family asserts only `footer.showHop`.
Existing App bubble-label originals/mounted tests use channel region fixtures.
Region model policy tests cover coalescing, not the builder's preference/routing
boundary. Thus a region-only TC_FLOOD suppression or TC_DIRECT admission lacks
its own footer assertion. Partial incumbent coverage is not total absence of
region tests, a presumed product defect or a reason to replay healthy families.

## Complete original inputs and production assertion

| Row | showIncomingRegion | routeType | regionScope | expected footer.regionToShow |
| --- | --- | --- | --- | --- |
| 0 | true | flood | United States | United States |
| 1 | true | tcFlood | United States | United States |
| 2 | true | flood | nil | nil |
| 3 | true | direct | United States | nil |
| 4 | true | tcDirect | United States | nil |
| 5 | false | flood | United States | nil |
| 6 | false | direct | United States | nil |
| 7 | true | direct | nil | nil |

`MessageRegionOriginalPredicateTest.allEightOriginalRegionRowsUseTheProductionBuilder`
retains all eight ordered literal tuples and directly compares
`MessageFragmentBuilder.makeItem(message, inputs, env).footer.regionToShow`
with the original nullable string. No candidate predicate computes expectations.
Native `makeFooter` already combines the actual preference and
`MessageDTO.isFloodRouted`, then uses real `RegionScopeSemantics.coalesce` and
`chipLabel`. Both protocol TC variants remain distinct real route inputs.
No producer or consumer repair is needed.

Source fresh message/radio UUIDs, nil contact/channel, Test text, timestamp0,
incoming/delivered/plain/read, pathLength2/A37F, nil sender, zero repeats/retries,
sendCount1, empty region matches and remaining DTO defaults are preserved.
Every row stays a DM: a channel would force flood and invalidate direct rows.
Ambient `Date()` becomes `Instant.EPOCH`, irrelevant to this predicate.
Helper inputs retain idle/nil preview, URL/images/formatted text/path, incoming
base color, Unknown/unresolved sender, sender-groupingtrue and other grouping
flagsfalse. Environment retains previewsEnabledtrue/currentUserNameMe and the
other source-equivalent defaults; removed translation offers are explicitly
false. No UIKit image resolver is necessary for this pure footer assertion.

The real App already consumes this footer in
`AppMessageBubbleAccessibility.messageBubbleAccessibilityItem`/`Label` through
`AppChatsContent -> AppChatConversationScreen -> ChatTimeline` private bubble
semantics. This test asserts the public production result at the original
helper boundary, not a fabricated DTO predicate or new mounted/speech scenario.
Adjacent channel override, legacy sentinel, region label and ambiguity originals
are not bundled into this single selected family or newly credited here.

## Integration, reservation and execution boundary

Implementation base is actual main
`7a42bb666d593e64df49d0267d27f3d87a7e125f`, parents
`16e5e77dfcd105458b22792755d34e6b2ea82f23` and
`6417b204b41337f83354ec99cbfec7df398636f9`, tree
`bf8532052febe3f4570a6ca49cd7830f5896c496`.
The clean managed branch fast-forwarded from delivered0a8c with no conflicts;
all 22 incoming paths and WP-309/502/503/504 producer attributions remain.
Same WP-501 attempt/session `d6056690-3a5d-453e-b67e-36866c676ac5`
evolved128->129 under trusted `merge-integration`/`reconcile-current-base`,
then129->130 under `wp-owned`/`modify` for only this test, evidence, proposal and
deviation before edits. Immutable authorization, identities and original
source/manifest/policy binding remain; no release/recreate or manual ledger edit.
No product, incoming owner entry, build/runner or archived accounting is changed.

Existing `:app` test-source wiring discovers synchronous JUnit4 parity tests.
One original family/eight literal inputs/one annotated method is not eight
families or eight discoveries. Declared non-native validation results are
recorded below; Kotlin compilation, actual method discovery/execution and
independent review remain **UNVERIFIED** for this local-only candidate.
Publication is held for coordinator source review. No actual TalkBack, mounted
UI, native/device/hardware, protocol/backup oracle or whole-WP acceptance follows.
Historical footer/heard/hop/bubble exact hosted successes remain historical;
their implementation-time UNVERIFIED notes are not new software gaps or results
transferred to this candidate. Main39947250641 was still running when assigned,
not claimed green. No CI logs/results bundle or native/Gradle work is consumed.

## Local non-native validation

Declared `python tools\android-port\controller\ci.py preflight` is **BLOCKED**
(exit2): explicit `--state/ANDROID_CI_STATE` is absent. No readiness state,
runtime/hook/lock repair, protected probe or resource bypass is introduced.
Separate declared non-native checks returned:

| Command | Actual local result |
| --- | --- |
| `python tools\android-port\controller\validate.py` | PASS: frozen source/ownership valid; canonical manifest SHA256 `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8` |
| `python tools\android-port\portmap.py` | PASS: 1878 provenance entries; not behavioral parity |
| `python android\parity-tests\semantic.py --check` | PASS: read-only original/native assertion and runner bindings; one new selected family proposal, not independent approval |
| `python tools\android-port\controller\test_runner.py --tests android\parity-tests --quiet` | PASS: 108 discovered/run/passed, zero failures/errors/skips, 6.815s; controller fixtures only |
| `git diff --check` | PASS |

No generation mode or execution-verification flag was used; historical generated
accounting/groups/bindings remain unchanged. These source/controller results do
not establish Kotlin compilation, JUnit discovery or the eight rows passing.
Actual required identity remains
`com.meshcoreone.android.parity.MessageRegionOriginalPredicateTest.allEightOriginalRegionRowsUseTheProductionBuilder`.
