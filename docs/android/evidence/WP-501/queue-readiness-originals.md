# WP-501: complete persisted queue-readiness originals

## Binding and actual status

**UNVERIFIED SOFTWARE SOURCE.** Three additional original readiness
destinations are implemented on the real persisted DM consumer boundary.
Neither their Kotlin compilation/execution/discovery nor that of the three
affected DM failure destinations has occurred. No 3/3, 6/6, all22-original
or whole-WP acceptance is claimed.

- Frozen reference: `db14559b39d32322b06477c6ae676112f583db50`.
- Direct implementation parent: `3fa9fcf3bf71ee1f9ea73ada8072594c427db4e4`.
- Actual main/base: `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
- Manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
- Trusted UTF-8 policy:
  `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
- Same WP-501/d605 attempt/session/branch/worktree, reservation88->89,
  owned modify admission for the two new Kotlin paths and this evidence.
- New readiness source:
  `f198a5b719e0223c6af66f96487851eef3b10d2a`.
- Extracted shared fixture:
  `fff5664ecf092e79f034e20487fc5dcfbbd108fb`.
- Affected failure-test wrapper:
  `77f12faf9d7da7f17f8f1c4cf023d169a8f8b352`.

Published12ce/automatic39543623108 does NOT contain either local increment.
Its healthy run remains immutable. Actual retained WP-503 native recovery
continues to exclude local Gradle/native admissions. No alternate compiler,
emulator restart, foreign process/lease action, rerun, dispatch, hook/runtime
change or merge-gate omission has been performed.

## Why this is a genuinely missing family

Read all three complete frozen readiness bodies at879-986, full
DMHarness/failure-mode/drain/teardown/setUpDMHarness, MessageService testing
helpers and constructors/defaults. Read both actual observers, SendQueue,
BLETransportOpenedSignal, DeviceConnectionState and the native snapshot/
ownership contracts. Compared all three old named SourceChatQueueTest
bodies plus their complete Harness/failingDM helpers.

Those old destinations use RecordingStore, pool cap0 and the shared native
fixture's different device/contact/defaults. The connected-only case also
lacks the original one-second parked observation. Labels and attempt-count
checks alone do not establish these source inputs and actual persisted
consumer actions. No old positive cohort is replayed here.

## Complete original predicates

Every case retains original pool cap2/baseDelay0.01 and default exponent2,
jitter0.8...1.2, message configuration and queue timeout30. The actual public
session has original timeout0.2 and complete SelfInfoAB/"TestNode", distinct
from Device22/"Test Radio". Contact33/"Test Contact" remains raw0/path255/
lastHeard0. Production MessageService creates "Hello"; independent complete
device/contact/message/pending DTOs are required before action and after
each park, not partial status/count proxies.

| Complete frozen original | New method and exact predicates |
| --- | --- |
| `observeConnectionState fires the trigger for an already-ready initial state` | `originalAlreadyReadyInitialStateWakesTheParkWithAnAlreadyFinishedStream`: perform the complete first transient drain/actual park, then re-observe native initial READY with an ALREADY CLOSED transition stream. Require the second full persisted attempt/park, six actual SEND_MESSAGE requests across both cap2 backoff cycles, two typed nested DeviceError3 park diagnostics and a completed observer. No new READY event can explain the initial-state wake. |
| `observeConnectionState keeps a drain parked through .connected and wakes it on .ready` | `originalConnectedRemainsParkedForOneSecondUntilTheReadyEdge`: install the observer with initial CONNECTED before hydration. After the initial real park, emit CONNECTED and preserve the literal one-second virtual observation: require original row/attempt1, complete PENDING message, queue1, exactly three actual sends and one typed park. Emit READY, finish the stream and require the complete second attempt/park, six sends, two typed parks and completed observation. |
| `connection-state ramp fires the trigger exactly once` | `originalDisconnectedConnectedSyncingReadyRampWakesExactlyOnce`: install initial DISCONNECTED observation, perform the first real park, then enqueue the exact four DISCONNECTED/CONNECTED/SYNCING/READY events in order before running the consumer. Finish the stream; require exactly attempt2/six sends/two typed parks. Preserve the original further one-second hold and require the same complete row/message/queue/wire/diagnostic state, with no third attempt or hidden trigger. |

Each park assertion requires the full original row with ONLY attemptCount
changed to1 or2, its actual original messageID/pendingID/radio/contactID,
sequence1 and every default/enqueue date; the full original PENDING message;
hasPendingSend true and queuedEnvelopeCount1. The wrapper also requires
the complete original device and contact partition, with only production
createPendingMessage's lastMessageDate update. Each actual park diagnostic
must be MessageServiceException(SessionError(DeviceError3)), not a timeout,
missing-contact drop, invalid generation or untouched readiness wait.

The source weak poll `(attemptCount ?? 0) >= 2` is not copied as an
absence-tolerant assertion. Required full rows, actual completed failures
and exact attempts prove the same source outcome. Extra native assertions
do not count as additional original cases or independent execution.

## Native lifetime/stream adaptation and a discovered fixture defect

Both generations share the extracted DirectQueueOriginalFixture rather
than duplicate literal seeds/session/Room/teardown code. The earlier
failure methods retain every action/assertion; only their fixture-call
name/imports change. Shared seed DTOs, independent frame, clocks, services,
error values, config defaults and nested finally cleanup are preserved.
The original singleton failure accessor remains singleton; readiness adds
an exact phase-filtered failure list and a parameterized full-row witness.

