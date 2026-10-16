package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.meshcore.AutoAddConfig
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.CommandResult
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.DeviceOtherParams
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.meshcore.NeighboursResponse
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toLowercaseHex
import one.meshcore.shared.transport.LinkDropReason
import one.meshcore.shared.transport.MeshLink
import one.meshcore.shared.transport.MeshLinkConnectException
import one.meshcore.shared.transport.MeshLinkListener

/**
 * An in-memory [MeshLink] for the state-machine tests. It never touches Bluetooth: [connect] either
 * succeeds or throws whatever [failConnectWith] is set to, and [drop] injects a link loss so the
 * manager's reconnect / bond-loss paths can be driven deterministically.
 */
class FakeMeshLink : MeshLink {
    var listener: MeshLinkListener? = null
        private set
    var failConnectWith: Throwable? = null

    /** Number of [connect] calls that throw before a subsequent call is allowed to succeed. */
    var remainingConnectFailures: Int = 0

    /** Parks [connect] until completed, standing in for a GATT connect still in flight. */
    var connectGate: CompletableDeferred<Unit>? = null
    var connectCount = 0
        private set
    var disconnectCount = 0
        private set
    var disconnected = false
        private set
    val sent = mutableListOf<ByteArray>()

    /**
     * GATT is down without [drop] having fired, as when the drop collector died while backgrounded.
     * Foreground health reads [isConnected] and tears the Ready session down.
     */
    var silentlyDead = false

    override val isConnected: Boolean
        get() = !silentlyDead

    override fun setListener(listener: MeshLinkListener?) {
        this.listener = listener
    }

    override suspend fun connect() {
        connectCount++
        connectGate?.await()
        if (remainingConnectFailures > 0) {
            remainingConnectFailures--
            throw failConnectWith ?: MeshLinkConnectException("boom")
        }
        failConnectWith?.let { throw it }
    }

    override fun sendBytes(bytes: ByteArray, withoutResponse: Boolean) {
        sent += bytes
    }

    /** When set, [disconnect] records the call and then throws. */
    var disconnectFailure: Throwable? = null

    override suspend fun disconnect() {
        disconnectCount++
        disconnected = true
        disconnectFailure?.let { throw it }
    }

    /** Injects a link drop, as the real transport's state monitor would. */
    fun drop(reason: LinkDropReason) {
        listener?.onDisconnected(reason)
    }
}

/**
 * An in-memory WiFi [MeshLink] built by the manager's `wifiLinkFactory`. It captures the injected
 * [heartbeat] probe so a test can invoke it directly (the real `TcpMeshLink` runs it on a 30s loop) and
 * records the host/port it was asked to reach.
 */
class FakeWiFiLink(val host: String, val port: Int, val heartbeat: suspend () -> Unit) : MeshLink {
    override val supportsPipelinedReads: Boolean get() = true

    var listener: MeshLinkListener? = null
        private set
    var failConnectWith: Throwable? = null
    var connectCount = 0
        private set

    override fun setListener(listener: MeshLinkListener?) {
        this.listener = listener
    }

    override suspend fun connect() {
        connectCount++
        failConnectWith?.let { throw it }
    }

    override fun sendBytes(bytes: ByteArray, withoutResponse: Boolean) = Unit

    override suspend fun disconnect() = Unit

    /** Injects a link drop, as the transport's staleness watchdog would. */
    fun drop(reason: LinkDropReason) {
        listener?.onDisconnected(reason)
    }
}

/**
 * An in-memory [MeshSession] returning canned device data, so the manager's ceremony can run without
 * the Swift bridge. [start] does not need [reportConnected]; the inbound hooks are recorded no-ops.
 */
