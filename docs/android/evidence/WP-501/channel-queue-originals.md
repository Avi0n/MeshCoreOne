# WP-501: persisted channel-drain originals and a blocked real-query boundary

## Bounded diagnostic correction after exact586 failure (2026-10-21)

**LOCAL CORRECTION, NOT COMPILED/RUN/DISCOVERED.** Its direct parent is
`dea433c5c99b8ee36bbb5c75040bc0c7418b709b`; reviewed localDEA remains
unchanged and unpublished. This increment changes only the existing
channel fixture, diagnostic assertion messages, this evidence and the
WP-501 deviation. The three complete channel original bodies, fixture
construction/DTOs/wire inputs/timing/configuration and all DEA test and
evidence bytes remain unchanged. No new family or product change.
Same owner/attempt/session and existing modify capabilities were checked
through normal `reserve.py evolve`; admission was already complete, so
the reservation correctly remains92, without release/recreation.

### Authoritative failed execution, not a source-only guess

[Exact586 automatic39555279222](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39555279222)
is completed/FAILURE. Linux119074319252 and aggregate119081271631 failed;
classifier119073498241, controller119073558483 and Swift codec119074319188
succeeded. SwiftData119081272420 was SKIPPED after upstream failure,
not a passed or scope-excluded oracle. Official available-report evidence
reports App1105 completed,1104 passed,1 failed,0 errors/skips.

The sole reported failing identity is
`ChannelQueryCorrelationDiagnosticTest.blockedCounterOriginalExposesTheRealQueryCorrelationBoundaryWithoutClaimingParity`.
Its stack points to fixture53 from finalizer80, not a demonstrated body
completion. The outer ConnectionLost assertion preceded the failing
direct-cause UnresolvedReply assertion. The log does NOT expose the
actual nested cause class or establish that earlier body predicates or
the second park were reached. Cleanup could replace a body failure.
No diagnostic pass or named positive3/3-original inventory is inferred
from the aggregate. No failed586 rerun or second CI-result bundle.

### Actual consumer trace and bounded assertion repair

`ExchangeOwner.match` checks correlation before registration/write. Its
finally marks an unresolved sent/nonterminal request with the requested
family AND `error`, even when its matcher ignores ERROR2.
`SessionCore.checkCorrelation` constructs
`MeshCoreException.ConnectionLost(SessionCorrelationException.UnresolvedReply(generation, family))`.
The exception constructor preserves the Java cause. ChannelService
retries a Timeout, but wraps a subsequent ConnectionLost as its typed
`ChannelServiceError.SessionError(error)` without another wire write.
MessageService's real failure path wraps a protocol exception in
`MessageServiceException(MessageServiceError.SessionError(underlying))`.

The queue reports the query exception, counts it, and parks the same
object. A non-storage query diagnostic alone is not retained; this
unrecognized ChannelService wrapper IS retained by the first park.
A subsequent transient MessageService/ConnectionLost park is reported
without replacing that retained issue. Shutdown maps the retained
failure object directly to a STOP_SERVICES issue. The correction adds
an `assertSame` between that issue and the original query diagnostic.
It does not accept the second park's error as a substitute.

Existing protocol `TcpOwnershipParityTest.correlationCause` already
traverses at most16 cause nodes specifically for coroutine exception
stack recovery. `SessionBoundaryCasesTest` and
`ReviewedSessionRegressionTest` likewise locate typed correlation
causes through the chain. Therefore one fixed Java cause depth is not
a reliable assertion of the protocol's typed semantic contract.
This source/prior-art explanation is NOT an observed cause-chain dump
from586; actual recovery-copy depth is still unobserved.

The corrected helper requires the outer ConnectionLost and allows only
consecutive ConnectionLost wrappers before the exact UnresolvedReply.
An unrelated intermediate exception, absent typed cause, typed cause
outside the first16 nodes, or wrong literal family still fails. It does not scan past
arbitrary wrappers or replace the typed assertion with non-null.
Assertion messages print the bounded class/message chain, including
the correlation generation/family, so a different runtime shape is
actionable. Query and second-park wrapper assertions also print their
actual throwable stacks on failure.

