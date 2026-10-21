# WP-501: original offline pending-envelope hydration on real Room

## Identity and missing-family investigation

- Frozen reference: `db14559b39d32322b06477c6ae676112f583db50`.
- Actual execution parent: `0c9e7397dd0ca8ecdf2cce6de8642ef07161dff3`.
- Actual main/base: `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
- Manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
- Trusted UTF-8 policy:
  `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
- Existing issue134/PR165, same stored attempt/session
  `d6056690-3a5d-453e-b67e-36866c676ac5`, reservation85->86.
- New test:
  `android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/OfflineQueueHydrationOriginalPredicateTest.kt`;
  executed blob `870e61f1511ade03985b57e4220be011c0c89c91`.

Read all selected complete frozen bodies, makeStore/makeMessageService/
makeChannelService, full pending DTO/model/envelope constructors and actual
queue consumers. Compared existing SourceChatQueueTest and real
MessagingRoomIntegrationTest, actual pending/contact/message repositories,
Room mappings/DAOs/context/lifetime and App queue assembly.

Existing SourceChatQueueTest has destinations for all22 originals but uses
RecordingStore; its ordering destination seeds different three/one/two
message/send fixtures. Existing real Room integration seeds real contacts,
messages and an acknowledged started session, not these original empty-store,
missing-contact/unstarted-session inputs. Neither supplies these four complete
original fixtures at their actual persistence/queue boundaries. Merely finding
those old names does not resolve the gap.

## Four complete original bodies and new methods

| Complete frozen source name | New method | Complete original input/actions and required predicates |
| --- | --- | --- |
| `hydrate replays persisted PendingSend rows and drains them` | `originalPersistedMissingContactEnvelopeReallyDrainsFromRoom` | Empty actual in-memory store; fresh separate radio/message/contact/envelope-row UUIDs; persist one default DM envelope with no contact/message/device seed. Require the complete persisted row before hydrate, then actual hydrate/awaitDrainCompletion and empty persisted rows. Additionally require native queue count0 and no transport activity. |
| `hydrate processes only the service's own radio rows` | `originalTwoOwnRadioEnvelopesDrainAndTheExactForeignEnvelopeSurvives` | Fresh radios A/B, two distinct missing-contact envelopes for A and one for B, in original insertion order. Require all three complete persisted rows/sequences first. Bind one actual service to A, hydrate/drain; A empty, B count1 and exact original foreign messageID/full DTO retained. |
| `hydrate fetch returns rows in sequence order` | `originalHydrationFetchPreservesTheThreeActualEnvelopeMessageIDsInOrder` | Three fresh A/B/C DM envelopes with separate message/contact/row IDs, inserted through actual assigning-sequence API. Fetch returns exact original messageIDs `[A,B,C]` and full DTOs at sequences1/2/3. As in the original, this is the fetch boundary, not an invented started-session send-order test. |
| `hydrate runs once per service instance` | `originalSecondHydrateDoesNotEnqueueTheNewlyPersistedEnvelope` | Persist/require first full envelope, hydrate/drain and require empty. Persist/require a second fresh full envelope. Same service instance hydrate/awaitDrainCompletion again; count1 and exact second DTO survive. No replacement service or reinsertion after the second hydrate. |

All envelopes use actual production DirectMessageEnvelope defaults and
PendingSendDTO.fromEnvelope. An independently literal full DTO assertion
requires DM, original IDs/radio/contact, channelnil, resendfalse, empty text,
timestamp0, localnamenil, sequence0, enqueuedAt and attemptCount0 BEFORE
persistence. Required complete readbacks prevent absent/incorrect fixtures
from masquerading as deletion or foreign-row survival. Assigning-sequence
results are independently required: A1/2 and B1, or A1/2/3. After deleting the
only pending row, the next insertion is1, as both stores compute from the
remaining per-radio maximum rather than a durable global counter.

The source DTO's attemptCount default is0; the source model initializer's
separate nil default is not substituted for it. UUIDs remain freshly generated
and distinct input roles as in Swift. Only incidental Date() is fixed to
Unix1704067200; no date ordering or clock-boundary predicate is replaced.
Expected rows are not produced by another candidate persistence algorithm.