Native public queue hydration automatically installs an observer and gates
its FIRST send on current-generation READY. The Swift test queue has no
corresponding first-send gate. Therefore the native fixture injects one
additional READY after hydration has registered its readiness wait, before
the first actual error/park. This is consumed by that readiness wait.
The source observers for CONNECTED and DISCONNECTED are installed BEFORE
hydrate, just as in the source; the subsequent source events and original
one-second holds follow the first actual park. The extra first-send
lifetime input is not counted as one of the source post-park wake edges.

These are synthetic original app-state streams, NOT real connect/reconnect
observations. The started session/transport stay connected even while the
synthetic ramp contains DISCONNECTED. Every DISCONNECTED snapshot has
token NULL, as the actual native ConnectionSnapshot constructor requires;
all other rungs restore the same token. A null token is not a foreign
generation and does not invalidate the observer. The complete four-event
Channel stream preserves order; StateFlow is only the current ownership
snapshot, not an event-history substitute. Finishing closes actual current
channels and future subscriptions while retaining the current snapshot;
buffered transitions are drained, not discarded or synthesized.

Reading that constructor exposed a REAL defect in the unexecuted3fa
fixture: its unstarted case tried to construct DISCONNECTED with a
NON-NULL token. That violates the repository's existing init requirement
before the original body could run. This is a source-inspection finding,
NOT an observed Kotlin test failure. The shared fixture repairs it:
the unstarted fixture's additional native lifetime snapshot is CONNECTING
with its current token before hydration, then READY to enter the real
NotConnected park; actual session/transport remain disconnected, SelfInfo
absent and wire empty. This preserves the original unstarted source input
without bypassing or weakening any production snapshot invariant.

The immutable original3fa/e7dc implementation and its source-only results
are retained as historical UNVERIFIED evidence, NOT retargeted to these
bytes. Its earlier description of an initial DISCONNECTED lifetime
snapshot is superseded by this explicit repair. The affected failure class
requires actual execution with the new shared source; no prior pass exists
to inherit, and the unchanged published hydration4/4 is unrelated.

Dates remain fixed Unix1704067200 and UUID roles fresh/distinct. The
incidental jitter sample1.0 stays within the unchanged source range.
The original one-second holds are exact virtual1,000ms;300ms settlement
allows each literal error3/cap2 backoff cycle to reach a real park, not just
the top-of-drain counter bump. Real queue/message teardown reports, session
stop, Room close and owned-job cancellation/join remain mandatory.
No physical connection/process death/device/API37/backup claim follows.

## Verified immutable inputs

All14 frozen and20 native pins in
[the predecessor evidence](direct-queue-failure-originals.md#immutable-source-and-consumer-pins)
were individually rechecked against their literal Git objects and current
bytes. Frozen inputs equal db145; all20 native inputs equal actualmainDBB
AND directparent3fa. No production/App/scaffold input changed.

The five additionally inspected inputs were individually verified:

| Input | Immutable blob |
| --- | --- |
| Frozen `MC1Services/Sources/MC1Services/Services/BLETransportOpenedSignal.swift` | `1f5a8b2ba9b25ed8d78ad345187dfc6177410cfe` |
| Frozen `MC1Services/Sources/MC1Services/Services/SendQueue.swift` | `ef9ee480690cf70f8545e9366614aba0ef3e036a` |
| Frozen `MC1Services/Sources/MC1Services/Connection/DeviceConnectionState.swift` | `7cbcf5ba61765e4b2dd6d2ff77dc769e00c96870` |
| Actualmain/parent `android/core/contracts/.../domain/ConnectionContracts.kt` | `eaac312ed47f2a6218e826549b5f5a275a0c8edb` |
| Actualmain/parent `android/core/services/.../messaging/BLETransportOpenedSignal.kt` | `eb018aff862df0105a844f6786d77533e0ee6718` |

The parent-only old MessagingTestSupport.kt remains unchanged; it was not
misclassified as equal to main or used in place of the real Room fixture.
No generated semantic archive, historical execution receipt or review
binding was refreshed/promoted to claim these original cases.

## Required future execution and actual source validation

This declared invocation is **NOT RUN**. Both selectors are necessary:
the three new originals and the three actually affected, previously
unexecuted failure destinations now depend on the shared fixture.
This is not replay of an already healthy cohort.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.QueueReadinessOriginalPredicateTest',
    '--tests', 'com.meshcoreone.android.parity.DirectQueueFailureOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

Positive discovery must enumerate all six exact method identities and
bind actual executed shared/wrapper/readiness bytes/base/reservation/lane,
with zero failures/errors/skips. Source method inventory is not discovery.
Actual isolated candidate CI may provide execution while the native hold
persists; protected exact-candidate merge verification remains required.

| Read-only source command | Result for this increment |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

All six actually completed successfully in one chained source-only
invocation at directparent3fa with these five files staged.
`git diff --cached --check` also passed. The table was updated only after
the actual successful commands; the three Kotlin blob bindings above
are unchanged. Historical semantic proposals remain historical, not
accepted original-family evidence.

An initial ad-hoc pre-commit guard incorrectly compared the raw Git JSON
SHA256 `8376af85d2032ad45628f82489c71dec218ee330cbc65a33c9100deb564b7c3f`
with the manifest's canonical digest and stopped BEFORE committing.
The existing trusted Manifest.sha256 is digest(data), not a raw-file hash.
Strict UTF-8 Git JSON equals the loaded manifest; its actual canonical
977b digest and e8 policy both agree. The guard is corrected to use that
existing algorithm, with no hash exception or authority change.

Source pin comparisons above and source validation do not establish
Kotlin compilation, test discovery, consumer execution, independent
acceptance, protected review or any whole-WP completion.
