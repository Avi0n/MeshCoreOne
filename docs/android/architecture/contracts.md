# Typed boundary signatures

**Status:** Proposed; WP-001 supervised dependent draft, pending human review.
Source links are pinned to `db14559b39d32322b06477c6ae676112f583db50`.
These are API fragments, not implementations or a standalone compilable product.
Names of DTOs/ports not declared here are supplied by their owning WPs, not fake DTOs.

## Values, errors and transport

WP-101 produces immutable content-equal `Bytes`; constructors/accessors copy mutable
arrays, equality/hash use contents, slices validate bounds. WP-201 supplies immutable
DTO snapshots, including copied collections. Store/wire enums retain explicit raw
values. UUID text in codecs/dedup is source-canonical uppercase; wire integer widths,
signed RF fields, UTF-8 and endianness never depend on JVM defaults.

```kotlin
@JvmInline value class RadioId(val value: java.util.UUID)
@JvmInline value class ProcessEpoch(val value: java.util.UUID)
@JvmInline value class Generation(val value: Long)
data class SessionToken(
    val epoch: ProcessEpoch,
    val generation: Generation,
    val radioId: RadioId,
)
```

Generation is increasing within an epoch, never persisted as radio identity.
An epoch prevents stale persisted platform actions colliding with a restarted process.
Preserve nullable absence separately from failure. Suspended operations throw typed
domain/transport/storage exceptions; `CancellationException` propagates, not an
`Unknown` result or `null`. Match every original
[MeshCoreError](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MeshCore/Sources/MeshCore/Session/SessionConfiguration.swift#L94)
case and its code/command/size/key-prefix/cause metadata; never replace them with strings.

[MeshTransport](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MeshCore/Sources/MeshCore/Transport/MeshTransport.swift)
and its [six error cases](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MeshCore/Sources/MeshCore/Events/MeshTransportError.swift)
have primary source owners WP-101 and WP-106 respectively; WP-107 produces the
session that consumes them. BLE/WiFi adapters implement the transport in WP-205/108:

```kotlin
sealed interface TransportError {
    data object NotConnected : TransportError
    data class ConnectionFailed(val reason: String) : TransportError
    data class SendFailed(val reason: String) : TransportError
    data object DeviceNotFound : TransportError
    data object ServiceNotFound : TransportError
    data object CharacteristicNotFound : TransportError
}
class TransportFailure(val error: TransportError, cause: Throwable? = null) : Exception(cause)
interface MeshTransport {
    val receivedData: kotlinx.coroutines.flow.Flow<Bytes>
    suspend fun isConnected(): Boolean
    suspend fun supportsWriteWithoutResponse(): Boolean
    suspend fun supportsPipelinedReads(): Boolean
    suspend fun connect()
    suspend fun disconnect()
    suspend fun send(data: Bytes)
    suspend fun sendWithoutResponse(data: Bytes)
}
```

The receive stream is single-session ingestion, ordered, lossless and terminates
on disconnect; chunks are not assumed to be complete frames. Disconnect is idempotent.
Without-response defaults to acknowledged send and capability false; BLE opts in
only for actual characteristic support. TCP may allow pipelined reads independently.
Protocol `MeshEvent` cases are WP-106 outputs, not Android callback/error strings.

## Persistence roles

WP-201 declares domain/repository types; WP-202 implements the roles in
[PersistenceStoreProtocol](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Sources/MC1Services/Protocols/PersistenceStoreProtocol.swift)
and [Protocols/Persistence](https://github.com/Avi0n/MeshCoreOne/tree/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Sources/MC1Services/Protocols/Persistence).
Preserve all methods/default arguments/ordering when implemented, not only this
representative signature set. Broad consumers may compose the roles; narrow
services receive only the roles they use. Room entities never cross this boundary.

| Original role / Kotlin interface | Required semantics / primary consumers |
| --- | --- |
| `DevicePersisting` | Device ID and radio ID are separate queries; source UInt32 contact-sync bookmark; device/runtime WP-207/211 |
| `MessagePersisting` | Radio-scoped dedup, anchored newest windows/hasMore, conditional status updates, atomic pending-send sequence; messaging/render/sync WP-208/213/214 |
| `ContactPersisting` | Public-key/prefix multiplicity, atomic batch/cascade and unreferenced-delete checks, orphan adoption; WP-209/214 |
| `ChannelPersisting` | Radio/index identity; atomic configured/unconfigured/prune pass without deleting skipped slots; WP-209/214 |
| `TracePathPersisting` | Saved path/runs, exact path bytes/hash size; remote/tool consumers WP-210 |
| `HeardRepeatPersisting` | Per-radio content correlation, path first-wins and counts; WP-216 |
| `DebugLogPersisting` | Batch save and age/row-ceiling pruning; WP-212 |
| `LinkPreviewPersisting` | Nullable URL-keyed cache lookup versus failed read; WP-218 |
| `RxLogPersisting` | Explicit flush, deterministic correlation/enrichment, partition retention; WP-212/216 |
| `RoomPersisting` | Radio-partitioned sessions, permission-preserving disconnect, room dedup/unread/ACK updates; WP-210 |
| `DiscoveredNodePersisting` | Advertisement upsert/isNew, timestamp/hop rules and confirmed-key set; WP-209/214 |
| `ReactionPersisting` | Source dedup/most-recent ordering/limit100 and summary cache; WP-216 |
| `NodeSnapshotPersisting` | Atomic in-window enrichment, location first-wins, ascending history and sparse baselines; WP-210 |

```kotlin
interface DevicePersisting {
    suspend fun fetchDevice(id: java.util.UUID): DeviceDTO?
    suspend fun fetchDevice(radioId: RadioId): DeviceDTO?
    suspend fun updateDeviceLastContactSync(radioId: RadioId, timestamp: UInt)
    suspend fun saveDevice(dto: DeviceDTO)
}
interface MessagePersisting {
    suspend fun isDuplicateMessage(key: String, radioId: RadioId): Boolean
    suspend fun fetchMessage(id: java.util.UUID): MessageDTO?
    suspend fun fetchMessageWindow(
        contactId: java.util.UUID, anchorSortDate: java.time.Instant?,
        floorLimit: Long,
    ): MessageWindow
    suspend fun updateMessageStatusUnlessDelivered(
        id: java.util.UUID, status: MessageStatus,
    ): Boolean
    suspend fun insertPendingSendAssigningSequence(dto: PendingSendDTO): Long
    suspend fun fetchPendingSends(radioId: RadioId): List<PendingSendDTO>
    suspend fun incrementPendingSendAttemptCount(messageId: java.util.UUID): Long?
}
```

`MessageWindow` contains immutable `messages` and `hasMore`; include the separate
radio/channel overload in WP-201. Swift Int sequences/counts use Long with checked
conversions at allocation/query boundaries. A null attempt count means a deleted
row, while a storage exception means park/retry. Conditional-update false means
no change; do not emit a failed/sent event for an already delivered or absent row.
Atomic probe/delete, batch sync and backup import are database transactions, not
multiple independent suspend calls. Source lightweight-stub no-op defaults are
not production implementations. Storage failures retain causes and surface
localized recovery; no destructive database/key recreation.

## Connection signals and factory seam

The source [DeviceConnectionState](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Sources/MC1Services/Connection/DeviceConnectionState.swift)
has five rungs; operational means syncing/ready and send-drain eligibility means
ready only. A `ConnectionSnapshot` carries those rungs, source transport state,
optional BLE phase, connection intent, token and typed issue independently.

```kotlin
enum class DeviceConnectionState { DISCONNECTED, CONNECTING, CONNECTED, SYNCING, READY }
sealed interface TransportState {
    data object Disconnected : TransportState
    data object Connecting : TransportState
    data object Connected : TransportState
    data class Reconnecting(val attempt: Long) : TransportState
    data class Failed(val error: TransportError) : TransportState
}
sealed interface ConnectionIntent {
    data object None : ConnectionIntent
    data object UserDisconnected : ConnectionIntent
    data class WantsConnection(val forceFullSync: Boolean = false) : ConnectionIntent
}
data class ConnectionSnapshot(
    val state: DeviceConnectionState,
    val transport: TransportState,
    val blePhase: BleLinkPhase?,
    val intent: ConnectionIntent,
    val token: SessionToken?,
    val issue: ConnectionIssue?,
)
interface ConnectionSignals {
    val snapshot: kotlinx.coroutines.flow.StateFlow<ConnectionSnapshot>
    suspend fun subscribeTransitions(): ConnectionSubscription
}
interface ConnectionSubscription : AutoCloseable {
    val initial: ConnectionSnapshot
    val transitions: kotlinx.coroutines.flow.Flow<ConnectionSnapshot>
    override fun close()
}
interface ConnectionController : ConnectionSignals {
    suspend fun connect(target: ConnectionTarget)
    suspend fun disconnect(reason: DisconnectReason)
}
data class SessionInputs(
    val token: SessionToken,
    val session: MeshSession,
    val signals: ConnectionSignals,
    val scope: kotlinx.coroutines.CoroutineScope,
)
interface SessionServiceFactory {
    suspend fun create(inputs: SessionInputs): SessionServices
}
interface SessionServices {
    val token: SessionToken
    suspend fun startMonitoring(options: MonitoringOptions)
    suspend fun tearDown(): TeardownReport
}
```

`BleLinkPhase` and `ConnectionIssue` are neutral domain projections declared with
WP-201's contracts, not types imported from concrete BLE/runtime implementations.
Only `UserDisconnected` intent is persisted, following
[ConnectionIntent](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Sources/MC1Services/Connection/ConnectionIntent.swift).
Syncing/ready require a live matching token/session/graph; disconnected carries
no active token. Typed issues preserve failure/capability state without inventing
a sixth app rung or treating optional BLE phase as an error.

Subscription registers before returning its atomic initial snapshot/transition
boundary, without missing or double-applying an edge. StateFlow is for current
UI state, **not** an ACK/transition queue. `close()` unregisters and terminates
this subscription; process signals survive it. Session service events use the
same explicit per-subscriber completion contract with generation-tagged events.

The concrete factory receives process repository roles, preferences, clock,
notification/string ports and dispatchers by constructor; SessionInputs contains
only generation-specific dependencies. The controller owns the supplied scope.
Factory failure cleans up all partial children. Teardown is awaited/idempotent;
`TeardownReport` explicitly includes flush/close issues, not an unconditional success.
WP-207 consumes this seam using test doubles; WP-303 supplies the concrete factory
and service action facades after real prerequisites.

## Platform ports and feature entry points

Notification policy/actions are per-session WP-215; the process Android delivery
adapter is WP-401. A notification command carries stable destination/message IDs
and a guarded radio context. Posting returns explicit posted/permission-denied/
unsupported outcomes; quick reply/mark-read resolves the current graph rather than
retaining old service callbacks. Message translation is removed from active scope and future build
requirements. Any already-declared translation types are inert compatibility contracts, not a required
adapter, registration, success claim or WP-407 blocker; re-admission needs a new user request and
scope/admission decision.

```kotlin
interface NotificationPort {
    suspend fun post(command: NotificationCommand): NotificationPostResult
    suspend fun cancel(id: NotificationId)
}
enum class AppTab(val sourceIndex: Int) {
    CHATS(0), NODES(1), MAP(2), TOOLS(3), SETTINGS(4),
}
sealed interface Destination {
    data class Tab(val tab: AppTab) : Destination
    data class DirectChat(
        val radioId: RadioId, val contactId: java.util.UUID,
        val scrollToMessageId: java.util.UUID? = null,
    ) : Destination
}
```

WP-201/302 extend typed destinations for channels, rooms/authentication, discovery,
node detail, coordinate focus, tools/settings and confirmed link actions, following
[AppTab](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1/State/AppTab.swift)
and [NavigationCoordinator](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1/State/NavigationCoordinator.swift).
The seven stable registration IDs are `onboarding.root`, `chats.root`, `nodes.root`,
`remotenodes.root`, `map.root`, `tools.root`, `settings.root`; remotenodes and onboarding
do not add tabs. Compose entry functions live in their feature modules, take typed
route/dependency/action inputs, and expose no Bluetooth/storage construction.
Contracts contain no `@Composable`, `Context` or concrete Nav3 class.

For example, a chats-owned entry interface can declare:

```kotlin
interface ChatsEntry {
    @androidx.compose.runtime.Composable
    fun Content(
        route: ChatDestination,
        dependencies: ChatFeatureDependencies,
        onNavigate: (Destination) -> Unit,
    )
}
```

The owning feature defines this Compose interface and resolves its typed
dependencies; app performs registration. `ChatDestination` is the chats subset
of the domain route hierarchy, not another feature's class. Content takes
immutable state/actions beneath this wrapper and has no service-construction effects.

WP-002 may compile neutral IDs/registrations and interface shells only with available
types. Domain fragments wait for WP-101/106/201 instead of invented DTOs or duplicate
future implementations. A reachable shell displays localized **not yet ported**,
disabled unavailable actions and capability failures; it does not return empty
data or fake send success. WP-002 compile evidence is never feature parity. Removed translation must
not gain a reachable placeholder/entry or become a registration requirement.