## Real consumers and explicit native lifetime adaptation

RoomProcessTest opens actual in-memory Room with native SQLite, owns the
process storage scope, awaits real store close and closes its database.
No RecordingStore or fake transaction implementation is used here.
Actual RoomPersistenceStore -> PendingSendRepository -> DAO/mapping/context
assigns, reads and deletes rows. Actual ChatSendQueueService hydrates and
drains through its real SendQueue and missing-contact lookup/status/cleanup
path. Source missing-contact cleanup is exercised rather than successful
radio sending; these originals intentionally contain no real Contact,
Message or Device records.

As the Swift helper does, the fixture constructs a real MessageService over
an actual MeshCoreSession/MockTransport and does NOT start the session.
Native construction additionally requires a generation/radio-bound token,
32-byte local identity input, ConnectionSignals and an owned coroutine scope.
The extra local key is zero-filled construction input, not a crypto vector.
The test explicitly supplies one static logical READY snapshot while the
transport remains Disconnected. This only satisfies the native queue's
additional ownership/readiness gate; it is not observed connection readiness,
an actual radio generation or evidence for ConnectionManager transitions.
There are no transition events; the real watcher receives a closeable channel
subscription and shutdown closes it. TestScope owns deterministic queue
dispatching; real Room transactions retain the process-owned storage scope.

The selected inputs are all DM. Source's unused ChannelService/ReactionService
roles become fail-fast native channel-query/reaction-indexer roles; unexpected
use fails instead of returning a manufactured channel/index success.
This is not an actual assembled App connection graph or channel/reaction test.
For each of the three queue-consuming cases, require initial selfInfo absent,
no sent transport bytes and transport still disconnected. Await actual queue
and MessageService teardown reports and require complete; always stop the
session and cancel/join the owned job. No real connect/config/admin operation.

## Exact newly affected declared execution

Readiness `python android\scaffold\check_environment.py` **PASS**:
allowlisted installed JDK21/private caches/SDK, no dependency/tool install.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.OfflineQueueHydrationOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS4/4, zero failures/errors/skips**, exact four methods in the first
table. Started `2026-10-21T03:43:30.943986Z`, finished
`03:45:27.474836Z`; fresh XML timestamp `03:45:26.600270Z`,
suite3.945s. Actual0c9/baseDBB/source870e61f unchanged before/after.
XML:
`android\app\build\test-results\testDebugUnitTest\TEST-com.meshcoreone.android.parity.OfflineQueueHydrationOriginalPredicateTest.xml`.
Primary local log under session27f3c368 files:
`wp501-offline-queue-hydration-originals-652c9711ac7f4268965fbd1c9a8c962d.log`.

Both canonical shared lanes empty before acquisition; native lane checked
again after Gradle acquisition. Same stored owner acquired/released exclusive
Gradle suffix `offline-queue-hydration-originals:652c9711ac7f4268965fbd1c9a8c962d`,
binding reservation86/0c9/DBB/870e/selector/977b/e8afe. Final records empty.
Fresh read03:47:47.249495Z reconfirmed ordinary release and both lanes empty.
Further Gradle admissions held for existing503 comparator11 as directed;
source inspection/documentation alone continues. No interrupt, forced/foreign
release, native/device action, old positive replay or new actor.
Actual release reported/read back in PR165 comment6307556368.

## Immutable source and actual consumer byte bindings

Frozen paths below are under `MC1Services/`; native paths under `android/`.

