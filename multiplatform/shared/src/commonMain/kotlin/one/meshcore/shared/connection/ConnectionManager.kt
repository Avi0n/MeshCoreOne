package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.transport.MeshLink
import kotlin.time.Duration.Companion.seconds

/** Swift `ConnectionManager.initializeSession` / `rebuildSession` `withTimeout(.seconds(10))`. */
val HANDSHAKE_COMMAND_TIMEOUT = 10.seconds

/**
 * Owns the connection lifecycle: the transport [MeshLink], the [MeshSession], and a per-connection
 * [ServiceContainer]. Mirrors the Swift `ConnectionManager` ownership graph — the container is rebuilt
 * on every connect and torn down on disconnect, and an explicit [disconnect] is kept distinct from an
 * unexpected connection loss (only the latter arms auto-reconnect).
 *
 * Two transports share this one manager. BLE ([connect]) uses [linkFactory] and the [policy] backoff
 * (30/60/120s); WiFi ([connectViaWiFi]) uses [wifiLinkFactory] and the separate [wifiPolicy] schedule
 * (0.5→4s capped, 35s cooldown) plus an app-level heartbeat, because ESP32 ignores TCP keepalives. The
 * live [currentTransportType] selects which reconnect schedule a drop arms.
 *
 * The link and session are supplied by factories so the production wiring (Kable / TCP + the real
 * `MeshCoreSession` over the Swift bridge) and the pure-JVM state-machine tests (fakes) share one
 * manager. `radioId` is minted once per identity and resolved by public key, never derived from the
 * volatile transport handle (three-axis identity invariant).
 */