open class FakeMeshSession(
    private val selfInfo: SelfInfo = defaultSelfInfo(),
    private val capabilities: DeviceCapabilities = defaultCapabilities(),
    contacts: List<MeshContact> = emptyList(),
    private val sentInfo: MessageSentInfo = defaultSentInfo(),
    /** Queued getMessage results drained by [getMessage]; empty yields NoMoreMessages immediately. */
    private val backlog: MutableList<GetMessageResult> = mutableListOf(),
) : MeshSession {
    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    /** Live collectors of [events]: an event emitted before the graph subscribes reaches nobody. */
    val eventSubscribers: kotlinx.coroutines.flow.StateFlow<Int> get() = _events.subscriptionCount

    var started = false
        private set
    var stopped = false
        private set
    var closeCount = 0
        private set
    var closed = false
        private set
    var getTimeCount = 0
        private set
    val getTimeFailures: MutableList<Throwable> = mutableListOf()
    var getMessageCount = 0
        private set
    var autoFetchStartCount = 0
        private set
    var autoFetchStopCount = 0
        private set
    var getChannelsCallCount = 0
    var getChannelCallCount = 0
        private set

    /** The contact table this radio reports; settable so a test can model an eviction between connects. */
    var contacts: List<MeshContact> = contacts

    /** The `since` watermark each [getContacts] call carried, in order. A null entry is a full sync. */
    val getContactsSince = mutableListOf<Long?>()

    /** Queued [getContacts] failures, one consumed per call, so a later pull can succeed. */
    val contactsFailures = mutableListOf<Throwable>()

    /** The epoch seconds this radio's clock reports to [getTime]. */
    var deviceTimeEpochSeconds: Long = FAKE_TIME

    /**
     * Ordered record of the session calls the connect ceremony's ordering depends on: every backlog
     * [getMessage] must precede the [startAutoMessageFetching] that hands the drain over to the session.
     */
    val drainCallOrder = mutableListOf<String>()

    /** When set, [getContacts] suspends on it, so a test can hold the ceremony in the Syncing state. */
    var contactsGate: CompletableDeferred<Unit>? = null

    /**
     * When set, [getTime] suspends on it. The connect path reads the clock only after Ready, so a
     * test can cancel the caller while the session is already live.
     */
    var getTimeGate: CompletableDeferred<Unit>? = null

    /** When set, [start] suspends on it, so a test can hold the handshake while a second connect supersedes. */
    var startGate: CompletableDeferred<Unit>? = null
    var startEntered = false
        private set

    /** When set, [start] throws it — a session-rebuild failure over a link that connected fine. */
    var failStartWith: Throwable? = null

    /** When set, [queryDevice] throws it — handshake got past app-start on a live link. */
    var failQueryWith: Throwable? = null

    /** When set, [queryDevice] suspends on it, so a test can hold the handshake after start. */
    var queryDeviceGate: CompletableDeferred<Unit>? = null
    val sentMessages = mutableListOf<String>()

    /** Recorded device-config writes for the settings tests. */
    var lastDevicePin: Long? = null
        private set
    var rebootCount = 0
        private set
    var lastPathHashMode: Int? = null
        private set
    var lastOtherParams: DeviceOtherParams? = null
        private set
    var lastRadio: RecordedRadio? = null
        private set
    var lastAutoAddConfig: AutoAddConfig? = null
        private set
    var getAutoAddConfigCount = 0
        private set

    /** The auto-add policy this radio reports; the connect path persists it onto the Device row. */
    var autoAddConfig: AutoAddConfig = AutoAddConfig(bitmask = 0, maxHops = 0)

    /** When set, [getAutoAddConfig] throws it — firmware that rejects the command. */
    var failAutoAddConfigWith: Throwable? = null

    /** When set, the next [selfInfoFailuresRemaining] [getSelfInfo] calls throw it. */
    var selfInfoFailure: Throwable? = null

    /** How many upcoming [getSelfInfo] calls throw [selfInfoFailure]. */
    var selfInfoFailuresRemaining: Int = 0

    /** How many times a caller asked the radio for self-info, including a thrown read. */
    var selfInfoReadCount: Int = 0
        private set

    /** When set, [setAutoAddConfig] records the request but the radio keeps its previous policy. */
    var refuseAutoAddConfigWrite = false

    /** When set, [setOtherParams] records the request but the radio keeps its previous values. */
    var refuseOtherParamsWrite = false

    /** When set, [setPathHashMode] records the request but the radio keeps its previous mode. */
    var refusePathHashModeWrite = false

    /** The other-params the radio is running, which [getSelfInfo] reads back. */
    private var acceptedOtherParams: DeviceOtherParams? = null

    /** The path-hash mode the radio is running, which [queryDevice] reads back. */
    private var acceptedPathHashMode: Int? = null

    /** The repeat mode the radio accepted, which [queryDevice] reads back like the real firmware. */
    private var acceptedClientRepeat: Boolean? = null

    /** When set, the radio reports this repeat mode instead of the one asked for (a refused write). */
    var reportedClientRepeat: Boolean? = null

    /** When set, [reboot] throws it (e.g. an expected disconnect after a PIN write). */
    var failRebootWith: Throwable? = null

    /** One recorded [setRadio] call (wire units: frequency MHz, bandwidth kHz). */
    data class RecordedRadio(
        val frequency: Double,
        val bandwidth: Double,
        val spreadingFactor: Int,
        val codingRate: Int,
        val clientRepeat: Boolean?,
    )

    override suspend fun start(): SelfInfo {
        failStartWith?.let { throw it }
        startEntered = true
        startGate?.await()
        started = true
        return selfInfo
    }

    // Capabilities reflect whatever path-hash mode the radio accepted since the handshake: the verified
    // write reads its own value back here, so a fake answering with the original snapshot would fail it.
    override suspend fun queryDevice(): DeviceCapabilities {
        failQueryWith?.let { throw it }
        queryDeviceGate?.await()
        return capabilities.copy(
            pathHashMode = acceptedPathHashMode ?: capabilities.pathHashMode,
            clientRepeat = reportedClientRepeat ?: acceptedClientRepeat ?: capabilities.clientRepeat,
        )
    }

    override suspend fun getTime(): Long {
        getTimeCount++
        getTimeGate?.await()
        if (getTimeFailures.isNotEmpty()) throw getTimeFailures.removeAt(0)
        return deviceTimeEpochSeconds
    }

    override suspend fun getContacts(since: Long?): List<MeshContact> {
        getContactsSince += since
        contactsGate?.await()
        if (contactsFailures.isNotEmpty()) throw contactsFailures.removeAt(0)
        return contacts
    }

    override suspend fun sendMessage(
        destination: ByteArray,
        text: String,
        timestamp: Long,
        attempt: Int,
    ): MessageSentInfo {
        sentMessages += text
        return sentInfo
    }

    override suspend fun sendChannelMessage(
        channel: Int,
        text: String,
        timestamp: Long,
        floodScope: ResolvedFloodScope,
    ) = sendChannelMessage(channel, text, timestamp)

    override suspend fun sendChannelMessage(channel: Int, text: String, timestamp: Long) {
        sentMessages += text
    }

    /** Configured slots answered by [getChannel]; an absent slot answers as an empty (unconfigured) one. */
    val channelSlots = mutableMapOf<Int, ChannelInfo>()

    /** Failures queued per slot, one consumed per [getChannel] call, so a later read can succeed. */
    val channelSlotFailures = mutableMapOf<Int, MutableList<Throwable>>()

    /** The pipelined [getChannels] answer; null reports every requested slot as unanswered. */
    var channelBatch: CommandResult.ChannelsResult? = null

    /** Optional hook at the start of [getChannels] (e.g. mid-connect 0x80 injection). */
    var onGetChannels: (suspend () -> Unit)? = null

    /** Public keys every [getContact] was called with, in order. */
    val getContactKeys = mutableListOf<ByteArray>()

    override suspend fun getChannel(index: Int): ChannelInfo {
        getChannelCallCount++
        channelSlotFailures[index]?.takeIf { it.isNotEmpty() }?.let { throw it.removeAt(0) }
        return channelSlots[index] ?: ChannelInfo(index = index, name = "", secret = ByteArray(CHANNEL_SECRET_BYTES))
    }

    override suspend fun getChannels(indices: ByteArray): CommandResult.ChannelsResult {
        getChannelsCallCount++
        onGetChannels?.invoke()
        return channelBatch ?: CommandResult.ChannelsResult(received = emptyList(), missing = emptyList())
    }

    override suspend fun setChannel(index: Int, name: String, secret: ByteArray) = Unit

    override suspend fun getMessage(): GetMessageResult {
        getMessageCount++
        drainCallOrder += GET_MESSAGE
        return if (backlog.isEmpty()) GetMessageResult.NoMoreMessages else backlog.removeAt(0)
    }

    override suspend fun startAutoMessageFetching() {
        autoFetchStartCount++
        drainCallOrder += START_AUTO_FETCH
    }

    /** When set, [stopAutoMessageFetching] records the call and then throws. */
    var stopAutoFetchFailure: Throwable? = null

    override suspend fun stopAutoMessageFetching() {
        autoFetchStopCount++
        stopAutoFetchFailure?.let { throw it }
    }

    override suspend fun getContact(publicKey: ByteArray): MeshContact? {
        getContactKeys += publicKey
        return contacts.firstOrNull { it.publicKey.contentEquals(publicKey) }
    }

    override suspend fun resetPath(publicKey: ByteArray) = Unit

    override suspend fun addContact(contact: MeshContact) = Unit

    /** Public keys every [removeContact] was called with, in order. */
    val removedContactKeys = mutableListOf<ByteArray>()

    override suspend fun removeContact(publicKey: ByteArray) {
        removedContactKeys += publicKey
    }

    override suspend fun changeContactFlags(contact: MeshContact, flags: Int) = Unit

    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo = sentInfo

    override suspend fun sendTrace(tag: Long?, authCode: Long?, flags: Int, path: ByteArray?): MessageSentInfo =
        sentInfo

    override suspend fun shareContact(publicKey: ByteArray) = Unit

    // The remote-node access ops: this ceremony fake models only login (the re-auth path a link drop
    // drives); the other send ops record nothing and the request-response ops are unmodelled.

    /** Public keys every [sendLogin] was addressed to, in order. */
    val loginDestinations = mutableListOf<ByteArray>()

    /** Whether a login is answered with the `loginSuccess` push a live radio would return. */
    var answerLoginWithSuccess = true

    override suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo {
        loginDestinations += destination
        if (answerLoginWithSuccess) {
            _events.emit(
                MeshEvent.LoginSuccess(
                    one.meshcore.shared.meshcore.LoginInfo(
                        permissions = ADMIN_PERMISSIONS,
                        isAdmin = true,
                        publicKeyPrefix = destination.copyOf(PUBLIC_KEY_PREFIX_BYTES),
                    ),
                ),
            )
        }
        return sentInfo
    }

    /** Public keys every [sendLogout] was addressed to, in order. */
    val logoutDestinations = mutableListOf<ByteArray>()

    override suspend fun sendLogout(destination: ByteArray) {
        logoutDestinations += destination
    }

    override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): MessageSentInfo = sentInfo

    override suspend fun sendCommand(destination: ByteArray, command: String, timestamp: Long): MessageSentInfo =
        sentInfo

    override suspend fun requestStatus(publicKey: ByteArray, type: ContactType): StatusResponse =
        throw NotImplementedError("node status not modelled by the ceremony fake")

    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse =
        throw NotImplementedError("node telemetry not modelled by the ceremony fake")

    override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse =
        throw NotImplementedError("node owner info not modelled by the ceremony fake")

    override suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: Int,
        offset: Int,
        orderBy: Int,
        pubkeyPrefixLength: Int,
    ): NeighboursResponse = throw NotImplementedError("node neighbours not modelled by the ceremony fake")

    // The canned handshake snapshot, reflecting whatever radio parameters, name and other-params the
    // radio accepted since. A verified write reads its own value back here, so a fake that answered with
    // the original snapshot would fail every verification for the wrong reason.
    override suspend fun getSelfInfo(): SelfInfo {
        selfInfoReadCount++
        val failure = selfInfoFailure
        if (failure != null && selfInfoFailuresRemaining > 0) {
            selfInfoFailuresRemaining--
            throw failure
        }
        val other = acceptedOtherParams
        return selfInfo.copy(
            name = lastSetName ?: selfInfo.name,
            radioFrequency = lastRadio?.frequency ?: selfInfo.radioFrequency,
            radioBandwidth = lastRadio?.bandwidth ?: selfInfo.radioBandwidth,
            radioSpreadingFactor = lastRadio?.spreadingFactor ?: selfInfo.radioSpreadingFactor,
            radioCodingRate = lastRadio?.codingRate ?: selfInfo.radioCodingRate,
            manualAddContacts = other?.manualAddContacts ?: selfInfo.manualAddContacts,
            telemetryModeEnvironment = other?.telemetryModeEnvironment ?: selfInfo.telemetryModeEnvironment,
            telemetryModeLocation = other?.telemetryModeLocation ?: selfInfo.telemetryModeLocation,
            telemetryModeBase = other?.telemetryModeBase ?: selfInfo.telemetryModeBase,
            advertisementLocationPolicy = other?.advertisementLocationPolicy ?: selfInfo.advertisementLocationPolicy,
            multiAcks = other?.multiAcks ?: selfInfo.multiAcks,
            txPower = lastTxPower ?: selfInfo.txPower,
        )
    }

    /** How many times connect-time RX-log decrypt asked the radio for its private key. */
    var exportPrivateKeyCount = 0
        private set

    override suspend fun exportPrivateKey(): ByteArray {
        exportPrivateKeyCount++
        return ByteArray(PRIVATE_KEY_BYTES)
    }

    override suspend fun importPrivateKey(privateKey: ByteArray) = Unit

    /** The last name written, which [getSelfInfo] then reads back. */
    var lastSetName: String? = null
        private set

    /** When set, [setName] records the write but the radio keeps its previous name. */
    var refuseNameWrite = false

    override suspend fun setName(name: String) {
        if (!refuseNameWrite) lastSetName = name
    }

    override suspend fun setCoordinates(latitude: Double, longitude: Double) = Unit

    var lastSetTimeEpoch: Long? = null
        private set

    override suspend fun setTime(epochSeconds: Long) {
        lastSetTimeEpoch = epochSeconds
    }

    override suspend fun setOtherParams(params: DeviceOtherParams) {
        lastOtherParams = params
        if (!refuseOtherParamsWrite) acceptedOtherParams = params
    }

    override suspend fun setDevicePin(pin: Long) {
        lastDevicePin = pin
    }

    override suspend fun reboot() {
        rebootCount++
        failRebootWith?.let { throw it }
    }

    override suspend fun setPathHashMode(mode: Int) {
        lastPathHashMode = mode
        if (!refusePathHashModeWrite) acceptedPathHashMode = mode
    }

    /**
     * One recorded session-scoped flood-key push. Only the frame-selecting fields are kept — the resolver
     * never emits a raw key, so [scopeKeySize] captures the (always empty) key length without a ByteArray
     * that would break value equality. [UNSCOPED] stands in for sub-command 1.
     */
    data class RecordedFloodScope(val scopeType: String, val scopeText: String, val scopeKeySize: Int = 0) {
        companion object {
            val UNSCOPED = RecordedFloodScope("unscoped", "")
        }
    }

    /** Ordered session-scoped flood-key pushes, for the channel-open / info-sheet push tests. */
    val floodScopeCalls = mutableListOf<RecordedFloodScope>()

    override suspend fun setFloodScope(scopeType: String, scopeText: String, scopeKey: ByteArray) {
        floodScopeCalls += RecordedFloodScope(scopeType, scopeText, scopeKey.size)
    }

    override suspend fun setFloodScopeUnscoped() {
        floodScopeCalls += RecordedFloodScope.UNSCOPED
    }

    override suspend fun setRadio(
        frequency: Double,
        bandwidth: Double,
        spreadingFactor: Int,
        codingRate: Int,
        clientRepeat: Boolean?,
    ) {
        lastRadio = RecordedRadio(frequency, bandwidth, spreadingFactor, codingRate, clientRepeat)
        if (clientRepeat != null) acceptedClientRepeat = clientRepeat
    }

    /** The last TX power written, which [getSelfInfo] then reads back. */
    var lastTxPower: Int? = null
        private set

    /** When set, [setTxPower] records nothing and the radio keeps its previous level. */
    var refuseTxPowerWrite = false

    override suspend fun setTxPower(power: Int) {
        if (!refuseTxPowerWrite) lastTxPower = power
    }

    override suspend fun getAutoAddConfig(): AutoAddConfig {
        getAutoAddConfigCount++
        failAutoAddConfigWith?.let { throw it }
        return autoAddConfig
    }

    override suspend fun setAutoAddConfig(bitmask: Int, maxHops: Int) {
        lastAutoAddConfig = AutoAddConfig(bitmask, maxHops)
        if (!refuseAutoAddConfigWrite) autoAddConfig = AutoAddConfig(bitmask, maxHops)
    }

    /** When set, [stop] records the call and then throws. */
    var stopFailure: Throwable? = null

    override suspend fun stop() {
        stopped = true
        stopFailure?.let { throw it }
    }

    override fun reportConnected() = Unit

    override fun reportConnectFailed(message: String) = Unit

    override fun reportDisconnected() = Unit

    override fun deliverReceived(bytes: ByteArray) = Unit

    override fun close() {
        closeCount++
        closed = true
    }

    /** Pushes an event onto the session's stream, as an inbound frame would. */
    suspend fun emit(event: MeshEvent) = _events.emit(event)

    companion object {
        /** [drainCallOrder] marker for one backlog read. */
        const val GET_MESSAGE = "getMessage"

        /** [drainCallOrder] marker for the hand-over to the session's own drain. */
        const val START_AUTO_FETCH = "startAutoMessageFetching"

        /** The clock a radio reports until a test moves it. */
        const val FAKE_TIME = 1_700_000_000L

        private const val CHANNEL_SECRET_BYTES = 16
        private const val PRIVATE_KEY_BYTES = 32

        /** The prefix width every push carries instead of the full key. */
        private const val PUBLIC_KEY_PREFIX_BYTES = 6

        /** Admin, so a re-auth never reads as a permission downgrade. */
        private val ADMIN_PERMISSIONS = one.meshcore.shared.model.RoomPermissionLevel.ADMIN.raw
    }
}

