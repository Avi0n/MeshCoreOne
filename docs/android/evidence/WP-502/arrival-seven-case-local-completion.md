# Seven original MessagePathArrival cases: local-only remainder

WP-502, cbattlegear/MeshCoreOne-Android; primary source ownership stays WP309.
Frozen reference: `db14559b39d32322b06477c6ae676112f583db50`.
LOCAL-ONLY parent/public head: `86137fa17ad38e5f462df5e4192205bde4c1eee2`;
underlying main base: `b8c6c610d5d4f5fe729ecd86ea89117aaebb9393`.
PR313 is held at its existing exact head/body while its own CI runs.
Nothing in that run executes these four new local methods.

## Complete original file mapping

All seven frozen bodies and the three incomingMessage/outgoingMessage/makeRepeat
helpers were re-read, alongside actual assembly, availability, details, holder
and mounted rendering/copy consumers. Earlier evidence remains unchanged.

| Frozen MessagePathArrivalTests original | Actual consumer method in MessagePathComposeTest |
| --- | --- |
| Incoming with0 extras is one first arrival | Existing `incomingWithoutExtrasRetainsItsSingleFirstArrivalAndRealCanonicalHopAcrossRecomposition`: count1/first/messageID/pathAA/scalar1; already merged, unchanged. |
| Incoming with2 extras is canonical then extras in time order | Published861 `incomingExtrasSelectTheirOriginalTimeOrderRatherThanRepositoryOrderAcrossRecomposition`: all7 predicates, exact original BB+5/CC+1 input and real First/CC/BB selection. Public CI is not this local follow-on's verification. |
| Incoming empty pathNodes with one extra still has first0-hop | Existing `nilPathFirstArrivalRemainsZeroHopWhenSelectingItsEchoAndRecomposing`: count2/first/empty canonical bytes/zeroHop/not-unavailable with actual First/AA echo selection; merged302, unchanged. |
| Outgoing with2 repeats has no first arrival | NEW `outgoingOriginalTwoEchoesHaveNoCanonicalArrivalAndSelectAndCopyTheirOwnPaths`: original11/22 echoes/heard2, count2/every-not-first/scalar2, real #1/#2/retained#2/#1 and actual selected-path clipboard. |
| Outgoing identical-path echoes stay three arrivals | NEW `outgoingOriginalIdenticalEchoesRemainThreeSelectableObservationsWithoutInventedDifferences`: original count3 and set of ALL pathNodes equals singleton42; three distinct observation IDs/no dedup, actual #1/#2/#3/retained#3/#1 selection and clipboard42. |
| Outgoing with pathNodes nil still reads hops from arrival | NEW `outgoingOriginalNilMessagePathUsesTheTwoHopEchoForRealDetailsAndClipboard`: explicit nil, nonempty first arrival, not-unavailable, exact[A3,7F] hexes and A3,7F clipboard; real two-hop details/copy survive recomposition, no selector for one arrival. |
| Incoming0-hop canonical is not Path Unavailable | NEW `incomingOriginalNonNilEmptyPathStaysZeroHopWithoutForcingAnUnavailableDisclosureOrRead`: explicit nonnil-empty input/length0/heard0/no extras, required first arrival, zeroHop/not-unavailable/empty hops; actual source-faithful no-disclosure/no-copy/no-read route, metadata and unchanged clipboard through recomposition. |

This completes the authored original input/predicate bundles for the reviewed
file, NOT executed source-file parity or whole-WP acceptance. No original row
is dropped or replaced by a filename/count/header assertion. Inventory/proposal
and primary test surfaces are untouched; no duplicate primary credits.

## Exact helper values and deterministic adaptation

Outgoing helper retains contact=nil/channel0/text=out/timestamp1700000000,
outgoing/sent/plain/ack=nil/pathLength0/SNR=nil/pathNodes=nil/senderPrefix=nil/
senderName=nil/read=true/reply=nil/RTT=nil/heard2,3,1 respectively/retry0/maxRetry0.
Message createdAt Date() becomes fixed Unix1700000000. IDs use distinct
`UUID(502,720..730)` values: radio720; messages721/724/728/730; echoes722/723,
725/726/727 and729. UUID formatting/serialization is not changed.

Every original echo retains its messageID/path bytes/pathLength (default1,
explicit2 for A37F), SNR7/RSSI-80/rxLog=nil. Default Date() observations become
Unix1700000001; the second11/22 echo is Unix1700000002, preserving creation
order deterministically without a delay. ALL THREE identical42 echoes share
EXACTLY the same Unix1700000001, path42, length1, SNR7, RSSI-80 and messageID.
Only their distinct observation UUIDs differ. A direct normalized-DTO assertion
proves no differentiating payload/signal/time fields were invented.