class ConnectionManager(
    internal val database: MeshDatabase,
    internal val scope: CoroutineScope,
    internal val linkFactory: (deviceId: String) -> MeshLink,
    internal val sessionFactory: (MeshLink) -> MeshSession,
    internal val nowEpochSeconds: () -> Double,
    ports: ConnectionManagerPorts = ConnectionManagerPorts(),
) {
    /**
     * BLE link factory for the auto-reconnect loop. Null on [ConnectionManagerPorts] means this
     * connection uses [linkFactory], which is what JVM tests and hosts that do not split the clocks do.
     */
    internal val reconnectLinkFactory: (deviceId: String) -> MeshLink =
        ports.reconnectLinkFactory ?: linkFactory

    internal val policy = ports.policy
    internal val wifiPolicy = ports.wifiPolicy
    internal val resyncPolicy = ports.resyncPolicy
    internal val channelRetryPolicy = ports.channelRetryPolicy
    internal val connectRetryPolicy = ports.connectRetryPolicy
    internal val handshakeTimeout = ports.handshakeTimeout
    internal val bluetoothAdapterEnabled = ports.bluetoothAdapterEnabled
    internal val bluetoothConnectAuthorized = ports.bluetoothConnectAuthorized
    internal val hasSystemPairingRegistry = ports.hasSystemPairingRegistry
    internal val systemConnectedHandles = ports.systemConnectedHandles
    internal val wifiLinkFactory = ports.wifiLinkFactory
    internal val lastConnectionStore = ports.lastConnectionStore
    internal val appliedRadioPresetStore = ports.appliedRadioPresetStore
    internal val intentStore = ports.intentStore
    internal val deriveWifiDeviceId = ports.deriveWifiDeviceId
    internal val newRadioId = ports.newRadioId
    internal val discoveredNodes = ports.discoveredNodes
    internal val rxLogStore = ports.rxLogStore
    internal val debugLogStore = ports.debugLogStore
    internal val shouldNotifyDiscovery = ports.shouldNotifyDiscovery
    internal val threadCoordinatorRegistry = ports.threadCoordinatorRegistry
    internal val threadChatStore = ports.threadChatStore
    internal val warmConversationPreviews = ports.warmConversationPreviews
    internal val chatEnvInputs = ports.chatEnvInputs
    internal val unknownSenderLabel = ports.unknownSenderLabel
    internal val roomNameFallback = ports.roomNameFallback
    internal val notifier = ports.notifier
    internal val reactionNotifier = ports.reactionNotifier
    internal val contactDiscoveryNotifier = ports.contactDiscoveryNotifier
    internal val activeConversation = ports.activeConversation
    internal val messageServiceConfig = ports.messageServiceConfig
    internal val sendQueueConfig = ports.sendQueueConfig
    internal val nodePasswordStore = ports.nodePasswordStore
    internal val rxChannelCache = ports.rxChannelCache
    internal val slotOccupantChanged = ports.slotOccupantChanged
    internal val connectionUi = ports.connectionUi
    internal val onContactsChanged = ports.onContactsChanged
    internal val onConversationsChanged = ports.onConversationsChanged

    internal val logger = PersistentLogger(scope, category = "ConnectionManager")

    internal val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    /** The observable connection state, the single rung UI and callers gate on. */
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    /** The live per-connection container, or `null` when not connected. */
    var services: ServiceContainer? = null
        internal set

    /** The data-partition key of the live connection, or `null` when disconnected. */
    val radioId: Uuid?
        get() = services?.radioId

    /** How the live connection reaches the radio, or `null` when disconnected. */
    var currentTransportType: TransportType? = null
        internal set

    internal var link: MeshLink? = null
    internal var session: MeshSession? = null

    /** The user's intent to be connected. Gates auto-reconnect: an explicit disconnect clears it. */
    internal var wantsConnection = false

    /**
     * One-shot full contact sync. Pair and saved-radio reconnect set it; launch auto-reconnect does
     * not. Cleared only after a contacts phase succeeds, so a failed first pass still prunes on resync.
     */
    internal var pendingForceFullSync = false

    /**
     * Last clean / attempted channel pass for the skip window. Kept across a transient drop so an
     * ESP32 reconnect inside 30s does not re-read every slot. Cleared on a user disconnect.
     */
    internal var lastCleanChannelSync: Pair<Uuid, Double>? = null
    internal var lastAttemptedChannelSync: Pair<Uuid, Double>? = null
    internal var forceFullSyncThisCycle = false

    /**
     * True while the connect ceremony's first contacts/channels/backlog pass is running. Foreground
     * [checkSyncHealth] must not start a competing resync loop in that window (iOS waits for
     * `syncCoordinator.state == .failed`, which is never `.syncing`).
     */
    internal var initialSyncInProgress = false

    /**
     * Radio that already spent the one-shot invalid-watermark full fetch. A later connect on the same
     * radio uses the stored stamp incrementally rather than pruning again. Mirrors iOS
     * `invalidWatermarkRecoveryRadioID`.
     */
    internal var invalidWatermarkRecoveryRadioID: Uuid? = null

    /**
     * True during companion pairing / scan-new (iOS `isPairingFlowActive`). Opening the picker
     * to tap a saved radio does not set this, so an in-flight watchdog can be joined.
     */
    internal var pairingInProgress = false

    // Serializes the synchronous pairing callback's teardown and re-arm work. A cancelled pairing
    // must not start a replacement connection until its teardown has released the old link.
    internal var pairingTransitionJob: Job? = null

    /** The device the user wants connected; guards against acting on a superseded device's callbacks. */
    internal var currentDeviceId: String? = null

    internal var reconnectJob: Job? = null

    /** iOS coordinator `restartTimeout`: join of an in-flight reconnect re-arms this window. */
    internal var reconnectUiTimeoutJob: Job? = null
    internal var reconnectUiTimedOut = false

    /** iOS `sessionRebuildDeviceID`: in-place handshake retries still own this handle. */
    internal var sessionRebuildDeviceId: String? = null

    // The post-connect sync-retry loop, live only while an initial sync left the connection short of synced.
    internal var resyncJob: Job? = null

    // The channel-only retry, live only while a connect pass left channel slots unread. Held solely so a
    // disconnect or a later connect can cancel it; a finished loop leaves an inert handle behind, and
    // cancelling one is a no-op.
    internal var channelRetryJob: Job? = null

    // Remote-node sessions that were logged in when the link dropped, replayed into
    // RemoteNodeService.handleBLEReconnection once a fresh container is running. They are held on the
    // manager because Kotlin rebuilds the ServiceContainer on every connect while iOS keeps one alive
    // across the drop. A cold start begins empty, so a room shows its reconnect prompt rather than
    // silently logging back in.
    internal var pendingReauthSessionIds: Set<Uuid> = emptySet()

    // When each BLE handle last completed a verified encrypted session (a full handshake). Seeds the
    // bond-verification grace: a reconnect that keeps hitting encryption timeouts is treated as a weak
    // link rather than a lost bond while the stamp is fresh, so a healthy radio at the edge of range is
    // never wrongly routed to guided re-pair. Persisted per handshake via
    // LastConnectionStore.persistBondVerification and lazily seeded back into this map in startBleReconnect,
    // so the grace survives a cold rebuild; a live stamp here always wins over the older persisted one.
    internal val bondVerificationEpochSeconds = mutableMapOf<String, Double>()

    // Bumped on teardown and on a holder-matched forget so an in-flight persist hop that already
    // observed a live stamp cannot resurrect the cross-launch shield after the clear.
    internal var bondRefreshPersistEpoch = 0L

    // Periodic refresh of an existing BLE bond stamp while the session stays live. Started after a
    // BLE handshake; cancelled in tearDownCurrent. WiFi never starts this — it carries no bond.
    internal var bondRefreshJob: Job? = null

    // The chip family the live radio reported, resolved once per connect from its model string and the
    // transport it arrived on. Drives the pipelined-versus-serial channel read; a connection that has not
    // handshaked yet is UNKNOWN, which takes the conservative serial path.
    internal var detectedPlatform: DevicePlatform = DevicePlatform.UNKNOWN

    // A monotonic token identifying one connect attempt, mirroring the Swift `reconnectGeneration` guard.
    // The transport handle cannot serve as one: auto-reconnect retries the same handle without touching
    // wantsConnection or currentDeviceId, so a stale ceremony and the attempt that replaced it are
    // indistinguishable by handle. Every path that abandons the connection a ceremony is building runs
    // tearDownCurrent, which is what makes bumping it there enough to invalidate that ceremony.
    internal var connectGeneration = 0L

    // The connect attempt currently suppressing sync notifications, or null when none is. The suppression
    // is one shared last-writer-wins flag, so the attempt that raised it has to be the one that lowers it.
    internal var syncSuppressionOwner: Long? = null

    // WiFi reconnect targeting + anti-flap cooldown, in the same wall-clock seconds as nowEpochSeconds.
    internal var wifiHost: String? = null
    internal var wifiPort: Int? = null
    internal var lastWifiReconnectStartSeconds: Double? = null

    internal val circuitBreaker = ConnectionCircuitBreaker(nowEpochSeconds = nowEpochSeconds)

    /**
     * Confirmed foreground. Starts false: a BLE process relaunch is already background, matching iOS
     * `BLEStateMachine.isAppActive`. [setAppActive] is the scene-phase edge.
     */
    internal var isAppActive = false

    /**
     * Whether [setAppActive] has run. Until the scene reports, advert parking treats the process as
     * foreground (iOS `AppStateProvider` nil defaults true) so ConnectionManager tests and
     * activate-before-foreground still drain 0x80 keys.
     */
    internal var scenePhaseKnown = false

    internal data class SyncPhasesOutcome(val retryableIndices: List<Int>, val messageCount: Int)

    /** How a resync loop ended: recovered, out of attempts, or superseded before it could decide. */
    internal enum class ResyncOutcome {
        SUCCEEDED,
        EXHAUSTED,
        ABANDONED,
    }

    /**
     * One `setOtherParams` write: the whole frame to send, the fields this write owns rendered for
     * comparison against the radio's read-back, and the row update to persist once the radio confirms.
     */
    internal class OtherParamsWrite(
        val params: OtherParams,
        val owned: (OtherParams) -> String,
        val persist: (DeviceEntity) -> DeviceEntity,
    )

    internal companion object {
        const val MILLIS_PER_SECOND = 1000.0

        // Stands in for an absent field in a disconnect diagnostic, so an empty slot cannot read as a
        // value the connection actually had.
        const val NO_VALUE = "none"

        // selfInfo carries the radio frequency in MHz and bandwidth in kHz; the Device row stores kHz
        // and Hz, matching the Swift connect path's unit conversions.
        const val KHZ_PER_MHZ = 1000.0
        const val HZ_PER_KHZ = 1000.0

        // Firmware v10+ accepts setPathHashMode; older radios reject the opcode.
        const val PATH_HASH_MODE_MIN_FIRMWARE_VERSION = 10

        // A never-configured radio's auto-add policy, matching what the Device row seeds with on both
        // platforms (Swift `Device.Defaults`).
        const val DEFAULT_AUTO_ADD_CONFIG = 0
        const val DEFAULT_AUTO_ADD_MAX_HOPS = 0

        // How far the radio's clock may sit from the phone's before it is corrected. Wide enough that a
        // radio deaf for the duration of a write is not re-written on every promotion; narrow enough that
        // the drift never reaches a message timestamp or the reaction-matching window.
        const val DEVICE_CLOCK_DRIFT_TOLERANCE_SECONDS = 5.0

        // Per-command ceiling on the clock exchange, so a radio that stops answering cannot hold the
        // connect coroutine open.
        val DEVICE_CLOCK_SYNC_TIMEOUT = 5.seconds
    }
}

