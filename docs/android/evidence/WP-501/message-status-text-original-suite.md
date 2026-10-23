# WP-501 complete original message-status text suite

## Frozen suite and actual missing predicates

The complete `MC1Tests/Views/Chats/Components/MessageStatusTextTests.swift`
at `db14559b39d32322b06477c6ae676112f583db50` has four standalone originals,
no parameterized declarations or external test helpers. Its Git blob is
`df146ebbc528c44e08b24f0d8a0d73e5b78942e0`. All four now have separate
`@OriginalCase` methods in `MessageStatusTextOriginalPredicateTest`:

| Frozen declaration/body | Exact footer input | Independent localized equality | Native method |
| --- | --- | --- | --- |
| `DM .sent renders as Sending()`, lines57-60 | SENT, channel=false | `Chats.chatsMessageStatusSending` | `directSentOriginalRendersAsSending` |
| `Channel .sent renders as Sent()`, lines64-67 | SENT, channel=true | `Chats.chatsMessageStatusSent` | `channelSentOriginalRendersAsSent` |
| `DM .delivered still renders as Delivered()`, lines70-73 | DELIVERED, channel=false | `Chats.chatsMessageStatusDelivered` | `directDeliveredOriginalRendersAsDelivered` |
| `DM .failed still renders as Failed()`, lines76-79 | FAILED, channel=false | `Chats.chatsMessageStatusFailed` | `directFailedOriginalRendersAsFailed` |

The concrete missing behavior pair is transient outgoing DM SENT versus
terminal channel SENT. The source explicitly explains that radio queueing is
not a DM acknowledgement. Current `MessageBubbleAccessibilityAcceptanceTest`
checks PENDING/Sending, same-label repeated reads for SENT, wide retry/repeat/
send counters and a mounted FAILED-DM label/retry. The SENT stability test
asserts stable text, not the correct status resource, and uses a DM fixture.
The wide-counter DELIVERED check asserts repeat/count fragments, not the
Delivered status equality. The mounted FAILED-DM assertion overlaps the fourth
behavior but does not port this complete original fixture/suite; that overlap
is disclosed, not another novel defect or extra original credit.

Room status cases use the separate room consumer and its intentionally
different room labels. They are neither modified nor counted here.
The four already-covered UnifiedMessageBubble label originals and all eleven
MessageBubblePredicate declarations remain untouched; no healthy replay,
MessagePathArrival/Rooms work, issue261 test or catalog/parser increment is
introduced. No current proposal or original annotation already joins these
four MessageStatusText originals.

## Full makeItem defaults and real producer/consumer

Frozen `makeItem` lines8-52 is ported in full: separate fresh item/envelope UUIDs;
outgoing=true; lower-case `me` sender and EXACT `me` resolution; supplied raw
status; Unix-second date1700000000; hasFailed only for FAILED; self-mention/
mentionSeen=false; nil incoming avatar; empty content. Footer showHop=false,
hopCount0, nil formattedPath/region/sendTime, sendTimeWasCorrected=false,
showStatusRow=true, supplied status/channel, heardRepeats default0, retry0,
maxRetry0 and sendCount default1 are retained. Source/native footer defaults
keep empty region names and showHeardCount=false. All four grouping flagsfalse
plus source/native showDayDivider defaultfalse and preview-fetch=false remain.
Swift Int counters use native Long; Date maps exactly to Instant, not ambient
time. No source input is replaced with another fixture's sender or defaults.

The source calls `MessageStatusText.text(for: item.footer)` in
`MC1/Views/Chats/Components/BubbleFooterRow.swift:342-387`. Actual Android
`AppMessageBubbleAccessibility.kt` has the same private footer-based
`bubbleStatusText` logic, consumed by the real outgoing
`messageBubbleAccessibilityLabel`. The test invokes that existing App function,
not a copy/evaluator, reflection, new visibility API or test-only formatter.
It deliberately retains the hand-built item/footer rather than substituting a
builder fixture: the original suite itself does not invoke a DTO builder.