All original body predicates remain: complete A/B DTOs and rows,
A attempt8 then9/B2, PENDING state, queue length2, three sends/one
GET_CHANNEL, original query/first-park identity, one real wake, no new
query/write, exact second MessageService wrapper and `error` family,
no terminal events. The first query still requires `channel:0`.
The actual phase/row/wire/identity predicates must still execute.
No quarantine/generation reset, synthetic Timeout/null, new product
hook, or production/protocol/consumer-contract edit.

The fixture now retains the first body/assertion/cancellation failure,
attempts every owned cleanup stage under NonCancellable, attaches each
later cleanup failure as suppressed without replacing the primary, and
rethrows the original object. If the body succeeds, the first cleanup
failure itself is thrown. Queue report/type/family/identity and complete
message close assertions are retained; session/store/job/database
cleanup all still run. Broad Throwable capture is confined to this
test-resource finalization boundary and always ends in rethrow of the
retained failure, never a success-shaped fallback or lost cancellation.

### Correction execution requirements and source checks

The existing focused command below remains **NOT RUN** for this
correction. Required positive identities remain exactly the unchanged
three original/adapted methods and the separate diagnostic; their
defined existence is not discovery or4/4. The fourth original remains
BLOCKED even if the corrected diagnostic passes. Current native hold
forbids local Gradle/compiler/native/host/guest/lane/reentry/release/
restart actions. No dispatch, manual rerun, publication or merge.

The four additional source inputs below were individually verified
equal at actualmainDBB, directparentDEA and current working bytes.
The existing21 frozen/28 native input table remains the original input
binding, not an execution report.

| Additional native source input under android/ | Git blob |
| --- | --- |
| core/protocol config/MeshCoreException.kt | `439055a72ff9ae6c775c861c91cb934b21b8945f` |
| core/protocol test parity/TcpOwnershipParityTest.kt | `fe4141efd2f7a1080565a22b1cccffc81cf86575` |
| core/protocol test session/SessionBoundaryCasesTest.kt | `0f1de64cdd96e1a6f297ec084593018e1531a85b` |
| core/protocol test session/ReviewedSessionRegressionTest.kt | `d638ed26d273aebf9ef3770caf31e711620b48b6` |

All six declared source-only commands actually PASSED for this
correction with exactly four paths staged:

| Source command | Actual correction result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

`git diff --cached --check` also passed. These are not Kotlin compilation
or positive execution/discovery. The older sections below are the historical
586 source-preparation record; their NOT RUN statements and older
predecessor CI must not override the actual exact586 failure above or
be reinterpreted as execution of this correction or localDEA.

## Exact binding and actual status

**UNVERIFIED SOFTWARE SOURCE.** Three original/adapted channel destinations
and one explicitly Android-only diagnostic are implemented, NOT compiled,
executed or discovered. The fourth original, the per-envelope fetch-failure
counter scenario, remains **BLOCKED**, not replaced by the diagnostic.
Four method declarations are not four ported originals or a 4/4 result.

- Frozen reference: `db14559b39d32322b06477c6ae676112f583db50`.
- Direct parent: `ffe4facc09ad50f4be56fd736b5fcbeb43a36c47`.
- Actual main/base: `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
- Manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
- Trusted UTF-8 policy:
  `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
- Same WP-501/d605 attempt/session/branch/worktree, reservation89->90->91,
  owned modify admission for the three new Kotlin paths and this evidence.
- Original destinations: `a750091e90d99d643139497f7f9a85d7d87a2ae6`.
- Shared original fixture: `53b479f99bc790e5f61d4e409d01e097f28511ee`.
- Separate native diagnostic: `ff769ba6041527392ebde8bb0e94892a5a2ab0bf`.

Published12ce/automatic39543623108 actually completed all six jobs
SUCCESS, including SwiftData119046352710 and aggregate119048328685.
That exact run contains none of local3fa/ffe/this increment and is not
their execution. Old0c9's provisioning failure remains old0c9-only.
No second CI-result bundle is created or revalidated.