Incoming nonnil-empty retains full incoming helper contact=nil/channel0/text=flood/
timestamp1700000000/incoming/delivered/plain/ack=nil/pathLength0/SNR8.5/
pathNodes=Bytes.EMPTY/senderPrefix=nil/senderName=Alice/read=true/reply=nil/
RTT=nil/heard0/retry0/maxRetry0. Date() is fixed Unix1700000000. No echo,
contact, location, route, sender or heard count is added to unlock a control.

## Actual consumers and source-constrained negative case

Three outgoing cases use existing production MessageActionsRoute and real
MessageActionsStateHolder with the unchanged App source registration. The only
held port is the actual repeat read; true loading/null repeats/no tabs/no path
copy is required before release. Correct radio/message IDs and exactly one
contacts/discoveredNodes/repeats sequence are asserted. The original successful
list and each DTO remain identical in state, not an empty failure fallback.

Direct original assertions use production assembly of that ACTUAL loaded list.
Additional ID/path/length/time/SNR/RSSI/not-first checks cover every echo.
The state snapshot remains the same across real clicks, clipboard writes and
an observed route-tag recomposition. Tabs expose the actual Role.Tab, Selected,
48dp action, text and count; no direct selection callback or parallel evaluator.
The three identical echoes retain three tabs and three raw repeat rows despite
identical visible hops/copy output.

Actual localized path-copy control/hint and48dp target must be present; click
writes the Android clipboard, not MessageAction.Copy/message text. Exact output
is11,22,42 or A3,7F, never the display arrow string. The strict unchanged
mounted-text oracle verifies literal2f/LTR/full UTF16/line/paint/native-ink/
owning-window/viewport containment for tabs, hop/path text and copy buttons.

For incoming empty/no extras, frozen MessageActionAvailability.canViewPath
requires NONEMPTY bytes, and showsPathDetail also needs extras. Frozen
MessageActionsSheet.loadPathExtras guards the same availability; actual
ActionsDetailsSection excludes the disclosure. Android matches these gates.
Therefore a real source-faithful route must perform ZERO extra reads, never
enter loading, leave repeats null, expose no tabs/path-copy/disclosure/unavailable
error and keep the clipboard untouched. The test asserts the original assembler
predicates directly against the exact empty original input, then verifies real
visible flood message/zero-hop/SNR metadata and unchanged state across
recomposition. It does NOT pretend to load an empty list or render private
PathHopList for an unavailable disclosure. No production repair is appropriate.
This is an explicit consumer disposition, not relaxed original expectations.

All reaction/block/mute/action ports fail loudly if invoked. No original
helper, test assertion, deadline, golden, product behavior or native oracle is
changed. Native numbered outgoing tabs remain existing adaptation; capsule
subtitles/offsets, map, other APIs, physical/spoken TalkBack and whole-WP
acceptance are not claimed.

## Scope, preservation and verification

Same canonical actor/attempt `3bc8c1b8-20ec-4647-88ae-db3775268489` (runtime
fb112832) uses trusted owned modify/create capabilities. Existing two paths
are idempotent at69; adding this evidence path returns actual revision70.
The increment is additive: four tests plus private fixture-only wiring helpers
inside the existing class, one deviation section and this evidence. Removing
only these additions restores every public861 test/section/helper byte.
No new runner/framework or public surface. Full protected runtime/hook/lock
state is guarded separately; immutable audit identity is unchanged.

Frozen source, canonical manifest
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8` and policy
`46a1aebeace81c8719901030e859569df4c2008e19cb841d80e887b5801077d8` remain
unchanged. Public313, independent311/312 and every primary provenance blob
remain untouched. No main integration, push, body edit or second CI bundle.

Actual local declared checks:

- `python -B .\tools\android-port\portmap.py`: PASS, exit0.
- `python -B .\tools\android-port\controller\validate.py`: PASS, exit0.
- `python -B .\tools\android-port\controller\verification_config.py --check`:
  PASS, exit0.
- `git diff --check` and `git diff --cached --check`: PASS, exit0.
- `python -B .\tools\android-port\controller\ci.py preflight`: BLOCKED, exit2,
  `Explicit --state/ANDROID_CI_STATE is required; failed cloud setup is not readiness`.

Final commit/show-check identity and outcomes accompany the handoff, not a
duplicate CI bundle. The missing explicit state is not evidence of missing
Linux tooling. New Kotlin compilation/discovery/execution remains UNVERIFIED;
no earlier public861 controller967/scoped-codec or pending Linux result is
borrowed. No manual Gradle/native/device/probe/install/guest/APK campaign,
allocation/allowance/private resource, dependency/pin/runtime/toolchain/policy
change, dispatch/rerun or merge. Stop for coordinator source review and explicit
publication clearance. Full parent/WP task remains incomplete.