The Android label additionally requires a DTO body. A small adapter supplies
literal `Test`, the envelope identity/date/status, outgoing direction, fresh
radio UUID, channel0 versus nil according to the fixture, and the same heard/
send counters; other irrelevant DTO defaults remain native defaults. This DTO
is adapter-only, not a claimed frozen makeMessage input. Exact full-label
equality is `Test, ` plus the independently selected resource in each method,
so missing, wrong or extra status text fails. No expected resource is chosen
by a candidate status predicate, and no expected value calls the formatter.
This proves the source status equality through its actual App consumer while
not pretending to mount Compose, inspect visible footer text or run TalkBack.

The existing visible `ChatTimeline.statusText` independently has the same four
branches and was read but is not exercised by these label-function tests.
No production discrepancy or repair is inferred. `WP-307` owns the original
view consumer; `WP-501` owns only this bounded acceptance addition.

## Local continuity and ownership

Local parent is public PR312 head
`9920c72bf76c28b7719ee3a08e88170ec3f059ff`, tree
`3086165625156f7e84c127fb8d8b49f1808c1864`. Observed actual main remains
`b8c6c610d5d4f5fe729ecd86ea89117aaebb9393`; no unmerged candidate is called main
and no main integration, branch/history rewrite or publication is performed.
PR312/head/base/body remain frozen. Its pending exact9920 hosted work and
closed controller result are not this local successor's execution.

Same stored attempt/session `d6056690-3a5d-453e-b67e-36866c676ac5` evolves
receipt134->135 through actual trusted `wp-owned` create/modify rules before
four exact owned paths: this evidence, the new test, one additive complete-suite
proposal and the owned deviation. Only the new test/evidence paths expand the
existing scope. All immutable identity/binding/authorization values, prior
capabilities/attributions and complete protected-lock records are preserved.
No permission machinery, manual ledger edit or release/recreate is introduced.

All 198 incumbent proposal groups/text, historical deviations and public9920
test/evidence blobs remain unchanged. Production, build/source sets/runners,
tools, native fixtures and generated accounting/review archives are untouched.
Canonical manifest remains
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
actual declared UTF-8 policy revision remains
`46a1aebeace81c8719901030e859569df4c2008e19cb841d80e887b5801077d8`.
Historical reservation digests remain immutable audit context.

## Declared verification and acceptance boundary

| Exact declared command | Actual local result |
| --- | --- |
| `python tools\android-port\controller\validate.py` | PASS: frozen source, ownership, dependencies and canonical manifest valid |
| `python tools\android-port\portmap.py` | PASS: provenance valid, not behavior or execution acceptance |
| `python android\parity-tests\semantic.py --check` | PASS: read-only joins include all four original methods exactly once as a complete source suite; no generated archives written |
| `python tools\android-port\controller\test_runner.py --tests android\parity-tests --quiet` | PASS: 108 discovered/run/passed, zero failures/errors/skips, 6.114s; controller fixtures only |
| `python tools\android-port\controller\ci.py preflight` | BLOCKED, exit2: explicit `--state/ANDROID_CI_STATE` absent; no invented state or manual Gradle bypass |
| `git diff --check` | PASS |

Required future execution is four distinct methods in
`com.meshcoreone.android.parity.MessageStatusTextOriginalPredicateTest`, through
the existing App JUnit4 source set/API31 en-US Robolectric runner. Four original
declarations are not four passing discoveries. Kotlin/Android compilation,
discovery/execution and independent review remain UNVERIFIED until actual
candidate evidence. Complete source-suite mapping is not full feature, visible
UI, API37, native/device/hardware, iOS or whole-WP acceptance.

**LOCAL ONLY; PUBLICATION HELD** for coordinator source review and concrete
continuity/clearance instructions. No push, PR/body mutation, CI dispatch/rerun,
official successful-log read, CI-result bundle, native/device/resource/probe/
allowance action or next-family launch. Acceptance references remain
`WP-501-behavior`, `WP-501-boundaries` and `WP-501-source-test-parity`;
WP-501/issue134 remain unfinished.
