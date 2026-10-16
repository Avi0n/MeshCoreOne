package one.meshcore.shared.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.channel.ChannelService
import one.meshcore.shared.channel.ui.ChannelQrScanner
import one.meshcore.shared.contact.ContactPingResult
import one.meshcore.shared.contact.ui.ContactQrScanner
import one.meshcore.shared.contact.ui.QrEncoder
import one.meshcore.shared.map.ElevationService
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.remotenode.ui.RemoteAdminWorkspaces
import one.meshcore.shared.room.RoomNotificationSink
import one.meshcore.shared.room.RoomServerService
import one.meshcore.shared.tools.LineOfSightViewModel
import one.meshcore.shared.tools.NodeDiscoveryWorkspace
import one.meshcore.shared.tools.NoiseFloorReading
import one.meshcore.shared.tools.NoiseFloorViewModel
import one.meshcore.shared.tools.RxLogStore
import one.meshcore.shared.tools.TracePathWorkspace
import one.meshcore.shared.trace.SavedTracePathStore
import one.meshcore.shared.trace.TraceResult

interface ShellServiceHost {
    /**
     * Emits on every app foreground edge (Android `ON_START`, desktop window focus). An open room
     * conversation restarts the keep-alive that backgrounding stopped, the counterpart of the app-state
     * hook that stops them; only the room on screen restarts, so no airtime is spent on the rest.
     * Default never emits.
     */
    val appForeground: Flow<Unit>

    /** The live radio's data-partition id, or `null` when disconnected. Scopes the Nodes list's reads. */
    val currentRadioId: () -> Uuid?

    /**
     * The live radio's [RepeaterAdminService], or `null` when disconnected. Drives the repeater auth/admin
     * screens; defaulted so a platform that has not yet wired the per-connection node graph compiles.
     */
    val repeaterAdminService: () -> RepeaterAdminService?

    /**
     * Live [NodeSnapshotService] for status/telemetry history capture, or `null` when disconnected.
     */
    val nodeSnapshotService: () -> NodeSnapshotService?

    /** The live radio's [RoomServerService], or `null` when disconnected. Drives the room join/auth screens. */
    val roomServerService: () -> RoomServerService?

    val roomAdminService: () -> one.meshcore.shared.room.RoomAdminService?

    /**
     * The app-lifetime encrypted node-password store (the iOS keychain analogue), or `null` on a platform
     * without secure storage wired; the node auth sheet reads and clears saved passwords through it.
     */
    val nodePasswordStore: () -> NodePasswordStore?

    /**
     * Resolves a pushed chat route to the live conversation thread and its provider bundle, or `null`
     * until a platform wires the per-connection chat graph; the shells then render the disconnected
     * placeholder for the chat detail.
     */
    val chatThread: ChatThreadBinder?

    /**
     * The live radio's [ChannelService], or `null` when disconnected. Read live by the channel create/join
     * flows; defaulted so a platform that has not yet wired the per-connection service compiles.
     */
    val channelService: () -> ChannelService?

    /**
     * Removes a deleted channel's delivered notifications and refreshes the badge. Deleting a channel
     * must not leave its alerts in the tray pointing at a conversation that no longer exists.
     */
    val clearChannelNotifications: suspend (channelIndex: Int, radioID: Uuid) -> Unit

    /** Refreshes the OS unread badge after mute or a DM delete. */
    val updateBadge: suspend () -> Unit

    /**
     * Notification hooks for the open room conversation, the room counterpart of the chat thread's
     * active-conversation sink. [RoomNotificationSink.NONE] leaves the tray and badge untouched.
     */
    val roomNotifications: RoomNotificationSink

    /** The connected radio's own public key, for the "Share My Contact" QR; `null` when disconnected. */
    val selfPublicKey: () -> ByteArray?

    /** The device's contact-table cap, for the "node list full" copy; `null` when unknown. */
    val maxContacts: () -> Int?

    /** The device's channel-slot cap, gating the channel create/join options; `null` when unknown. */
    val maxChannels: () -> Int?

    /** An epoch-seconds clock stamped onto a newly added contact frame's `lastModified`. */
    val nowSeconds: () -> Long

    /** Encodes a QR payload into a module grid, or `null` when no encoder is available. */
    val qrEncode: QrEncoder

    /** The camera slot backing the contact QR scanner; a no-op renders no preview (desktop/disconnected). */
    val contactScanner: ContactQrScanner

    /** The camera slot backing the channel QR scanner; a no-op renders no preview (desktop/disconnected). */
    val channelScanner: ChannelQrScanner

    /** The live radio's blocked-channel-sender service, or `null` when disconnected. */
    val blockedChannelSenderService: () -> one.meshcore.shared.contact.BlockedChannelSenderService?

    /**
     * Zero-hop contact ping (Trace). `null` hides the ping action. Injected from the live session on
     * Android; desktop leaves it null until a transport is wired.
     */
    val zeroHopPing: (suspend (ContactDto) -> ContactPingResult)?

    /**
     * Live telemetry request (`MeshSession.requestTelemetry`). `null` keeps chat Telemetry on history
     * only. Android injects the connected session; desktop leaves it null until a transport is wired.
     */
    val requestTelemetry: (suspend (publicKey: ByteArray) -> one.meshcore.shared.meshcore.TelemetryResponse)?