/**
 * The six fields one `setOtherParams` frame carries. The frame is all-or-nothing, so a write that owns
 * one field still has to state the other five; this keeps the carried values and the requested one in a
 * single value that can be compared, field for field, against what the radio reports back.
 */
internal data class OtherParams(
    val manualAddContacts: Boolean,
    val telemetryModeEnvironment: Int,
    val telemetryModeLocation: Int,
    val telemetryModeBase: Int,
    val advertisementLocationPolicy: Int,
    val multiAcks: Int,
) {
    /** The telemetry triple, rendered for a verification-failure message. */
    fun describeTelemetry(): String =
        "base=$telemetryModeBase, loc=$telemetryModeLocation, env=$telemetryModeEnvironment"

    /** The direct-message acknowledgment count, rendered for a verification-failure message. */
    fun describeMultiAcks(): String = "multiAcks=$multiAcks"

    /** The advertisement location policy, rendered for a verification-failure message. */
    fun describeAdvertLocationPolicy(): String = "advertLocationPolicy=$advertisementLocationPolicy"

    /** The manual-add-contacts flag, rendered for a verification-failure message. */
    fun describeManualAddContacts(): String = "manualAddContacts=$manualAddContacts"

    companion object {
        /** The radio's live values, falling back per field to the Device row when the read failed. */
        fun live(existing: DeviceEntity, info: SelfInfo?): OtherParams = OtherParams(
            manualAddContacts = info?.manualAddContacts ?: existing.manualAddContacts,
            telemetryModeEnvironment = info?.telemetryModeEnvironment ?: existing.telemetryModeEnv,
            telemetryModeLocation = info?.telemetryModeLocation ?: existing.telemetryModeLoc,
            telemetryModeBase = info?.telemetryModeBase ?: existing.telemetryModeBase,
            advertisementLocationPolicy = info?.advertisementLocationPolicy ?: existing.advertLocationPolicy,
            multiAcks = info?.multiAcks ?: existing.multiAcks,
        )

        /** What the radio reports it is running, for comparison against a write. */
        fun reportedBy(info: SelfInfo): OtherParams = OtherParams(
            manualAddContacts = info.manualAddContacts,
            telemetryModeEnvironment = info.telemetryModeEnvironment,
            telemetryModeLocation = info.telemetryModeLocation,
            telemetryModeBase = info.telemetryModeBase,
            advertisementLocationPolicy = info.advertisementLocationPolicy,
            multiAcks = info.multiAcks,
        )
    }
}