fun defaultSelfInfo(publicKey: ByteArray = ByteArray(32) { 0x01 }): SelfInfo = SelfInfo(
    advertisementType = 1,
    txPower = 22,
    maxTxPower = 22,
    publicKey = publicKey,
    latitude = 0.0,
    longitude = 0.0,
    multiAcks = 0,
    advertisementLocationPolicy = 0,
    telemetryModeEnvironment = 0,
    telemetryModeLocation = 0,
    telemetryModeBase = 0,
    manualAddContacts = false,
    radioFrequency = 915.0,
    radioBandwidth = 250.0,
    radioSpreadingFactor = 7,
    radioCodingRate = 5,
    name = "Test",
)

/** A model string [detectDevicePlatform] resolves to nRF52, the pipelined-over-Bluetooth case. */
const val NRF52_MODEL = "RAK 4631"

/** A model string [detectDevicePlatform] resolves to ESP32, which stays serial over Bluetooth. */
const val ESP32_MODEL = "Heltec V3"

fun defaultCapabilities(): DeviceCapabilities = DeviceCapabilities(
    firmwareVersion = 10,
    maxContacts = 100,
    maxChannels = 8,
    blePin = 0,
    firmwareBuild = "01 Jan 2026",
    // A real nRF52 model string: the ceremony's channel read is derived from it, and nRF52 over Bluetooth
    // is the pipelined case.
    model = NRF52_MODEL,
    version = "v1.15.0",
    clientRepeat = false,
    pathHashMode = 0,
)

fun defaultSentInfo(): MessageSentInfo =
    MessageSentInfo(route = 0, expectedAck = byteArrayOf(0x11, 0x22, 0x33, 0x44), suggestedTimeoutMs = 5000)

fun sampleContact(publicKey: ByteArray, name: String): MeshContact = MeshContact(
    id = publicKey.toLowercaseHex(),
    publicKey = publicKey,
    typeRawValue = 1,
    flags = 0,
    outPathLength = 0,
    outPath = ByteArray(0),
    advertisedName = name,
    lastAdvertisement = 0,
    latitude = 0.0,
    longitude = 0.0,
    lastModified = 0,
)