Fresh reservation status still reports the retained WP-503 native lease
`1666502a-0420-46ef-930f-e78651ba8b9f:comparator:2be164ec007a4693b219e85f3fc5bb7b`,
purpose comparator-api37-11-original-methods/source473. No local Gradle,
compiler, native/device/host/guest/reentry/release/restart operation was
performed. Missing local execution is explicit, not an invented test pass
or a feature-publication permission gate.

## Complete source bodies, fixtures and actual consumers

Read all four frozen channel bodies at280-497, the complete
ChannelCapHarness/setUpChannelCapHarness, error dispatcher, drain and
teardown helpers; original Channel/Device/Message/PendingSend/envelope
constructors and defaults; channel create/send/resend, pool backoff, queue
query/counter/park/terminal behavior; ChannelService.fetchChannel; and the
public session matching/timeout and CHANNEL_INFO parser boundaries.
Compared all four old SourceChatQueueTest destinations and full Harness/
failingChannel helpers. Those old cases use RecordingStore, pool cap0,
different seed metadata and only one "second" message in their counter
variant. The counter's injected MessagingChannelQuery throws Timeout
without any real ChannelService/getChannel request, masking the boundary
described below. Old green results are not this family.

The new fixture uses real RoomPersistenceStore/openMemoryDatabase, actual
MessageService/ChatSendQueueService, real ChannelService and public
MeshCoreSession/FirmwareRadio. It preserves Device32x11/"Test Radio" and
its actual radioID, distinct from Device.id; Channel0/"Test Channel"/16xAA;
enabled true, nil lastMessageDate, zero unread/mentions, ALL notifications,
not favorite and inherited/nil flood scope.

Production creates BOTH "Hello channel" and "Second message". Independent
complete MessageDTOs require every source field/default and actual saved
row, not just status. A's complete original pending row has sequence1,
isResend true, original text/timestamp/"Test Radio", supplied attempt0/7.
B's message ALWAYS exists, even when no B pending row is persisted.
The diagnostic also preserves the counter original's actual B pending
row with sequence2/attempt2/isResend false. Independent complete envelope
and PendingSendDTO assertions preserve IDs, radio, kind, nullable contact,
index, metadata, enqueue date and attempt count.

All cases retain source session timeout0.2, queue timeout30 and
disambiguateAfterAttempts3. Gone-channel and default-timeout variants
retain default pool cap3/base0.5 and queue failure cap16. Below-threshold
and counter diagnostic retain source pool cap2/base0.01; only the latter
uses source query-failure cap2. Exponent2/jitter range0.8...1.2 and every
other message configuration default remain unchanged.

## Three original/adapted destinations

| Complete frozen original | Native method and required predicates |
| --- | --- |
| `channel drain parks deviceError(2) below disambiguateAfterAttempts` | `originalBelowThresholdRetainsTheCompleteFirstRowWithoutAChannelQuery`: original attempt0/cap2/base0.01 enters three real SEND_CHANNEL requests and the actual nested SessionError(DeviceError2) park. Require the entire original row with only attemptCount1, both complete PENDING messages, queue1, no GET_CHANNEL or ChannelService invocation, and unchanged full device/channel/empty-contact partition. |
| Disabled `drain bounded wait re-attempts after transportWaitTimeout expires` | `originalDefaultThirtySecondWaitReattemptsOnlyAtTheActualParkDeadline`: retain the real default cap3/base0.5 and timeout30, without a DEBUG hook or reduced constant. Require the first complete park/four wire sends, then full attempt1/unchanged wire count at actual recorded park timestamp+29,999ms. At EXACT +30,000ms require full attempt2/fifth actual send; after the second full default backoff require both complete PENDING messages, attempt2/eight sends, two typed DeviceError2 parks, queue1 and no below-threshold query. Virtual time removes wall-clock cost, not the source threshold. |
| Disabled `channel drain treats deviceError(2) as terminal when fetchChannel confirms the channel is gone` | `originalDisabledGoneChannelIntentUsesARealUnconfiguredChannelResponse`: original attempt7/default config traverses four real failed SEND_CHANNEL requests and the actual ChannelService/session/parser path. A literal valid CHANNEL_INFO response for slot0 with empty 32-byte name/zero16-byte secret makes real fetchChannel return null. Require exactly GET_CHANNEL[31,0], one successful-null query result, complete FAILED A/unchanged PENDING B, no pending rows, queue0 and typed original DeviceError2 terminal failure. This supplies the missing harness described by the disabled source; it is an explicit native adaptation, not evidence the disabled Swift body ran or ERROR2 alone confirmed absence. |

