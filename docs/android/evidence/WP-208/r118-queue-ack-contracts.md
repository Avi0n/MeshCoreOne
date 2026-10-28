# WP-208 R118 queue/ACK contract repair

Repository: `cbattlegear/MeshCoreOne-Android`. Issue: #388. Owner:
`services-porter`. Session: `0872d489-506f-4cd4-984f-3715cd4a8a8c`.
Branch: `cbattlegear-queue-ack-contracts`.
Implementation base: `9eb5b343254a655e49c34134afeec294ca623df1`.
Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
The PR's live head and exact-commit hosted checks identify the candidate; this
file is a source/acceptance map, not a second reproducible CI-result bundle.

## Authority and prerequisite

The user assigned the oldest unclaimed issue and explicitly chose recoverable
Android failure policy rather than reproducing Swift's failure-time tracking
loss/hydration latch. The three observation windows preserve source durations
using the already-injected virtual clock, with no arbitrary wall-clock sleeps.
WP-207 prerequisite #449 is merged as
`91194f3d75b28131d297a78ef604367ded13e52c`, an ancestor of this base.

Automatic `reserve.py claim` returned the historical WP-208 source/base/manifest/
backend mismatch. Inspection identified an advisory historical record, not a
live semantic collision. It was not released, recreated, impersonated or edited.
Trusted `reserve.py admit` accepted modifications to MessageService,
SourceChatQueueTest, SourceAckTest and this WP's deviations, and creation of
RecoveryPolicyTest and this map, under `wp-owned`. Admission rejected modifying
the separately owned MessagingRoomIntegrationTest path. That path is untouched;
#452 tracks the required support admission and supervised verification binding.

## Seven direct original families

All names below refer to the unchanged pinned Swift files
`MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift`
and `MessageServiceACKTests.swift`. Original family identities/expectations are
retained, not renamed into supplemental coverage.

| Concern / original family | Native assertion |
| --- | --- |
| CM-05: transient send error preserves the persisted row while parked on the trigger | SourceChatQueueTest advances 200ms after actual firmware error/park and asserts the exact pending row, PENDING status, one attempt and one packet. |
| CM-05: Transient DM send error must not broadcast .failed | Original pool attemptCap=2/baseDelay=0.01; continuous generation-tagged subscription through 300ms, three wire writes, one persisted queue attempt, no Failed event and PENDING row. |
| CM-05: observeConnectionState keeps a drain parked through .connected and wakes it on .ready | CONNECTED remains parked for 1000ms without a second packet/attempt; READY then advances the actual persisted attempt to two. The production 30-second fallback is unchanged. |
| CM-08: hydrate runs once per service instance | First row drains; after inserting a second row, repeated hydrate plus drain completion neither rereads persistence nor schedules/deletes that row. Failed loads remain a distinct retryable native policy. |
| CM-08: checkExpiredAcks marks expired ACK as failed | Exact committed FAILED row, one failure notification, zero retained tracking. Supplemental failure path keeps only failed entries and retries them without duplicate notifications. |
| CM-08: failAllPendingMessages fails all non-delivered and broadcasts .failed | Both original rows commit FAILED, exactly their two identities notify, and tracking becomes empty. Supplemental multi-error sweep retains failed entries, processes the later healthy entry, throws aggregate failure, then reconciles retained entries on retry. |
| CM-08: failAllPendingMessages does not downgrade or notify on a delivered DB row | DELIVERED remains unchanged, no failed event, stale undelivered tracking retires. Confirmed but not yet committed ACKs remain retained for reconciliation rather than being failed. |

`RecoveryPolicyTest` adds ten production-path cases: expiry error isolation/
retry; fail-all two-error isolation/aggregate/retry; unreconciled teardown cause;
cancelled fail-all; successor ownership during a suspended write; real raw ACK
delivery after a storage fault; failed hydration retry/success latch; and
observer cancellation during a shared hydration load; and fail-all persistence
failure followed independently by ACK delivery or expiry. Both latter paths
must retire tracking, emit exactly the authoritative status, avoid another wire
send and produce a complete teardown report with the recovered diagnostic gone.

These are deterministic real MessageService/ChatSendQueueService/MeshCoreSession
tests with RecordingStore. They do not prove physical persistence. Unchanged
`MessagingRoomIntegrationTest` independently exercises actual native-SQLite Room
consumers, including `actualRoomExpiryIsolatesOneStorageFailureAndCanRecoverItsRetainedEntry`,
`malformedRoomEnvelopeDoesNotStopValidLaterHydrationOrLoseItsRepairableRow`,
`actualHydrationDrainsOneRadioInFIFOOrderAndLeavesTheOtherPartitionUntouched`,
`freshAndRecoveredAttemptsUseTheActualCommittedCounterAndWireTimestamp`, and
`unacknowledgedResendRetiredByFailAllDoesNotIncrementTheActualRoomCount`.
Those existing consumers do not substitute for the new support-test obligations
tracked in #452 or for device/process-death/hardware evidence.

## Verification boundaries

WP-208's macro acceptance remains `configured=false`, `commands=[]`; this repair
does not manufacture a full-WP completion receipt. Existing CI-derived
affected-scaffold execution selects Services and downstream consumers. Its
exact-commit hosted job/log is authoritative, not older WP-208 counts or this
document.

Local checks before publication:

- `python tools\android-port\controller\validate.py`: valid; 64 WPs, 178 edges,
  unchanged seven human gates.
- `python tools\android-port\portmap.py`: valid, including the new pinned-source
  headers; source/header accounting is not runtime acceptance.
- `git diff db14559b39d32322b06477c6ae676112f583db50 --` the four original
  queue/ACK source/test files: no drift.
- `python android\scaffold\check_environment.py` in an allowlisted child:
  BLOCKED, missing JAVA_HOME and Android SDK environment. The initial unisolated
  invocation correctly rejected inherited non-allowlisted variable names.

No local JVM/Android, fresh Swift, device, signing, release, human or legal
verification is claimed. Missing local readiness does not authorize bypassing
the existing hard Gradle execution lock (#440). Feature publication remains an
explicit locally unverified candidate until exact-head hosted checks complete.
