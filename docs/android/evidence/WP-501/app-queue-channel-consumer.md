# Actual App queue channel-query consumer: unexecuted local correction

This bounded correction follows published, reviewed
`e8f6da52d20bf37cac4f3bd6f47683a35209690f` on the existing WP-501 branch/PR165.
It is local and **UNPUBLISHED**. New Kotlin is **NOT COMPILED / NOT RUN /
NOT DISCOVERED**. Four defined Android-only integration methods are not four
passing cases, four new Swift originals, or acceptance of the queue family.

Actual main/base is `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`. The read-only
reference remains `db14559b39d32322b06477c6ae676112f583db50`; canonical manifest
is `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8` and trusted
policy is `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.
Exact e8 automatic39561171838 completed all six jobs SUCCESS; official
Linux119093438455 reported App1108 and services2647 passed, zero failures,
errors or skips. That is predecessor-only evidence, not execution of this
increment. No CI-result bundle was saved, duplicated or revalidated.

## Product boundary and typed admission

Frozen `ServiceContainer.swift:313-318` constructs the queue with its real
per-connection ChannelService. Frozen `ChatSendQueueService.swift:331-355`
calls `channelService.fetchChannel` to distinguish a configured channel from
legitimate absence without substituting query failures for absence. Native
ChannelService already preserves configured/unconfigured classification,
typed NOT_FOUND handling, index validation, timeout retry and cancellation.

The actual main/e8 `RadioSessionContainer.kt:188` bypassed that service:

```kotlin
MessagingChannelQuery { index -> session.getChannel(index) }
```

The correction changes only that expression:

```kotlin
MessagingChannelQuery { index -> channelService.fetchChannel(index) }
```

The graph's existing ChannelService instance, declaration/construction order,
process/connection lifetimes, factory ownership, generation signals, reaction
adapter, reporter, package/applicationId, SDK bounds and Google-independent
core remain unchanged. The production file retains its primary WP-303 frozen
ServiceContainer provenance; no WP-207/runtime/protocol/service contract,
quarantine, generation or product-hook change is made.

Actual trusted main/current policy admits both App paths through
`app-build-launcher` / `add-direct-support-wiring`, with no WP filter. Invariants:
no unrelated feature activation, process/connection lifetimes,
GMS-independent core and min/target/application identity. Required affected
compile/tests and manifest/navigation verification remain pending, not waived.
WP-owned create/modify admits this evidence and the existing deviation record.

Before edits, actual actor cbattlegear/916234, clean exact head/public branch,
main/base, paused=false, trusted helper/model/ledger/policy/manifest bytes and
same leased WP-501 owner were checked. Historical authorization and binding
remain unchanged. The same attempt/session
`d6056690-3a5d-453e-b67e-36866c676ac5` evolved reservation92->93 through the
supported typed compare-and-swap, without release/recreate or manual ledger edits.

Actual same-file historical WP-216/WP-401 intents and broader WP-313/WP-407
App intents were reconciled deterministically against the common main/e8
production blob `654935183432df88ade39f73358f3252c3fdf379`, not another checkout.
All composition outside the one query expression is preserved. With LF line
endings and either complete adapter expression above replaced by the literal
`<QUEUE_CHANNEL_QUERY>`, parent and candidate source both have SHA256
`00670e2070805bec17b38f87bf14bbc2d2549f586322d3aeaf57fc62805a936b`.
All producer attributions remain attached; no foreign reservation is released,
rewritten or treated as authorization for unrelated edits.

## Actual factory regression, not a copied composition

`QueueChannelConsumerIntegrationTest` extends existing real native-SQLite
RoomProcessTest and uses the unchanged ContainerHarness. It enters production
AppContainer -> ConnectionManager -> RadioSessionContainerFactory ->
RadioSessionContainer, with the real RoomPersistenceStore, MessageService,
ChatSendQueueService, ChannelService and public MeshCoreSession. It never
constructs its own queue/query adapter or substitutes a callback result.

One complete fixed message and sequence1/attempt7 channel envelope are persisted
before connection. The BLE target uses the stored device's actual id, distinct
from its radioId, avoiding an accidental orphan-device migration. A second
persisted radio/message/envelope is a complete untouched partition witness.
The fake radio performs the public startup handshake and configured-slot sync.
Every other startup slot returns a valid independent empty CHANNEL_INFO frame;
startup ERROR2 replies cannot silently contaminate correlation before the test.

The real contact-list reply is held until the actual graph is SYNCING, the
pending envelope is hydrated and the contact request has reached the wire.
The status stream is subscribed before releasing that registered contact
request. No channel write is allowed before actual runtime READY. This is
protocol-speaking software readiness, not physical BLE, hardware or native
device evidence. Fixture clocks/config/defaults are not changed in production.

All four methods share these load-bearing assertions:

- Same real registry/lifecycle-created container, Room store, current manager
  token, MessageService/queue token, link-live token, one graph and one connection.
- Four complete independent SEND_CHANNEL packets for `"Hello channel"` at
  Unix1704067200, then exactly GET_CHANNEL `[31,0]`, excluding unrelated startup/
  monitoring commands. Expected packets never call a candidate packet builder.
- Full MessageDTO and PendingSendDTO equality, exact attempt7->8 and unchanged
  sequence/id/enqueuedAt/text/timestamp/node fields; complete foreign row/message
  and configured local channel survive as specified, before and after teardown.
- Real deliberate disconnect joins the graph, closes its status broadcaster,
  releases the receive reader and underlying fake transport once, withdraws the
  registry entry and ends the connection observer/live jobs.
- Finished status events retain the exact token and original message identity.
  Primary assertion/cancellation failures survive cleanup; later cleanup failures
  are suppressed rather than masking the original.

| Defined method in `com.meshcoreone.android.app.container.QueueChannelConsumerIntegrationTest` | Required actual consumer behavior | Execution |
| --- | --- | --- |
| `actualFactoryConfiguredChannelKeepsTheOriginalEnvelopeParked` | Configured slot response traverses the App-bound ChannelService. The original nested MessageService SessionError(DeviceError2) parks; full PENDING row stays at8, no query/terminal error and no Failed event. | NOT RUN |
| `actualFactoryUnconfiguredChannelTerminatesOnlyItsOriginalEnvelope` | Valid empty-name/zero-secret CHANNEL_INFO traverses real parsing and fetchChannel -> null. Only the original message becomes fully FAILED, its pending row is deleted, queue empties and exactly its one Failed event drains; the foreign partition and local configured channel remain. The old direct adapter would park instead of reaching these terminal assertions. | NOT RUN |
| `actualFactoryRawNotFoundQueryRetainsTypedCorrelationFailureInsteadOfInventingNull` | Raw ERROR2 is not manufactured into a null. Require actual ChannelServiceError.SessionError, ConnectionLost/recovery copies followed by exact UnresolvedReply(generation1, channel:0), same query/park object, PENDING full row at8, one query on wire, no terminal/Failed event, and the same retained STOP_SERVICES cause in queue and actual runtime teardown receipts. The old adapter would expose direct Timeout rather than this service retry/wrapper boundary. | NOT RUN |
| `actualFactoryCancelsTheHeldQueueQueryWithoutTerminalFailureOrRowLoss` | A genuinely unanswered registered query is cancelled by real queue shutdown. The intermediate full FAILED bubble from MessageService is restored to full PENDING, original row8 survives, repeated shutdown replays the same complete receipt, no query/park/terminal failure or Failed event is invented, and actual graph teardown joins the reader/observer without another send/query. | NOT RUN |

## NOT_FOUND is not an invented public-session null

ChannelService's typed DeviceError2->null branch remains intact, as does its
existing service-level mock test. However, actual `ExchangeOwner.channel`
passes no error matcher: a raw ERROR2 wire frame alone does not finish the
GET_CHANNEL request. It reaches the real configured session timeout; finally
quarantines channel:0 AND untagged error. ChannelService's timeout retry then
encounters pre-wire ConnectionLost(UnresolvedReply(channel:0)) and wraps it.
The new real-graph negative case requires this typed non-null boundary. It
does not claim to execute the typed NOT_FOUND->null branch through a protocol
path that currently cannot produce that exception from the reply.

Legitimate null in the positive absent-channel case comes from an independently
encoded valid unconfigured channel response, not injected null, timeout or a
successful-looking error response. Index validation is preserved by delegation,
not claimed independently exercised by a reply the protocol filters first.
No correlation safety weakening, family reset, generation replacement, injected
query timeout or product-only test hook is introduced.

The existing gone-channel primary original/adaptation remains once-only and
unchanged. These four methods are AndroidOnly composition integration proof,
not four new originals. The fourth original per-envelope query-counter case
still remains BLOCKED. Neither the previously passed diagnostic nor this
unexecuted real-App diagnostic accepts it or whole22/WP-501/native parity.

## Source provenance

Frozen paths below match frozen/main/parent/current; native unchanged inputs
match actual main/parent/current. Production composition is the one changed
native input; its old and candidate blobs are explicit.

| Input | Git blob |
| --- | --- |
| Frozen `MC1Services/Sources/MC1Services/ServiceContainer.swift` | `c26bca984af6c888c0ad9e6e946d5d75d4b66af8` |
| Frozen `MC1Services/Sources/MC1Services/Services/ChatSendQueueService.swift` | `1fdb6cbe40577887d01a37e6063f0eec89ab91c7` |
| Frozen `MC1Services/Sources/MC1Services/Services/ChannelService.swift` | `179930876d3501cad674cb5952540ed1b5b1ac3c` |
| Frozen `MC1Services/Sources/MC1Services/Services/MessageService+SendChannel.swift` | `139d747632e26d5e520f78dcb654e9c6e975a243` |
| Frozen `MC1Services/Sources/MC1Services/Services/SendQueue.swift` | `ef9ee480690cf70f8545e9366614aba0ef3e036a` |
| Frozen `MC1Services/Sources/MC1Services/Models/PendingSend.swift` | `26836068d071cab8baebf3d202577b88d06aa9c2` |
| Frozen `MC1Services/Sources/MC1Services/Models/ChannelMessageEnvelope.swift` | `11838a76d4607ec8d55654af47a6cc8830e0f1d8` |
| Frozen `MeshCore/Sources/MeshCore/Session/MeshCoreSession+Channels.swift` | `bd2ce4ab685ea8f0ce9ea28cbc2cf0165d7ab9f3` |
| Frozen `MeshCore/Sources/MeshCore/Protocol/PacketCodes.swift` | `31e38b41ad34aaf31a2d855a4f5cc8ff7ebccf6e` |
| Frozen `MeshCore/Sources/MeshCore/Protocol/PacketBuilder.swift` | `870637fd5b3bc985c00ecf1ceb3f2730b86b4647` |
| Frozen `MeshCore/Sources/MeshCore/Protocol/Parsers+Channels.swift` | `9a2b51e52b5393ccd7fec3bb97c3c7a658e36643` |
| Native App `AppContainer.kt` | `609fa79b8a9291c684fee6f190fd4a705a7ea9dc` |
| Native App `RadioSessionContainerFactory.kt` | `508042f9f90854b1b0b46eb7a380665d29bc9ec0` |
| Native App `SessionEnvironment.kt` | `fef7bae2186505e67ad82d58df002baf7315652e` |
| Existing App test `ContainerHarness.kt` | `c6fb962fca19d1ff87c48eaa05868514a08d614a` |
| Existing App test `ContainerTestSupport.kt` | `2ffc6cdf5870f412f7f8770ae97230c3f7f7eef4` |
| Native runtime `ConnectionManager.kt` | `a7e1a2300008f3c038609d9e8d01cbe6284192a4` |
| Native services contacts `ChannelService.kt` | `dd90af41b90319c122140b7f54f538c2bffdffa4` |
| Native services contacts `ChannelServiceSupport.kt` | `39d312e437e31ea93f2f15bb5a9a03f9c35fb382` |
| Native services messaging `ChatSendQueueService.kt` | `e76b382895677f320d106022326ac952d56d5958` |
| Native services messaging `MessageService.kt` | `1236ce36bfcb2d0952b5e4bd9150f9f071f6ff82` |
| Native services messaging `SendQueue.kt` | `532f9b85d0a49c402d4bb80ede5864fb0a3c9592` |
| Native protocol session `SessionChannelOperations.kt` | `f9343e670b209b284b28485b68aad3266021a2ce` |
| Native protocol session `SessionCore.kt` | `d6378350d45cb25e8b452e0552418c1352c37469` |
| Native protocol config `SessionConfiguration.kt` | `74470ef0d7f1c9acb847d904a452bdc5d6138cb7` |
| App `RadioSessionContainer.kt`, main/parent before correction | `654935183432df88ade39f73358f3252c3fdf379` |
| App `RadioSessionContainer.kt`, corrected candidate | `dee5d0a7e8f2231f56ee3eabb9bfe4dde2a9c0ad` |
| New App test `QueueChannelConsumerIntegrationTest.kt` | `e88a88fb99b172eba7e2cbf627094f80fd05c52f` |

The independent wire witnesses follow frozen PacketCodes/PacketBuilder and
Parsers+Channels: SEND_CHANNEL03/plain00/index00/Unix-seconds little-endian/
original UTF8 text; GET_CHANNEL1F/index00; CHANNEL_INFO12/index00/32-byte
zero-terminated name/16-byte secret. No candidate Kotlin generates expectations.

## Verification requirements and actual status

This declared existing App task, selected for the new class, is **NOT RUN**:

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.app.container.QueueChannelConsumerIntegrationTest',
    '--dependency-verification=strict', '--quiet'
  )
```

Actual execution must positively discover all four exact class/method identities
above, execute their complete row/wire/token/status/cause/teardown predicates and
report zero failures/errors/skips. Missing/zero/skipped discovery is not success.
The current foreign native/Gradle safety exclusion remains in force. No local
advisory, Gradle/compiler/WSL/native/host/guest/probe/reentry/release/restart,
deployment, CI dispatch/rerun, main/policy installation or publication occurred.
No local environment-readiness/build action was substituted for the prohibited
execution. The future exact candidate needs actual hosted execution, not an
inherited e8 verdict.

| Declared source-safe command | Actual result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |
| `git diff --cached --check` | PASS |

These source-only checks cannot establish Kotlin compilation, positive discovery,
App/native execution, physical reconnect/process-death/backup evidence or WP
acceptance. This one coherent local source/test/evidence correction remains
unpublished pending complete exact comparison; no next family is started.