The gone response is independently derived from frozen PacketCodes and Parsers+Channels:
opcode0x12, index0, name32 zero bytes, secret16 zero bytes (50 bytes total).
It is not generated by the Kotlin builder/parser under test. All actual
queries delegate directly to ChannelService.fetchChannel; no fake null or
injected Timeout substitutes for the public session.

## Fourth original remains blocked: real query correlation changes the consumer

Frozen getChannel uses matcher-only sendAndWait: only matching
CHANNEL_INFO completes it. Unrelated ERROR2 is ignored, NOT converted
to deviceError2/nil. Frozen ChannelService therefore retries real timeout
three times with500ms/1000ms plus original jitter, then throws typed
sessionError(timeout). The original counter case requires A to exhaust
two such failures under50ms transport-open triggers, be deleted/FAILED,
then B's row to survive the literal three-second observation. It does
NOT require a stable B message status or exact B attempt count.

Native SessionChannelOperations also ignores ERROR2 correctly. However,
SessionCore.match's finally calls unresolved after the FIRST timeout,
tainting channel:0 AND untagged error for the current generation.
ChannelService's second getChannel is consequently rejected BEFORE wire
as ConnectionLost(UnresolvedReply(channel:0)), not another Timeout.
The queue counts that first failed fetch and parks A. On its next actual
retry, the channel send itself is rejected as
MessageServiceException(SessionError(ConnectionLost(UnresolvedReply(error)))).
That error is transient but NOT firmware NOT_FOUND, so it cannot enter the
query/counter branch again. The original A-drains/B-survives scenario is
not established by the old synthetic query-Timeout witness.

This is a source-inspection finding and an **unexecuted diagnostic**,
NOT an observed runtime failure or an approved protocol-safety deviation.
`blockedCounterOriginalExposesTheRealQueryCorrelationBoundaryWithoutClaimingParity`
preserves the complete two-envelope input, requires the first full
attempt8/PENDING park and real query/correlation cause, then explicitly
wakes once and requires Aattempt9/Battempt2, both complete PENDING
messages, unchanged three SEND_CHANNEL/one GET_CHANNEL, no terminal
diagnostics and the exact subsequent untagged-error cause. This bounded
diagnostic intentionally does not pretend to execute the source's
continuous50ms trigger/30-second A-drain/three-second B observation.

Its teardown must report exactly one retained STOP_SERVICES issue with
the original ChannelService SessionError/ConnectionLost/channel:0 cause,
while all actual queue/message/session/Room/job cleanup still runs.
Other variants require complete queue teardown. No broad catch,
production hook, false null, protocol reset or silent issue suppression
is introduced.

**Owner-routed blocker:** the unchanged WP-107 SessionCore correlation
contract, WP-209 ChannelService retry consumer and WP-208 queue consumer
need the current producer's bounded resolution/equivalence decision.
WP-501 does not edit those product paths, weaken late-reply isolation,
reset protocol generations behind consumers, manufacture repeated
Timeouts or assert the counter original accepted. Actual focused
execution must first confirm/refute the diagnostic; retain the original
scenario open regardless of whether the diagnostic itself passes.

## New ungated App counter candidate: execution pending

