# WP-501: complete persisted DM failure originals

## Binding and actual verification status

**UNVERIFIED SOFTWARE CANDIDATE.** Three new executable original-family
destinations are implemented, but neither Kotlin compilation nor their
execution/discovery has occurred. No 3/3 result or original-family acceptance
is claimed. Actual local Gradle/native admissions remain held for the
retained WP-503 native recovery operation. Source implementation is allowed;
the hold is not permission to run another compiler, restart an emulator,
release a foreign lease or manufacture a passing result.

- Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
- Direct implementation parent/published head:
  `12ce24a5395dc86399f4b724091b0a48676ab05c`.
- Actual main/base: `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
- Manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
- Trusted UTF-8 policy:
  `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
- Same issue134/PR165 and stored attempt/session
  `d6056690-3a5d-453e-b67e-36866c676ac5`, reservation87->88.
- New `DirectQueueFailureOriginalPredicateTest.kt` source blob:
  `e7dcfbfeb07f8093072e298e72e7fd92fc5b8b57` (unexecuted).

Publication of reviewed12ce used the existing exact `record_pending`
and invocation-only omission of the advisory optimization, per the
coordinator's explicit correction. Installed hooks/runtime/configuration
and the foreign native lease were unchanged. Its automatic Android
candidate39543623108 is separate: it does not contain this increment.
The previous experimental hook change was removed, not committed/deployed.
Neither that experiment nor hydration4/4 verifies this new Kotlin source.

## Why these three destinations were missing

Read all three complete frozen bodies, DMHarness, setUpDMHarness,
DirectSendFailureMode, both drain helpers, teardown and the synchronous
status-event registration/finish-and-drain helper. Read complete source
constructor/defaults, pending-envelope conversion and createPendingMessage/
createOutgoingMessage, plus actual Room repositories, queue, MessageService,
failure classifier, public session startup, raw response parser and owned
lifetimes. Compared the existing named SourceChatQueueTest destinations and
their full native Harness/failingDM helpers.

The old destinations use RecordingStore, a prestarted firmware3 fixture
instead of the original disconnected/unstarted session, pool attemptCap0
instead of the original transient cap2/base0.01, and terminal firmware5
instead of the original illegalArgument6. Their names and green counts do
not establish these complete original fixtures on the actual persisted
consumer boundary. This increment changes no old test, product, App fixture,
build graph or expected protocol golden.

## Full original inputs and executable predicates

| Frozen complete original | New method | Inputs/actions and required consumer predicates |
| --- | --- | --- |
| `transient send error preserves the persisted row while parked on the trigger` | `originalUnstartedSessionRetainsTheCompletePendingRowWhileActuallyParked` | Device key32x01/"Test Device", its fresh id as service radio (NOT its distinct radioID); Contact key32x02/"Test Contact", rawtype0/path255/lastHeard0. Actual MessageService creates "Hello", actual full pending row is persisted/required. Session/transport remain unstarted/disconnected. Hydrate, explicit native logical-ready transition, original200ms virtual observation. Require an actual typed NotConnected park diagnostic, complete row/attempt1, complete message remapped to PENDING, queued count1, no wire data and no SelfInfo. |
| `Transient DM send error must not broadcast .failed` | `originalTableFullExhaustsTwoPoolRetriesWithoutBroadcastingFailed` | Device key32x22/"Test Radio"/its actual radioID; Contact key32x33/raw0/path255/"Test Contact"; original full source SelfInfo key32xAB/"TestNode", not normalized to Device22. Session timeout0.2; original MessageServiceConfig pool cap2/base0.01, other defaults retained. Register status subscription BEFORE hydration. Reply firmware3 to each actual DM request; hydrate/native CONNECTED->READY, original300ms virtual window. Require three actual send requests and typed nested DeviceError3 at the park boundary, persisted original full pending row/attempt1 and full PENDING message, then finish/drain real status stream with zero Failed events. |
| `Terminal DM send error broadcasts .failed exactly once` | `originalIllegalArgumentBroadcastsExactlyOneFailureForTheOriginalMessageID` | Same complete connected source helper, DEFAULT message/queue configs, exact ProtocolError.illegalArgument6 (not old fixture5). Register before hydrate; reply6, await actual drain completion. Require typed nested DeviceError6/one actual DM send, complete original message with only FAILED status changed, pending row removed, queued count0, and finish/drain status stream yielding exactly one Failed carrying the ORIGINAL messageID. |