    /**
     * Tools → Trace Path runner (path bytes + hash size + flags → [TraceResult]). `null` keeps the
     * form disabled until a live session is wired.
     */
    val runTracePath: (suspend (pathBytes: ByteArray, hashSize: Int, flags: Int) -> TraceResult)?

    /**
     * Tools → Trace Path saved-path store (Room-backed on Android/desktop). `null` falls back to a
     * process-local in-memory store so the form still works in tests without a database.
     */
    val savedTracePathStore: SavedTracePathStore?

    /** Tools → RX Log volatile buffer; `null` shows the empty listening state. */
    val rxLogStore: RxLogStore?

    /**
     * Tools → Noise Floor poller (`getStatsRadio`); `null` disables polling. A read the radio refuses
     * must throw: returning null means "no radio", and collapsing the two tells an operator whose radio
     * is connected but silent that nothing is connected.
     */
    val fetchNoiseFloorReading: (suspend () -> NoiseFloorReading?)?

    val lineOfSightWorkspace: LineOfSightViewModel?

    val noiseFloorWorkspace: NoiseFloorViewModel?

    /** The process-owned discovery workspace, retained across tool mounts. */
    val nodeDiscoveryWorkspace: NodeDiscoveryWorkspace?

    val remoteAdminWorkspaces: RemoteAdminWorkspaces?

    val tracePathWorkspace: TracePathWorkspace?

    /**
     * Tools → Node Discovery active scan (`sendNodeDiscoverRequest` → correlation tag). `null` keeps
     * the tool on the offline placeholder until a live session is wired.
     */
    val sendNodeDiscover: (suspend (filter: Int, prefixOnly: Boolean) -> Long)?

    /** Live mesh event stream for active discovery response correlation; null when disconnected. */
    val meshEvents: () -> kotlinx.coroutines.flow.Flow<one.meshcore.shared.meshcore.MeshEvent>?

    /** Tools → Line of Sight Open-Meteo elevation; `null` forces flat sea-level analysis. */
    val elevationService: ElevationService?

    /** Tools → CLI remote login/passthrough; null when disconnected. */
    val toolsCliRemote: () -> one.meshcore.shared.tools.ToolsCliRemoteBridge?

    /** Tools → CLI local radio commands (clock/ver/board/advert/get/set/…); null when disconnected. */
    val toolsCliLocal: () -> one.meshcore.shared.tools.ToolsCliLocalBridge?
}

/** Live radio services, identity, scanners, and tool bridges. */
data class ShellServiceInputs(
    override val appForeground: Flow<Unit> = emptyFlow(),
    override val currentRadioId: () -> Uuid? = { null },
    override val repeaterAdminService: () -> RepeaterAdminService? = { null },
    override val nodeSnapshotService: () -> NodeSnapshotService? = { null },
    override val roomServerService: () -> RoomServerService? = { null },
    override val roomAdminService: () -> one.meshcore.shared.room.RoomAdminService? = { null },
    override val nodePasswordStore: () -> NodePasswordStore? = { null },
    override val chatThread: ChatThreadBinder? = null,
    override val channelService: () -> ChannelService? = { null },
    override val clearChannelNotifications: suspend (channelIndex: Int, radioID: Uuid) -> Unit = { _, _ -> },
    override val updateBadge: suspend () -> Unit = {},
    override val roomNotifications: RoomNotificationSink = RoomNotificationSink.NONE,
    override val selfPublicKey: () -> ByteArray? = { null },
    override val maxContacts: () -> Int? = { null },
    override val maxChannels: () -> Int? = { null },
    override val nowSeconds: () -> Long = { 0L },
    override val qrEncode: QrEncoder = { _, _ -> null },
    override val contactScanner: ContactQrScanner = { _, _ -> },
    override val channelScanner: ChannelQrScanner = { _, _ -> },
    override val blockedChannelSenderService: () -> one.meshcore.shared.contact.BlockedChannelSenderService? = { null },
    override val zeroHopPing: (suspend (ContactDto) -> ContactPingResult)? = null,
    override val requestTelemetry: (suspend (publicKey: ByteArray) -> one.meshcore.shared.meshcore.TelemetryResponse)? =
        null,
    override val runTracePath: (suspend (pathBytes: ByteArray, hashSize: Int, flags: Int) -> TraceResult)? = null,
    override val savedTracePathStore: SavedTracePathStore? = null,
    override val rxLogStore: RxLogStore? = null,
    override val fetchNoiseFloorReading: (suspend () -> NoiseFloorReading?)? = null,
    override val lineOfSightWorkspace: LineOfSightViewModel? = null,
    override val noiseFloorWorkspace: NoiseFloorViewModel? = null,
    override val nodeDiscoveryWorkspace: NodeDiscoveryWorkspace? = null,
    override val tracePathWorkspace: TracePathWorkspace? = null,
    override val remoteAdminWorkspaces: RemoteAdminWorkspaces? = null,
    override val sendNodeDiscover: (suspend (filter: Int, prefixOnly: Boolean) -> Long)? = null,
    override val meshEvents: () -> kotlinx.coroutines.flow.Flow<one.meshcore.shared.meshcore.MeshEvent>? = { null },
    override val elevationService: ElevationService? = null,
    override val toolsCliRemote: () -> one.meshcore.shared.tools.ToolsCliRemoteBridge? = { null },
    override val toolsCliLocal: () -> one.meshcore.shared.tools.ToolsCliLocalBridge? = { null },
) : ShellServiceHost