`ChannelQueueCounterAppOriginalPredicateTest.originalAReachesTerminalAndBRetainsItsRowForLiteralThreeSecondsThroughTheUngatedApp`
now implements the previously absent positive predicate in the admitted parity
source set. Implementation commit `f4d4d8c504d5ddc919b761984a71499b919aa99b`
is locally integrated with published WP-303 `0ecfb10d0f48f25254316bd8d1062602046f8ac6`,
including WP-207 e75682 and WP-208 f2ce014f. This is new source, not an
unchanged previously passing method or a relabelled negative diagnostic.

The actual AppContainer, factory, per-connection services, public session and
Room queries receive same-radio Aattempt7/resendtrue/sequence1 and
Battempt2/resendfalse/sequence2. Default-preserving App configuration wiring
supplies the original failurecap2, poolcap2/base0.01 and session timeout0.2s.
FirmwareRadio returns raw ERROR2 frames for real sends and channel queries;
it does not inject a service exception or null. A 50ms transport-open loop
uses the current real queue without resetting its generation or progress.

The fixture uses Bluetooth, production Default recovery and real clocks.
A row must be observed deleted inside the original monotonic30-second
wait, then its status must be FAILED; B's row must survive a literal
three-second real delay afterward. Follow-up source
`96b2b7a7a` checks the clock before each poll, matching the frozen helper:
a post-deadline deletion cannot turn an expired wait into a pass.
No stable Bstatus/final attempt count is required. Genuine typed query
failures and B consumption additionally exclude cancellation or a disconnected,
never-consumed B as counter-isolation proof. No recovery gate, manual reconnect
acceleration, watchdog change or observation shortening is present. The original
Swift simulator has no physical reconnect/watchdog; the native30-second initial
BLE watchdog remains a real compatibility risk, not an approved timing exception.

Relevant declared execution is `:app:testDebugUnitTest` with both selectors
`--tests com.meshcoreone.android.parity.ChannelQueueCounterAppOriginalPredicateTest`
and `--tests com.meshcoreone.android.app.container.QueueChannelConsumerIntegrationTest`.
Read-only configuration, ownership, portmap, audit, semantic and whitespace checks
pass on the coherent local integration. Kotlin compilation, positive discovery
and execution remain UNVERIFIED: the actual shared native-execution guard is
still held. It is not bypassed or released by this WP. The four-path source
transfer is in PR281 comment6347740262; source execution must bind to a candidate
that actually contains the new test, not borrow the incumbent producer's CI.

The coordinator subsequently assigned faithful ungated regression implementation
and reconciliation to the existing WP-303 producer on PR281. WP-501 transferred
the prepared patch in comment6348026374 and awaits that producer's published
source for independent review. This local candidate is not a competing PR or
an accepted result; no new session, closed PR272 edit or native action follows.

## Explicit native fixture substitutions

The source channel helper connects transport but does NOT install
SelfInfo. Native public MessageService identity/lifetime requires an
actual started session: a literal full SelfInfo11/"TestNode" handshake and
one additional logical READY after hydration registers its first wait
are disclosed extra native inputs. They are not source radio readiness.
Per-subscriber owned Channel streams broadcast that edge without
StateFlow-history conflation or receiveAsFlow load-balancing.

Fixed Unix1704067200, fresh/distinct UUID roles, pool jitter sample1.0 and
ChannelService Random(0) make incidental ambient samples deterministic
within unchanged ranges. ERROR2 is returned for each actual channel
request instead of the source20ms test-only dispatcher; real GET_CHANNEL
ignores it and times out, not a synthetic service exception.
Independent whole DTOs, public request observations, typed failures and
nested finally queue/message/session/store/job/database cleanup remain
required. No hardware/reconnect/process-death/API37/backup evidence follows.

## Individually verified immutable inputs

All21 frozen inputs were compared individually to db145/current bytes;
all28 native inputs below to actualmainDBB, directparentffe and current
bytes. The test-only old MessagingTestSupport remains parent-only c2f05,
not misreported as main. No product/App/scaffold/build input changed.
Paths abbreviated S are under MC1Services/Sources/MC1Services; T under
MC1Services/Tests/MC1ServicesTests; M under MeshCore/Sources/MeshCore.