All three seeds/readbacks require a full independently literal outgoing
MessageDTO: "Hello", raw outgoing1/pending0/plain0, actual fresh original
message/contact IDs, fixed positive epoch timestamp, createdAt/sortDate,
sendCount1, retry/max0, heard0, path0, all original false/nil/empty fields.
The message is created by production MessageService, not hand-inserted.
Full device/contact seeds preserve the original default constructor fields,
including firmware0/empty version/manufacturer/build, max100/channels8,
RF915000/250000/SF10/CR5, tx/max20, multiAcks2/baseTelemetry2 and inactive.
Contact remains raw0, not a substituted raw1 CHAT frame. Require exact
stored device/contact before send and exact device/contact partitions after
the outcome; only createPendingMessage's lastMessageDate is changed.

PendingSendDTO.fromEnvelope is independently compared with every literal
source DM/default field before production sequence assignment; full row is
required at sequence1/attempt0 before hydration. At the parked boundary
only attempt1 differs. No missing-contact drop proxy, absent seed, silent
empty-query fallback or new-row insertion after an outcome can satisfy it.

## Explicit native adaptations, not source/device claims

The real RoomPersistenceStore uses the existing openMemoryDatabase factory
with actual DAOs/transactions and immediate test executors, and an owned
TestScope scheduler rather than the unrelated Default-dispatcher base.
Production queue, SendQueue and MessageService execute their real actions,
typed errors, durable writes and event broadcaster. Unsupported DM-only
channel query/reaction-index roles fail on access.

Swift connects the harness transport and injects SelfInfo through a test-only
session helper; native uses PUBLIC MeshCoreSession.start and an existing
FirmwareRadio fixture with an independently literal SELF_INFO input frame.
Every source SelfInfo field is required, including adv0, tx/max20,
lat/lon0, multi2, advertLocation0, environment/location0/base2, manualfalse,
915MHz/250kHz/SF10/CR5 and "TestNode". Source Device22 remains distinct
from SelfInfoAB. Frame error responses are literal ERROR1/code3 or6,
one per actual SEND_MESSAGE request instead of the source20ms injected-event
loop. No product test hook or alternate MessageService algorithm is added.

Native requires an explicit SessionToken/current ownership and logical
send-queue readiness. Hydrate initially observes CONNECTED (or DISCONNECTED
for the unstarted fixture), then transitions logically to READY after its
readiness wait has registered. The connected fixture really starts its
mock session; the unstarted fixture deliberately retains actual protocol/
transport Disconnected even when logical readiness is injected. Its typed
NotConnected identity/park diagnostic proves the queue entered the actual
failure path, rather than merely leaving the envelope in a readiness wait.
This additional native lifetime input is NOT observed physical readiness.

Fresh UUID roles remain distinct. Source Date() samples are fixed to
Unix1704067200; native monotonic deadlines use the test scheduler.
The original200/300ms observation windows advance virtually, with all
original config values preserved. Pool jitter chooses1.0 within the
unchanged0.8...1.2 interval; this deterministic incidental sample is not a
different cap/baseDelay or statistical jitter-coverage claim. The source
status helper's synchronous subscribe-before-action and finish-then-drain
semantics use the production closeable native event subscription. Native
session tokens are additionally checked. Real queue/message teardown
reports must be complete; session, store, owned job and Room DB are closed
through nested finally paths, not abandoned parked tasks.

## Immutable source and consumer pins

All frozen paths below exactly equal db145; no reference amendment applies
to them. Prefixes `S` and `T` mean `MC1Services/Sources/MC1Services/` and
`MC1Services/Tests/MC1ServicesTests/`, respectively.

| Frozen input | Git blob |
| --- | --- |
| T Services/ChatSendQueueServiceTests.swift | `fb80d92c0d5628088d0c126a5ba6139b0de4f6b2` |
| T Helpers/MessageService+Testing.swift | `3de6453e968eef7dfcedd30e060008ef05dbf5e7` |
| S Services/ChatSendQueueService.swift | `1fdb6cbe40577887d01a37e6063f0eec89ab91c7` |
| S Services/MessageService+SendDM.swift | `0d61fe6649cf3be2be29e75a0ad77e483ccb8482` |
| S Services/MessageServiceConfig.swift | `ebfbaa0043027149eebeb82865ab138a7dd630e5` |
| S Services/FirmwareDeviceErrorCode.swift | `17e6e04c243817063148f2253a61b995d0f8bc71` |
| S Models/Device.swift | `823bee3ced673712a51bfa5332dbba81cd4dfed5` |
| S Models/Contact.swift | `7b6ecd2e224c1b1651edeba8a0019426881e7db4` |
| S Models/Message.swift | `27caa780e5d8ee1dcf4fd5dab661ef9945a5fe26` |
| S Models/PendingSend.swift | `26836068d071cab8baebf3d202577b88d06aa9c2` |
| S Models/PendingSendEnvelope.swift | `c9035ceec1ddd579dae90dc6691430d10f2e2367` |
| S Models/DirectMessageEnvelope.swift | `9f71b9ff1521be21ff89340993c5c7d8d73c8d21` |
| MeshCore/Sources/MeshCore/Protocol/ErrorCode.swift | `ddfb1e92e69b7b86e8fac38e02dc78587869729a` |
| MeshCore/Sources/MeshCore/Session/SessionConfiguration.swift | `8da533669bcc84529b2f190939c4eb5cfb3a8944` |

