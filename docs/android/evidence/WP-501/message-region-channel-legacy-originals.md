# WP-501 original channel override and legacy direct-region edges

## Two frozen originals and exact assertions

Source remains `db14559b39d32322b06477c6ae676112f583db50`.
`MC1Tests/Views/Chats/Components/MessageBubblePredicateTests.swift:72-94`
defines two distinct standalone originals:

| Original | Exact source inputs | Required actual production-footer assertion |
| --- | --- | --- |
| `regionToShow: channel messages render region regardless of routeType()` | channelIndex0, explicit DIRECT, United States sticky scope, regiontrue; all other helper defaults | `regionToShow == "United States"` |
| `regionToShow: legacy radio with no routeType respects pathLength 0xFF as direct()` | nil-channel DM, nil route, pathLength255, United States sticky scope, EMPTY matches, regiontrue; retain A37F path bytes and other defaults | `regionToShow == nil` |

Existing `RenderingModelsTest` channel3/nil-route/path2 coverage cannot assert
the explicit-DIRECT channel override. Its generic nil-route/direct-path case
retains alpha/beta multi-match input, not the original sticky-only legacy input.
Those generic cases remain unchanged; neither is waived or counted as one of
these two originals. Existing eight-row coverage has no channel override or
nil-route/255 row.

Two new distinct `@OriginalCase` methods in existing
`MessageRegionOriginalPredicateTest` retain the source assertions:

- `directRouteOriginalChannelStillShowsRegion`
- `legacyNoRouteOriginalDirectPathHidesRegion`

Both read `sourceFooter(...).regionToShow` from the actual production builder.
String equality and `assertNull` are native equivalents of the source checks;
no expected value comes from DTO routing, a fake footer or candidate predicates.
These are two originals/two declarations, not passing discoveries, another
eight-row family, whole-file or whole-WP acceptance.

## Complete defaults and bounded helper extraction

The complete frozen `makeMessage` and `MessageBubbleTestData.messageItem`
helpers, actual DTO routing and Swift/native footer consumers were read.
Selected source/helper/consumer Git blobs and worktree source content remain
equal to the frozen reference under normal Git checkout normalization.

The eight-row test's inline message construction and builder inputs/environment
are extracted into private `makeMessage` and `sourceFooter` helpers in the same
class. All eight ordered literal rows, row-specific flags/routes/scopes/expected
strings, indexed equality predicates and `RegionCase` representation remain.
Previously implicit nil contact/channel/sender and empty matches are explicit
equivalent helper defaults. Existing matrix calls retain nil channel, path2,
A37F and each original row route/scope without new behavior.

The shared constructor retains fresh message/radio UUIDs, nil contact, Test
text, timestamp0, incoming/delivered/plain/read, nil ack/SNR/sender-key/reply/RTT,
heard0/retry0/max0/sendCount1 and remaining actual DTO defaults. Only irrelevant
ambient `Date()` maps to `Instant.EPOCH`. The channel original retains source
`senderNodeName == "RemoteNode"`; the legacy DM retains nil sender. Matches are
explicitly empty for every call. Path bytes stay A37F even with pathLength255;
they are not simplified away because a direct sentinel is present.

`sourceFooter` retains the former exact build inputs: actual messageID,
idle/nil preview/URL/images/formatted text/path, incoming base color,
Unknown/unresolved sender, sender-groupingtrue and other grouping flagsfalse.
Actual environment defaults plus the supplied region flag, previewsEnabledtrue
and currentUserNameMe are unchanged. Removed translation offers remain false.
The helper calls real public `MessageFragmentBuilder.makeItem(...).footer`;
it does not reproduce region gating or coalescing.

Production DTO routing prioritizes channel presence, then explicit route, then
legacy pathLength inference. The actual footer consumes
showIncomingRegion/isFloodRouted and then sticky/matches coalescing. Source
comparison supports the expected branches, not a presumed product defect or
runtime pass. No product repair, lookalike evaluator, new framework, mount,
label/TalkBack/native scenario or runner change is introduced.

## Local ancestry, ownership and frozen publication

This follow-on's local parent is the still-published PR312 candidate
`7786ba0929862f687a5a66d7872c72c6b7dce20f`, tree
`b2d8b86f86d189ef29b86a2c636169d2f80a0861`, whose sole parent is actual main
`b8c6c610d5d4f5fe729ecd86ea89117aaebb9393`.
No reset, stash, history rewrite, new branch or current-main integration is
performed. Public7786 and PR312 remain frozen; its pending hosted work is not
this local successor's execution evidence.

Same stored attempt/session `d6056690-3a5d-453e-b67e-36866c676ac5` evolves
receipt133->134 through trusted `wp-owned/modify` before exactly four owned
paths: existing region test, this evidence, one additive two-original proposal
and owned deviation. Only the new evidence path extends the existing scope.
Identity, immutable binding/authorization, prior capabilities/attributions,
branch/worktree and complete protected-lock records are preserved; no manual
ledger edit or release/recreate.

All 197 incumbent proposal groups/text remain unchanged. The published three
ambiguity cases and their complete acceptance-test blob remain unchanged.
Product, generic tests, App source-set/build/runner, tools, native fixtures and
generated accounting archives are not edited.

## Declared local results and remaining execution

| Exact command | Actual result |
| --- | --- |
| `python tools\android-port\controller\validate.py` | PASS: pinned source/ownership/dependencies valid; canonical manifest SHA256 `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8` |
| `python tools\android-port\portmap.py` | PASS: 1878 provenance entries, not behavioral acceptance |
| `python android\parity-tests\semantic.py --check` | PASS: read-only source/native assertion and runner joins, including two new original mappings; not independent approval or execution |
| `python tools\android-port\controller\test_runner.py --tests android\parity-tests --quiet` | PASS: 108 discovered/run/passed, zero failures/errors/skips, 6.943s; controller fixtures only |
| `python tools\android-port\controller\ci.py preflight` | BLOCKED, exit2: explicit `--state/ANDROID_CI_STATE` absent; no fabricated state or bypass |
| `git diff --check` | PASS |

Current trusted policy revision remains
`46a1aebeace81c8719901030e859569df4c2008e19cb841d80e887b5801077d8`.
Immutable historical receipt metadata is not rewritten to these candidate
digests. Historical implementation-time UNVERIFIED notes remain history,
not newly reopened source gaps; no past hosted result is borrowed.

**LOCAL ONLY; PUBLICATION HELD** for independent root source review and actual
PR312 CI/current-main sequencing. New Kotlin compilation and actual two-method
discovery/execution remain UNVERIFIED. Required class is
`com.meshcoreone.android.parity.MessageRegionOriginalPredicateTest` in the
existing App JUnit4 source set; its old eight-row method must also remain.
No push, PR/body mutation, second PR, manual Gradle/native/device/resource/
allowance action, dispatch/rerun, successful official-log reread or CI-result
bundle is introduced. Bounded acceptance references are `WP-501-behavior`,
`WP-501-boundaries` and `WP-501-source-test-parity`; WP-501/issue134 remain
unfinished.