| Frozen input | Git blob |
| --- | --- |
| T Services/ChatSendQueueServiceTests.swift | `fb80d92c0d5628088d0c126a5ba6139b0de4f6b2` |
| T Helpers/MessageService+Testing.swift | `3de6453e968eef7dfcedd30e060008ef05dbf5e7` |
| S Services/ChatSendQueueService.swift | `1fdb6cbe40577887d01a37e6063f0eec89ab91c7` |
| S Services/MessageService.swift | `b287bd657336b95a0194b9d892a25b939817e963` |
| S Services/MessageService+SendChannel.swift | `139d747632e26d5e520f78dcb654e9c6e975a243` |
| S Services/MessageServiceConfig.swift | `ebfbaa0043027149eebeb82865ab138a7dd630e5` |
| S Services/FirmwareDeviceErrorCode.swift | `17e6e04c243817063148f2253a61b995d0f8bc71` |
| S Services/ChannelService.swift | `179930876d3501cad674cb5952540ed1b5b1ac3c` |
| S Models/Device.swift | `823bee3ced673712a51bfa5332dbba81cd4dfed5` |
| S Models/Channel.swift | `3cca331e353d6504514025ec2758317449927cb9` |
| S Models/Message.swift | `27caa780e5d8ee1dcf4fd5dab661ef9945a5fe26` |
| S Models/PendingSend.swift | `26836068d071cab8baebf3d202577b88d06aa9c2` |
| S Models/PendingSendEnvelope.swift | `c9035ceec1ddd579dae90dc6691430d10f2e2367` |
| S Models/ChannelMessageEnvelope.swift | `11838a76d4607ec8d55654af47a6cc8830e0f1d8` |
| S Services/BLETransportOpenedSignal.swift | `1f5a8b2ba9b25ed8d78ad345187dfc6177410cfe` |
| S Services/SendQueue.swift | `ef9ee480690cf70f8545e9366614aba0ef3e036a` |
| M Session/MeshCoreSession+Channels.swift | `bd2ce4ab685ea8f0ce9ea28cbc2cf0165d7ab9f3` |
| M Session/MeshCoreSession+EventWaiting.swift | `f24f4801d694fdf6348eaeefa213b6d1d601bc0d` |
| M Protocol/Parsers+Channels.swift | `9a2b51e52b5393ccd7fec3bb97c3c7a658e36643` |
| M Protocol/PacketCodes.swift | `31e38b41ad34aaf31a2d855a4f5cc8ff7ebccf6e` |
| M Session/SessionConfiguration.swift | `8da533669bcc84529b2f190939c4eb5cfb3a8944` |

Native paths are under android/; package prefixes follow their module.