Native paths below are under `android/`. These20 inputs equal actual
mainDBB AND parent12ce; all remain unchanged in this increment.

| Native consumer/comparison input | Git blob |
| --- | --- |
| app test container/ContainerTestSupport.kt | `2ffc6cdf5870f412f7f8770ae97230c3f7f7eef4` |
| core/data repository/RoomPersistenceStore.kt | `de1c03fe01659660ee4628bd804a7b9b565f2d3c` |
| core/data repository/RoomRepositoryContext.kt | `85d5593ac6f45783acbefc5efeabb58a24f111d4` |
| core/data repository/DeviceRepository.kt | `9e069c60b5f696a970716ff9c51a1fb99bc27252` |
| core/data repository/ContactRepository.kt | `8da5513b403198a44f673addf443c5411b58a80c` |
| core/data repository/MessageRepository.kt | `937380545fe91d5b77e4a1552be0180f34b8612f` |
| core/data repository/PendingSendRepository.kt | `15a01dcacbfbacd69526a5e6e126793f73eea895` |
| core/services messaging/ChatSendQueueService.kt | `e76b382895677f320d106022326ac952d56d5958` |
| core/services messaging/MessageService.kt | `1236ce36bfcb2d0952b5e4bd9150f9f071f6ff82` |
| core/services messaging/MessageServiceConfig.kt | `89aba3d1de38ea86d43e65a5ec4d6a058f536e78` |
| core/services messaging/SendQueue.kt | `532f9b85d0a49c402d4bb80ede5864fb0a3c9592` |
| core/services messaging/MessagingOwnership.kt | `df15e2f6276956411143aedf17a7dd215411b0c1` |
| core/protocol session/MeshCoreSession.kt | `3e98cc221bca2af76c273a2e2f88ec005b0f2f29` |
| core/protocol session/SessionCore.kt | `d6378350d45cb25e8b452e0552418c1352c37469` |
| core/protocol session/SessionClock.kt | `8d401b1df2ff63482e8a8e0966736d454fde3adc` |
| core/protocol parser/Parsers.kt | `d328ac0717b6468d7d10cd05c069d32d1765d696` |
| core/model DeviceDTO.kt | `9d4b471f7f28209f4c8c77cae7131f9ef7546d97` |
| core/model ContactDTO.kt | `9d102668ec30d1f0a4075b9ea9dedcaeae3fe32b` |
| core/model MessageDTO.kt | `73e9e78d5c6ac11560a84521e49e07f509e54099` |
| Existing core/services test messaging/SourceChatQueueTest.kt | `456293b7d2323c7c4bf0f703401e55baa3b9fd1f` |

Existing candidate MessagingTestSupport.kt is
`c2f05a47ad59324e0a4e24eb714a29f1b93327ce`, unchanged from12ce, NOT equal
to main. An initial all-native-inputs-equal-main probe stopped on this old
WP-501 test adaptation; the explicit parent comparison above corrects that
overbroad diagnostic without misreporting product drift.

## Required focused execution and source validation

The declared command below is **NOT RUN**. Its future positive discovery
must include all three named methods above, zero failures/errors/skips and
the actual executed source/base/reservation/lane. A zero-test result is
failure, not evidence. No native/device/API37/hardware/backup claim follows.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.DirectQueueFailureOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

| Read-only source command | Actual result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

All six actually completed successfully in one chained invocation at parent12ce
with the three new-family files staged. `git diff --cached --check` also passed.
These read-only source/ownership/mapping results are not Kotlin compilation,
test discovery or executable original-family acceptance.

An initial scope evolution with both modify and record-validation-evidence
was rejected because the latter operation is not admitted on Kotlin test
paths. No reservation was changed by that refusal. The supported owned-path
modify operation then automatically evolved the SAME87->88 for both new
paths, verified from actual status. Authorization prose and prior
executions/receipts are untouched. No new owner/worker/provider or capability
exception was introduced.

Independent bounded source comparison, actual focused execution and exact
published-candidate CI remain separate prerequisites. Do not supersede
healthy12ce CI with this unreviewed local source; do not infer all22 queue
originals or whole-WP completion from three new destinations.
