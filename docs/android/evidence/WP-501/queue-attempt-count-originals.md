# Complete persisted queue attempt-count originals: unexecuted source

This increment follows LOCAL `586fcc695ca7d1fc8dd8f5e957c277a4dd8ae698`.
It adds the three COMPLETE frozen
`ChatSendQueueServiceAttemptCountTests.swift` bodies through actual
Room/production queue/MessageService, not the incumbent
`RecordingStore`/started-session/cap0 approximations. Kotlin is **NOT
COMPILED / NOT EXECUTED / NOT DISCOVERED**. Three named methods are not
3/3 executions, accepted originals or a whole-WP verdict.

Actual main/base remains `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
Reference remains `db14559b39d32322b06477c6ae676112f583db50`;
canonical manifest remains
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`,
trusted strict-UTF8 policy
`e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
Same WP-501 owner/attempt/session
`d6056690-3a5d-453e-b67e-36866c676ac5` evolved91->92 through the existing
typed capability, without changing historical authorization prose.
The prior readiness/failure/channel files and all production/App/scaffold
bytes are unchanged by this increment.

Published `12ce24a5395dc86399f4b724091b0a48676ab05c` and actual automatic
run39543623108's all-six SUCCESS remain12ce-only. No successor is published
by this source increment; pending bounded comparisons are not silently
inherited. The retained WP-503 native-execution lease
`1666502a-0420-46ef-930f-e78651ba8b9f:comparator:2be164ec007a4693b219e85f3fc5bb7b`
still excludes local Gradle/compiler/native operations. No alternate
compiler, guest/host/restart/reentry/release, CI dispatch or rerun was used.

## Complete fixture and production consumer

All three bodies share the full frozen QueueHarness/setup. Literal
Device32x01/"Test Device" and Contact32x02/"Test Contact"/lastHeard0 retain
all constructor defaults, including raw type0 and path255. The actual
**Device.radioID**, deliberately distinct from Device.id, owns contact,
message, pending row and token. This is NOT the different source family's
Device.id-based unstarted fixture. Device/contact full DTOs are guarded
before the operation and all surviving device/contact/channel partitions
are independently fetched afterward.

The real production MessageService creates `"Hello"`. A full independent
MessageDTO witness requires OUTGOING/PENDING/plain, creation/sort dates,
sendCount1, no ACK/retry/path/preview/reaction/region metadata and every
other source default before the real persistence API pins its wire
timestamp to exactly one hour earlier. The pinned full persisted DTO is
checked, not copied from returned output as an expectation. Source
updateMessageTimestamp changes only timestamp; outgoing createdAt/sortDate
and contact lastMessageDate stay at the original creation time.

The complete upserted PendingSendDTO is sequence1, fresh ID, actual radio,
original message/contact, DM, nil channel, isResend=false, empty messageText,
messageTimestamp0, nil localNodeName, enqueuedAt Date and attemptCount0
or1. Its full returned/persisted value and exact DirectMessageEnvelope
conversion are guarded. None of these bodies calls warmUp or purges rows;
the helper's true radio ownership is preserved without inventing a purge.
No legacy-null attempt-count test is claimed.

The actual session is UNSTARTED/disconnected with no SelfInfo: no startup
handshake, zero connects/readers/wire commands, and an unexpected-command
fixture error. Native adds only the required current-token CONNECTING
lifetime snapshot followed by logical READY after hydrate/readiness
registration. The broadcast uses independent closeable subscriptions.
These are disclosed additional native lifetime inputs; they are NOT a
connected radio or observed physical readiness. Without them the native
consumer would simply wait before reaching the original attempt branch.

Real queue hydrate converts/pushes the persisted row. Actual
preflightAndBump reads it and calls RoomPendingSendRepository's real
transaction/DAO. Its returned post-bump count selects preserveTimestamp
exactly as count>1. Actual MessageService.sendPendingClaim/queuedDM stamps
a fresh timestamp BEFORE retryLoop's identity check; therefore the
unstarted input still witnesses the source fresh-vs-preserved branch.
The subsequent exact typed NotConnected diagnostic proves a genuine
MessageService park, rather than an unchanged readiness wait. Full row,
message, pending membership and queue count1 remain required.

Default session timeout5, MessageService maxAttempts5/floodAttempts1,
floodAfter4, pool cap3/base0.5/exponent2/jitter0.8...1.2 and queue wait30
remain untouched. No poolcap0, immediate send-success substitute or
replacement retry loop is used. The early identity failure does not claim
the unused pool/jitter paths were executed.

## Three complete original predicates

Class `com.meshcoreone.android.parity.QueueAttemptCountOriginalPredicateTest`
contains exactly these three future execution identities:

| Frozen original | New destination and required predicate |
| --- | --- |
| `fresh send: row with attemptCount=0 first drain uses fresh timestamp` | `originalFreshRowCommitsAttemptOneAndReplacesTheOneHourOldTimestamp`: hydrate0; preserve source polling of BOTH attempt1 and a different wire timestamp; then require the full sole pending row with attempt1 and full persisted message with current timestamp, exact typed NotConnected park, queue1 and no wire. |
| `process restart: row with attemptCount=1 rehydrates with preserveTimestamp=true` | `originalPriorAttemptRowCommitsAttemptTwoAndPreservesTheOneHourOldTimestamp`: seed prior attempt1; hydrate/poll for attempt2; require the full row2 and complete message with the EXACT original one-hour-old timestamp; same real typed park and no wire. This source simulates a restart window with a preseeded row; it does NOT kill/relaunch any OS process. |
| `bump failure parks envelope, preserves row, does not call sendPendingDirectMessage` | `originalRealBumpFailurePreservesTheCompleteRowTimestampAndPendingBubble`: real native pre-update storage failure; exact source500ms observation; require sole complete row0, unchanged one-hour-old full message/PENDING, queue1, zero wire and the actual typed bump-operation failure, not a fake store callback. |

The first two use the frozen TestPolling helper's ten-second deadline and
ten-millisecond interval, translated to virtual monotonic time. The first
poll checks both effects, not just count1 while a stamp is still pending.
The second polls count2, then checks the preserved complete message just
as the source does. A final check at the deadline is permitted; expiry
fails explicitly. The third's literal500ms is advanced virtually, not
replaced by one of those successful-drain polls.

Incidental Date() samples are fixed to Unix1704067200 and fresh UUID roles
remain distinct. The pinned wire timestamp is literally1704063600:
the first case additionally requires their exact3,600-second separation.
Those substitutions are not independent date/UUID/backup or mesh-dedup
oracle evidence.

## Real pre-mutation bump failure and teardown

The source DEBUG hook throws FakeSaveFailure at the beginning of every
subsequent incrementPendingSendAttemptCount call, BEFORE fetching or
mutating. The native adaptation uses a real test-only SQLite BEFORE
UPDATE OF attemptCount trigger on the isolated fixture's pending_sends,
guarded by OLD.attemptCount0/NEW.attemptCount1. It raises FAIL with literal
`wp501_attempt_bump`. There is exactly one pending row. Failure leaves0,
so every attempted bump of that row remains guarded. No product fault
hook, repository replacement or product source change is added.

Native preflight/repository reads precede this trigger, unlike the source
hook; the failing update itself never mutates the row. This preserves the
source's load-bearing BEFORE-mutation failure position, not the unrelated
post-staging batch rollback adaptation. The assertion requires
PersistenceStoreException/SaveFailed("incrementPendingSendAttemptCount")
with actual SQLiteConstraintException and marker text, plus independently
fetched unchanged full row/message. Real parkBeforeSend remaps PENDING.
The old pinned timestamp is especially load-bearing: entering the fresh
MessageService branch would stamp current time even though the protocol
session is unstarted. Zero wire alone would NOT prove that branch was
avoided.

The native queue intentionally retains this typed storage issue in its
STOP_SERVICES teardown report. The test requires exactly one issue with
the SAME park failure identity. It does not suppress or pretend complete
teardown. Other cases require a complete queue report. All three still
require a complete MessageService close, session stop, store close,
owned-job cancellation/join, trigger drop and actual Room close through
nested finally blocks. The retained issue is not a leaked executor or
permission to abandon cleanup.

## Individually verified immutable inputs

All14 frozen inputs below were checked against their literal db145 Git
objects AND current bytes. Prefixes S/T mean MC1Services source/test roots.
No source drift was found.

| Frozen input | Git blob |
| --- | --- |
| T Services/ChatSendQueueServiceAttemptCountTests.swift | `9bc59c308a6ca57f83728c90381643cc1e66f213` |
| T Helpers/TestPolling.swift | `68e37d91e78676a2ee7f1a7eeded430accd1a6a0` |
| S Services/ChatSendQueueService.swift | `1fdb6cbe40577887d01a37e6063f0eec89ab91c7` |
| S Services/MessageService+SendDM.swift | `0d61fe6649cf3be2be29e75a0ad77e483ccb8482` |
| S Services/MessageServiceConfig.swift | `ebfbaa0043027149eebeb82865ab138a7dd630e5` |
| S Services/PersistenceStore+PendingSends.swift | `f8d3bb96d79355a39ae88da292eec70299336439` |
| S Services/PersistenceStore+Messages.swift | `02c50a9a0fda2aaf6b719a5fa410a0c5a0413c7f` |
| S Models/Device.swift | `823bee3ced673712a51bfa5332dbba81cd4dfed5` |
| S Models/Contact.swift | `7b6ecd2e224c1b1651edeba8a0019426881e7db4` |
| S Models/Message.swift | `27caa780e5d8ee1dcf4fd5dab661ef9945a5fe26` |
| S Models/PendingSend.swift | `26836068d071cab8baebf3d202577b88d06aa9c2` |
| S Models/PendingSendEnvelope.swift | `c9035ceec1ddd579dae90dc6691430d10f2e2367` |
| S Models/DirectMessageEnvelope.swift | `9f71b9ff1521be21ff89340993c5c7d8d73c8d21` |
| MeshCore/Sources/MeshCore/Session/SessionConfiguration.swift | `8da533669bcc84529b2f190939c4eb5cfb3a8944` |

These25 actual native inputs individually equal actual mainDBB,
directparent586 and current bytes. Prefixes are under android.

| Actual native input | Git blob |
| --- | --- |
| app test container/ContainerTestSupport.kt | `2ffc6cdf5870f412f7f8770ae97230c3f7f7eef4` |
| core/data repository/RoomPersistenceStore.kt | `de1c03fe01659660ee4628bd804a7b9b565f2d3c` |
| core/data repository/RoomRepositoryContext.kt | `85d5593ac6f45783acbefc5efeabb58a24f111d4` |
| core/data repository/DeviceRepository.kt | `9e069c60b5f696a970716ff9c51a1fb99bc27252` |
| core/data repository/ContactRepository.kt | `8da5513b403198a44f673addf443c5411b58a80c` |
| core/data repository/MessageRepository.kt | `937380545fe91d5b77e4a1552be0180f34b8612f` |
| core/data repository/PendingSendRepository.kt | `15a01dcacbfbacd69526a5e6e126793f73eea895` |
| core/database MessageDaos.kt | `082ea7f2a58a3ea02fbbb7f7c1863ec4c841cac5` |
| core/database MessageEntities.kt | `903e0d6a53d64a0ca19ef32841bca41803ad747d` |
| core/services messaging/ChatSendQueueService.kt | `e76b382895677f320d106022326ac952d56d5958` |
| core/services messaging/MessageService.kt | `1236ce36bfcb2d0952b5e4bd9150f9f071f6ff82` |
| core/services messaging/MessageServiceConfig.kt | `89aba3d1de38ea86d43e65a5ec4d6a058f536e78` |
| core/services messaging/SendQueue.kt | `532f9b85d0a49c402d4bb80ede5864fb0a3c9592` |
| core/services messaging/MessagingOwnership.kt | `df15e2f6276956411143aedf17a7dd215411b0c1` |
| core/protocol session/MeshCoreSession.kt | `3e98cc221bca2af76c273a2e2f88ec005b0f2f29` |
| core/protocol session/SessionCore.kt | `d6378350d45cb25e8b452e0552418c1352c37469` |
| core/protocol session/SessionClock.kt | `8d401b1df2ff63482e8a8e0966736d454fde3adc` |
| core/protocol config/SessionConfiguration.kt | `74470ef0d7f1c9acb847d904a452bdc5d6138cb7` |
| core/model DeviceDTO.kt | `9d4b471f7f28209f4c8c77cae7131f9ef7546d97` |
| core/model ContactDTO.kt | `9d102668ec30d1f0a4075b9ea9dedcaeae3fe32b` |
| core/model MessageDTO.kt | `73e9e78d5c6ac11560a84521e49e07f509e54099` |
| core/model SendEnvelopes.kt | `d68c55365a74cdae73c3caff03979362731bd952` |
| core/contracts domain/MessagingContracts.kt | `ff8072d3c73e1adc3cb4eb623972d7f66150ad59` |
| core/contracts domain/RepositoryValues.kt | `681e2f35f7d534849e55b74884f973be62097a94` |
| core/contracts domain/ConnectionContracts.kt | `eaac312ed47f2a6218e826549b5f5a275a0c8edb` |

New test blob `df416663042879d0cfe0747269bf9e031418ef40`, SHA256
`e7de9c23a5ae978f054749285bc36be24b085141f4da20e037a5855dade9ebdc`.
These are source-byte bindings, NOT an execution receipt. No prior3fa/ffe/
586 source-only validation or12ce CI result is retargeted to them.

## Required positive named execution and actual source checks

This existing declared lane invocation is **NOT RUN**. It selects only
the three genuinely new methods, not an already-passing old cohort.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.QueueAttemptCountOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

Future positive discovery must enumerate all three exact methods above
and bind the actual executed source/base/owner/reservation/lane, with zero
failures/errors/skips. A zero-test/missing/malformed result fails; source
inventory, inspected control flow, no wire or green unrelated counts do
not substitute. Actual isolated candidate CI may provide execution under
the retained local hold; exact protected merge verification stays intact.

| Read-only source command | Actual result for this increment |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

All six actually completed successfully in one chained source-only
invocation at directparent586 with all three increment paths staged;
`git diff --cached --check` also passed. Results were recorded only after
those commands completed. The exact new Kotlin source binding above
remains unchanged. None of these source checks compiles or executes it.

No generated semantic archive, prior execution receipt or original-family
acceptance has been promoted. The independent channel query/correlation
counter-original blocker remains open with its product owners. Nothing
here establishes Kotlin execution, original-family acceptance, physical
readiness, real process death, API37/device/hardware, backup compatibility
or whole-WP completion.