| File | Actual Git blob |
| --- | --- |
| Frozen Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift | `fb80d92c0d5628088d0c126a5ba6139b0de4f6b2` |
| Frozen Sources/MC1Services/Services/ChatSendQueueService.swift | `1fdb6cbe40577887d01a37e6063f0eec89ab91c7` |
| Frozen Sources/MC1Services/Models/PendingSend.swift | `26836068d071cab8baebf3d202577b88d06aa9c2` |
| Frozen Sources/MC1Services/Models/PendingSendEnvelope.swift | `c9035ceec1ddd579dae90dc6691430d10f2e2367` |
| Frozen Sources/MC1Services/Models/DirectMessageEnvelope.swift | `9f71b9ff1521be21ff89340993c5c7d8d73c8d21` |
| core/services messaging/ChatSendQueueService.kt | `e76b382895677f320d106022326ac952d56d5958` |
| core/services messaging/MessageService.kt | `1236ce36bfcb2d0952b5e4bd9150f9f071f6ff82` |
| core/services messaging/SendQueue.kt | `532f9b85d0a49c402d4bb80ede5864fb0a3c9592` |
| core/services messaging/MessagingOwnership.kt | `df15e2f6276956411143aedf17a7dd215411b0c1` |
| core/model SendEnvelopes.kt | `d68c55365a74cdae73c3caff03979362731bd952` |
| core/contracts domain/ConnectionContracts.kt | `eaac312ed47f2a6218e826549b5f5a275a0c8edb` |
| core/data repository/PendingSendRepository.kt | `15a01dcacbfbacd69526a5e6e126793f73eea895` |
| core/data repository/ContactRepository.kt | `8da5513b403198a44f673addf443c5411b58a80c` |
| core/data repository/MessageRepository.kt | `937380545fe91d5b77e4a1552be0180f34b8612f` |
| core/data repository/RoomPersistenceStore.kt | `de1c03fe01659660ee4628bd804a7b9b565f2d3c` |
| core/data repository/RoomRepositoryContext.kt | `85d5593ac6f45783acbefc5efeabb58a24f111d4` |
| core/database MessageEntities.kt | `903e0d6a53d64a0ca19ef32841bca41803ad747d` |
| core/database MessageDaos.kt | `082ea7f2a58a3ea02fbbb7f7c1863ec4c841cac5` |
| core/database MessageMappings.kt | `46a3f212a38399aecff68aea0c6387322b19becf` |
| app container/RadioSessionContainer.kt | `654935183432df88ade39f73358f3252c3fdf379` |
| app test container/ContainerHarness.kt, including RoomProcessTest | `c6fb962fca19d1ff87c48eaa05868514a08d614a` |
| Existing core/services test messaging/SourceChatQueueTest.kt | `456293b7d2323c7c4bf0f703401e55baa3b9fd1f` |
| Existing candidate core/data test messaging/MessagingRoomIntegrationTest.kt | `5ac7ac4e8bda3f5110a2620bd0f0c46183ce3cd2` |

All five frozen inputs equal db145. All production consumers, App fixture
and SourceChatQueueTest equal actual mainDBB. Existing owned
MessagingRoomIntegrationTest differs from main but exactly equals parent0c9;
its earlier WP-501 adaptation is not a new change in this increment.
An initial overbroad all-files-equal-main probe stopped on that old test;
subsequent explicit path/blob comparison distinguishes it from product drift.

| Actual read-only command | Result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

## Separate published-head blocker and acceptance limits

Published0c9 automatic39539245075 is now terminalFAILURE, not healthy/passing.
Actual Linux119023427739 log retrieval succeeded: provisioning failed at
03:39:46.3460906Z, after `Verifying platforms;android-37.2 (linux)`,
`BLOCKED: Publisher archive size/SHA-256 mismatch; installation refused`.
No Android assertion/timeout diagnosis follows from that pre-build failure.
Classify/controller/Swift codec succeeded, Linux/aggregate failed and
SwiftData restore/export was skipped. Exact hosted log/run is authoritative;
no second result bundle or manually downloaded archive, hash/policy bypass,
retry, dispatch or cancellation. Reported using existing bug template in
PR165 comment6307556368 for the existing trusted setup owner.
Underlying archive mismatch cause remains unestablished.

Four local real-consumer executions are not all22 queue originals.
Transient parked-row, classifiers, cancellation, successful transport and
other families remain separate; none is excluded or newly accepted here.
Robolectric API31/native SQLite is not API37/device/hardware, physical
process death, backup interoperability or current-head hosted/full-WP
acceptance. Independent bounded comparison and any subsequent publication
remain separate; no prior source review or hosted verdict is transferred.