| Native input | Git blob |
| --- | --- |
| app test container/ContainerTestSupport.kt | `2ffc6cdf5870f412f7f8770ae97230c3f7f7eef4` |
| core/data repository/RoomPersistenceStore.kt | `de1c03fe01659660ee4628bd804a7b9b565f2d3c` |
| core/data repository/RoomRepositoryContext.kt | `85d5593ac6f45783acbefc5efeabb58a24f111d4` |
| core/data repository/DeviceRepository.kt | `9e069c60b5f696a970716ff9c51a1fb99bc27252` |
| core/data repository/ChannelRepository.kt | `27d187f0f248774ebe673ab8260b88d5734c6c94` |
| core/data repository/MessageRepository.kt | `937380545fe91d5b77e4a1552be0180f34b8612f` |
| core/data repository/PendingSendRepository.kt | `15a01dcacbfbacd69526a5e6e126793f73eea895` |
| core/services messaging/ChatSendQueueService.kt | `e76b382895677f320d106022326ac952d56d5958` |
| core/services messaging/MessageService.kt | `1236ce36bfcb2d0952b5e4bd9150f9f071f6ff82` |
| core/services messaging/MessageServiceConfig.kt | `89aba3d1de38ea86d43e65a5ec4d6a058f536e78` |
| core/services messaging/SendQueue.kt | `532f9b85d0a49c402d4bb80ede5864fb0a3c9592` |
| core/services messaging/MessagingOwnership.kt | `df15e2f6276956411143aedf17a7dd215411b0c1` |
| core/services messaging/BLETransportOpenedSignal.kt | `eb018aff862df0105a844f6786d77533e0ee6718` |
| core/services contacts/ChannelService.kt | `dd90af41b90319c122140b7f54f538c2bffdffa4` |
| core/services contacts/ChannelServiceSupport.kt | `39d312e437e31ea93f2f15bb5a9a03f9c35fb382` |
| core/protocol session/MeshCoreSession.kt | `3e98cc221bca2af76c273a2e2f88ec005b0f2f29` |
| core/protocol session/SessionCore.kt | `d6378350d45cb25e8b452e0552418c1352c37469` |
| core/protocol session/SessionChannelOperations.kt | `f9343e670b209b284b28485b68aad3266021a2ce` |
| core/protocol session/SessionClock.kt | `8d401b1df2ff63482e8a8e0966736d454fde3adc` |
| core/protocol session/SessionDiagnostics.kt | `ad15639582b7d6e1eaec9e24b223d0d0c7f1a767` |
| core/protocol parser/Parsers.kt | `d328ac0717b6468d7d10cd05c069d32d1765d696` |
| core/model DeviceDTO.kt | `9d4b471f7f28209f4c8c77cae7131f9ef7546d97` |
| core/model ChannelDTO.kt | `aca6f253e93097351da74931fcca0652cad75682` |
| core/model MessageDTO.kt | `73e9e78d5c6ac11560a84521e49e07f509e54099` |
| core/model SendEnvelopes.kt | `d68c55365a74cdae73c3caff03979362731bd952` |
| core/contracts domain/ConnectionContracts.kt | `eaac312ed47f2a6218e826549b5f5a275a0c8edb` |
| core/contracts domain/MessagingContracts.kt | `ff8072d3c73e1adc3cb4eb623972d7f66150ad59` |
| Existing core/services test messaging/SourceChatQueueTest.kt | `456293b7d2323c7c4bf0f703401e55baa3b9fd1f` |

## Required execution and actual source validation

The following declared focused command is **NOT RUN**. No new local
execution can overlap the actual retained foreign native lease. The
three prior failure and three readiness methods are also still
unexecuted; their previously documented invocation remains separate.
No healthy predecessor cohort needs replay.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.ChannelQueueOriginalPredicateTest',
    '--tests', 'com.meshcoreone.android.parity.ChannelQueryCorrelationDiagnosticTest',
    '--dependency-verification=strict', '--quiet'
  )
```

Required discovery is the three exact original/adapted method identities
and the separately named diagnostic, bound to actual executed bytes.
Zero/missing tests, failures, errors or skips are not passing execution.
A passing diagnostic still does NOT satisfy the blocked fourth original.

The first source-only chain passed verification_config/validate, then
portmap correctly FAILED on conflicting traceability dispositions:
the initial single file had both PortedFrom and AndroidOnly markers.
No later command in that chain ran. The correction separates the three
ported methods, shared original fixture and explicitly Android-only
diagnostic into properly classified files, under the same evolved
reservation. No traceability validator or classification rule changed.
The historical uncompiled single-file draft a8b383 is not execution.

| Source command | Actual result for this increment |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

After the explicit traceability correction, all six actually passed in
one source-only chained invocation with the five files staged at
parentffe/reservation91. `git diff --cached --check` also passed.
Only this truthful result table/text was then updated; the three bound
Kotlin blobs remain unchanged. These checks are not Kotlin compilation,
positive test discovery or original consumer execution.

No generated semantic archive, old receipt or authorization prose is
rewritten. Source checks, independent comparison, Kotlin discovery/
execution, exact published-candidate CI and whole-WP acceptance remain
distinct. The uncovered counter original remains explicitly in scope.
