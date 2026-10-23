# WP-501 existing mounted reaction fixture wall-clock repair

## Actual failed candidate, not a passing or complete run

PR312 head `a4f2a39cdcb6b89a4f1d69dddaf58c882bf3dc2a`, base actual main
`ceedad8d5d693acdf18d927900a89850471a3a7e`, own run39992083594 attempt1:
the coordinator centrally consumed failed Linux job120547947979 once and
reported `IncomingReactionSyncTest.meshCoreOpenV3UsesTheStoredChannelTargetWithoutSavingAnOrdinaryMessage`
expected1/actual0 stored reactions, direct=false/pending=false/hash=aa61,
empty gaps/diagnostics. App1397 had one failure; remaining required module
reports were incomplete. This worker did not reread/replay that official log.
No completed Android-test compiler, full-suite, new15-method discovery or
acceptance claim follows from this failed run.

## Completion trace: not an assumed asynchronous persistence race

The existing test calls real `MessagePollingService.pollAllMessages()`.
Its generation-owned `drainRadio` invokes `dispatchPending`, which awaits
`deliverPending` under its dispatch mutex. `invokeConsumer` awaits the actual
installed SyncCoordinator handler; it does not launch detached persistence.
Residual deliveries also drain before poll completion. Handler counts unwind
in finally, and dispatch failures are parked/reported rather than completed
success. Adding an arbitrary delay or waiting for a reaction row would not
repair a missing v3 target match.

`RadioSessionContainer.buildSyncDependencies` forwards the real ReactionService
via SyncReactionAdapter. SyncCoordinator wires handlers before monitoring;
ordinary target ingestion awaits Room save, then indexes the target and
processes native pending reactions before returning. A channel v3 reaction
instead takes `handleMCOChannelReaction`: it bypasses cache/pending matching,
awaits Room `fetchChannelMessageCandidates` within receiveTime +/-300seconds,
checks literal Dart hash against each candidate's reactionTimestamp/sender/
text, then awaits `persistReactionIfNew`. That awaits the real ReactionService
saveReaction, fetchReactions and updateMessageReactionSummary. The Room DAO
filters persisted timestamp with BETWEEN; no index or UI observer is needed
for the v3 DB candidate path. No missing completion barrier is established.

Frozen `SyncCoordinator+ReactionHandlers.swift` at
`db14559b39d32322b06477c6ae676112f583db50` has the same bounded v3 lookup,
no cache/pending fallback and awaited persistence. V1 has its independent wire
timestamp anchor; native pending channel/DM reactions use their actual queues/
indices. This explains why their success cannot prove the v3 clock input.

## Concrete fixture defect and bounded repair

The App fixture still injected wall time as
`EPOCH + testScheduler.currentTime`, but its target wire timestamp1704067200
and independent v3 `r:aa61:00` packet remain fixed. Real Room operations run
off the test scheduler. `runTest` timer advancement while awaiting those
operations is not an explicit wire arrival. Any scheduler offset over300s
excludes the fixed target from the genuine v3 DB window while its stored
timestamp/hash remain correct; empty gaps/diagnostics do not expose this
ordinary no-match path. The failed hosted log did not measure its wall/
scheduler values, so its exact unseen drift is not claimed as observed.

This is the same clock-input defect already repaired in
`IncomingReactionAcceptanceTest`, documented with historical controlled
negative/positive execution in `reaction-clock-determinism.md`. Those results
are prior art, not fresh execution or acceptance of this App fixture.

Only existing `IncomingReactionSyncTest.assertMountedReaction` changes:
explicit receiveTime starts at EPOCH and advances once to EPOCH+1s when the
reaction arrives. Pending-before-target cases retain that later receiveTime,
just like the existing parity fixture. `elapsed()` and `sleep()` remain
scheduler-driven for actual monotonic deadlines/backoff. Before constructing
any session, the shared helper advances the otherwise empty scheduler301000ms,
then asserts its offset and wall==EPOCH. This deterministically rejects the
old timer-derived wall mapping without advancing an active watchdog or
waiting in real time; it is a fixture invariant, not a new original family.
The final wall==EPOCH+1s assertion and wall/scheduler/target failure diagnostics
make a recurrence observable. No wait, eventual reaction polling, fake query,
hand-inserted reaction or product/window/parser/hash change is introduced.

All four original JUnit method bodies and wire frames remain byte-identical:
pending channel, pending DM, stored-target v3 channel and stored-target v1 DM.
Every incumbent row/status/timestamp guard and exact reaction message/radio/
emoji/sender/rawText/hash/contact/channel/summary assertion remains, including
one target row/no ordinary reaction message, real channel secret setup,
handler wiring, empty reported gaps and finally container close. The
controlled scheduler offset strengthens the same four methods rather than
adding extra original credit or a new suite.

## Admission and candidate continuity

Same WP501/D605 owner evolves r136->137 through trusted
`app-build-launcher/add-direct-support-wiring` for the existing App test's
injected clock wiring, then r137->138 through `wp-owned/modify` for this new
evidence and the owned deviation. The initially considered
`add-regression-fixture-or-test` operation is not admitted for this App path
and was not used; no capability/policy framework is changed or bypassed.
Immutable identities/bindings/authorization, prior capability attribution and
every complete protected-lock record remain unchanged.

All eight reviewed candidate paths and all eleven PR311 incoming entries are
retained byte-for-byte except the directly necessary additive WP501 deviation.
Prior proposal groups/order/top metadata, status fixtures, region assertions,
production/harness/build/runner/tools/native-fixture/accounting trees remain
unchanged. Parent is exact failed a4f2/baseceed; no other main integration.
Frozen source, canonical manifest977b9 and actual UTF-8 policy46a1 stay fixed;
historical lease metadata is immutable audit context.

## Verification and publication boundary

| Exact declared command | Actual local result |
| --- | --- |
| `python tools\android-port\controller\validate.py` | PASS: source/ownership/dependencies; canonical manifest977b9 unchanged |
| `python tools\android-port\portmap.py` | PASS: 1879 provenance entries, not behavior/discovery |
| `python android\parity-tests\semantic.py --check` | PASS: read-only mappings; no archive/accounting refresh |
| `python tools\android-port\controller\test_runner.py --tests android\parity-tests --quiet` | PASS: 108 discovered/run/passed, zero failures/errors/skips, 5.952s; controller fixtures only |
| `python tools\android-port\controller\ci.py preflight` | BLOCKED exit2: explicit `--state/ANDROID_CI_STATE` absent |
| `git diff --check` | PASS |

No state fabrication, manual Gradle or bypass. New fixture execution and all
four method outcomes remain UNVERIFIED until actual corrected-candidate
evidence. Source tracing and the deterministic epoch guard are not a measured
Kotlin reproduction; the old mapping would violate the independent wall
assertion at the deliberate301s offset, but no fresh negative-control
execution is claimed.

Ordinary non-force publication to existing PR312 is authorized after current
base/receipt/frozen-input/clean-tree checks; unchanged advisory-hook failure
is explicitly UNVERIFIED, not green. Root retains body edits, source review
and exact-new-head CI consumption. No unchanged-head rerun, manual dispatch,
official-log reread, CI-result bundle, new family/worker, native/device/
resource/probe/allowance or whole-WP acceptance is introduced.
