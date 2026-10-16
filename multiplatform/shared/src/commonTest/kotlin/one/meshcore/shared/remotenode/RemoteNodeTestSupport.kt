package one.meshcore.shared.remotenode

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.meshcore.ContactMessage
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.LoginInfo
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.meshcore.Neighbour
import one.meshcore.shared.meshcore.NeighboursResponse
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid

/** A test [Uuid] with a stable, readable value. */
fun testUuid(n: Int): Uuid = Uuid.normalized("A0000000-0000-0000-0000-${n.toString().padStart(12, '0')}")

/** A 32-byte public key filled with [seed]. */
fun testPublicKey(seed: Int = 0x42): ByteArray = ByteArray(32) { seed.toByte() }

/** A remote-node session DTO fixture; a repeater by default. */
@Suppress("LongParameterList")
fun testSession(
    id: Uuid = testUuid(1),
    radioID: Uuid = testUuid(99),
    publicKey: ByteArray = testPublicKey(),
    role: RemoteNodeRole = RemoteNodeRole.REPEATER,
    isConnected: Boolean = false,
    permissionLevel: RoomPermissionLevel = RoomPermissionLevel.GUEST,
    lastSyncTimestamp: Long = 0,
): RemoteNodeSessionDto = RemoteNodeSessionDto(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = "Test Node",
    role = role,
    latitude = 0.0,
    longitude = 0.0,
    isConnected = isConnected,
    permissionLevel = permissionLevel,
    unreadCount = 0,
    notificationLevel = NotificationLevel.ALL,
    isPinned = false,
    neighborCount = 0,
    lastSyncTimestamp = lastSyncTimestamp,
)

/** A contact DTO fixture for [createSession]; a repeater by default. */
@Suppress("LongParameterList")
fun testContact(
    radioID: Uuid = testUuid(99),
    publicKey: ByteArray = testPublicKey(),
    type: one.meshcore.shared.model.ContactType = one.meshcore.shared.model.ContactType.REPEATER,
    name: String = "Test Node",
    latitude: Double = 0.0,
    longitude: Double = 0.0,
    isFavorite: Boolean = false,
): ContactDto = ContactDto(
    id = testUuid(50),
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    typeRawValue = type.raw,
    flags = 0,
    outPathLength = 0,
    outPath = ByteArray(0),
    lastAdvertTimestamp = 0,
    latitude = latitude,
    longitude = longitude,
    lastModified = 0,
    isBlocked = false,
    isMuted = false,
    isFavorite = isFavorite,
    unreadCount = 0,
)

/** A firmware-suggested send outcome; [suggestedTimeoutMs] drives the login timeout derivation. */
fun testSentInfo(suggestedTimeoutMs: Long = 1000): MessageSentInfo =
    MessageSentInfo(route = 0, expectedAck = ByteArray(0), suggestedTimeoutMs = suggestedTimeoutMs)

/** A login-success push payload for [publicKey]'s 6-byte prefix. */
fun testLoginInfo(
    publicKey: ByteArray = testPublicKey(),
    isAdmin: Boolean = true,
    permissions: Int = 0,
    serverTime: Long? = null,
): LoginInfo = LoginInfo(
    permissions = permissions,
    isAdmin = isAdmin,
    publicKeyPrefix = publicKey.copyOf(6),
    serverTime = serverTime,
)

/** A repeater status frame with the given per-type counters. */
@Suppress("LongParameterList")
fun testStatusResponse(
    publicKey: ByteArray = testPublicKey(),
    battery: Int = 3850,
    lastSNR: Double = 8.5,
    lastRSSI: Int = -87,
    noiseFloor: Int = -120,
    uptime: Long = 3600,
): StatusResponse = StatusResponse(
    layout = "repeater",
    publicKeyPrefix = publicKey.copyOf(6),
    battery = battery,
    txQueueLength = 1,
    noiseFloor = noiseFloor,
    lastRSSI = lastRSSI,
    packetsReceived = 1000,
    packetsSent = 500,
    airtime = 500,
    uptime = uptime,
    sentFlood = 200,
    sentDirect = 100,
    receivedFlood = 400,
    receivedDirect = 300,
    fullEvents = 7,
    lastSNR = lastSNR,
    directDuplicates = 11,
    floodDuplicates = 22,
    rxAirtime = 100,
    receiveErrors = 3,
)

/**
 * A [NodeSession] fake that records every command and lets a test drive login-result pushes and per-command
 * outcomes. Mirrors the FakeChannelSession pattern.
 */
class FakeNodeSession : NodeSession {
    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    val sendLoginCalls = mutableListOf<Pair<ByteArray, String>>()
    val sendLogoutCalls = mutableListOf<ByteArray>()
    val sendKeepAliveCalls = mutableListOf<Pair<ByteArray, Long>>()
    val requestStatusCalls = mutableListOf<ByteArray>()
    val requestTelemetryCalls = mutableListOf<ByteArray>()
    val requestOwnerInfoCalls = mutableListOf<ByteArray>()
    val sendCommandCalls = mutableListOf<Pair<ByteArray, String>>()
    val getMessageCalls = mutableListOf<Unit>()
    val getMessageResults = mutableListOf<GetMessageResult>()
    val addContactCalls = mutableListOf<MeshContact>()
    val resetPathCalls = mutableListOf<ByteArray>()

    var sentInfo: MessageSentInfo = testSentInfo()
    var sendLoginError: Throwable? = null

    /** Per-call login error keyed by 1-based call index; drives the notFound auto-heal (throw on call 1). */
    var sendLoginErrorForCall: ((Int) -> Throwable?)? = null
    var addContactError: Throwable? = null
    var resetPathError: Throwable? = null
    var sendLogoutError: Throwable? = null
    var keepAliveError: Throwable? = null
    var statusResponse: StatusResponse = testStatusResponse()
    var statusError: Throwable? = null

    /** Per-call status error keyed by 1-based call index; drives the transient-retry ladder. */
    var statusErrorForCall: ((Int) -> Throwable?)? = null
    var statusDelayMs: Long = 0
    var telemetryResponse: TelemetryResponse = testTelemetryResponse()
    var telemetryError: Throwable? = null
    var telemetryDelayMs: Long = 0
    var ownerInfoResponse: OwnerInfoResponse = testOwnerInfoResponse()
    var ownerInfoError: Throwable? = null
    var ownerInfoDelayMs: Long = 0
    var sendCommandError: Throwable? = null
    val requestNeighboursCalls = mutableListOf<Pair<Int, Int>>()
    var neighboursResponse: NeighboursResponse = testNeighboursResponse()
    var neighboursError: Throwable? = null

    /** Returns a page for a given `(count, offset)`; defaults to [neighboursResponse]. */
    var requestNeighboursHandler: ((count: Int, offset: Int) -> NeighboursResponse)? = null

    /** Invoked after each [sendLogin] with the running call count; lets a test push a result for a given attempt. */
    var onSendLogin: (suspend (callIndex: Int) -> Unit)? = null

    override suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo {
        sendLoginCalls += destination to password
        (sendLoginErrorForCall?.invoke(sendLoginCalls.size) ?: sendLoginError)?.let { throw it }
        onSendLogin?.invoke(sendLoginCalls.size)
        return sentInfo
    }

    override suspend fun sendLogout(destination: ByteArray) {
        sendLogoutCalls += destination
        sendLogoutError?.let { throw it }
    }

    override suspend fun addContact(contact: MeshContact) {
        addContactCalls += contact
        addContactError?.let { throw it }
    }

    override suspend fun resetPath(publicKey: ByteArray) {
        resetPathCalls += publicKey
        resetPathError?.let { throw it }
    }

    override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): MessageSentInfo {
        sendKeepAliveCalls += publicKey to syncSince
        keepAliveError?.let { throw it }
        return sentInfo
    }

    override suspend fun requestStatus(publicKey: ByteArray): StatusResponse {
        requestStatusCalls += publicKey
        if (statusDelayMs > 0) delay(statusDelayMs)
        (statusErrorForCall?.invoke(requestStatusCalls.size) ?: statusError)?.let { throw it }
        return statusResponse
    }

    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse {
        requestTelemetryCalls += publicKey
        if (telemetryDelayMs > 0) delay(telemetryDelayMs)
        telemetryError?.let { throw it }
        return telemetryResponse
    }

    override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse {
        requestOwnerInfoCalls += publicKey
        if (ownerInfoDelayMs > 0) delay(ownerInfoDelayMs)
        ownerInfoError?.let { throw it }
        return ownerInfoResponse
    }

    override suspend fun sendCommand(destination: ByteArray, command: String): MessageSentInfo {
        sendCommandCalls += destination to command
        sendCommandError?.let { throw it }
        return sentInfo
    }

    /**
     * Production GET_MSG also fans the frame through `session.events`. Queued results emit that way
     * so a CLI waiter that only pumps getMessage still completes, matching the Swift dispatcher.
     */
    override suspend fun getMessage(): GetMessageResult {
        getMessageCalls += Unit
        val result = if (getMessageResults.isEmpty()) {
            GetMessageResult.NoMoreMessages
        } else {
            getMessageResults.removeAt(0)
        }
        if (result is GetMessageResult.ContactMessageReceived) {
            _events.emit(MeshEvent.ContactMessageReceived(result.message))
        }
        return result
    }

    override suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: Int,
        offset: Int,
        orderBy: Int,
        pubkeyPrefixLength: Int,
    ): NeighboursResponse {
        requestNeighboursCalls += count to offset
        neighboursError?.let { throw it }
        return requestNeighboursHandler?.invoke(count, offset) ?: neighboursResponse
    }

    suspend fun emitLoginSuccess(info: LoginInfo) = _events.emit(MeshEvent.LoginSuccess(info))

    suspend fun emitLoginFailed(prefix: ByteArray?) = _events.emit(MeshEvent.LoginFailed(prefix))

    /** Pushes an inbound CLI response ([TextType.CLI_DATA]) from [senderPrefix]'s 6-byte prefix. */
    suspend fun emitCliResponse(text: String, senderPrefix: ByteArray = testPublicKey().copyOf(6)) =
        _events.emit(MeshEvent.ContactMessageReceived(testContactMessage(text, senderPrefix)))
}

/** A telemetry response fixture; [points] is what the bridge decodes the LPP frame into. */
fun testTelemetryResponse(
    publicKey: ByteArray = testPublicKey(),
    rawData: ByteArray = ByteArray(0),
    points: List<LPPDataPoint> = emptyList(),
): TelemetryResponse =
    TelemetryResponse(publicKeyPrefix = publicKey.copyOf(6), tag = null, rawData = rawData, points = points)

/** The decoded reading a repeater's self telemetry carries: channel 1, 3.99 V. */
fun voltagePoint(): LPPDataPoint = LPPDataPoint(1, LPPSensorType.VOLTAGE, LPPValue.FloatValue(3.99))

/** An owner-info response fixture. */
fun testOwnerInfoResponse(
    firmwareVersion: String = "v1.11.0",
    nodeName: String = "Test Node",
    ownerInfo: String = "owner",
): OwnerInfoResponse = OwnerInfoResponse(firmwareVersion = firmwareVersion, nodeName = nodeName, ownerInfo = ownerInfo)

/** A neighbour entry fixture. */
fun testNeighbour(prefix: Int = 0x01, snr: Double = 5.0, secondsAgo: Long = 60): Neighbour =
    Neighbour(publicKeyPrefix = ByteArray(6) { prefix.toByte() }, secondsAgo = secondsAgo, snr = snr)

/** A neighbours-response fixture with the given [neighbours] and node-reported [totalCount]. */
fun testNeighboursResponse(
    publicKey: ByteArray = testPublicKey(),
    totalCount: Int = 0,
    neighbours: List<Neighbour> = emptyList(),
): NeighboursResponse = NeighboursResponse(
    publicKeyPrefix = publicKey.copyOf(6),
    tag = ByteArray(0),
    totalCount = totalCount,
    neighbours = neighbours,
)

/** An inbound CLI-response contact message ([TextType.CLI_DATA]) for [senderPrefix]. */
fun testContactMessage(text: String, senderPrefix: ByteArray = testPublicKey().copyOf(6)): ContactMessage =
    ContactMessage(
        senderPublicKeyPrefix = senderPrefix,
        pathLength = 0,
        textType = TextType.CLI_DATA.raw,
        senderTimestamp = 0,
        signature = null,
        text = text,
        snr = null,
    )
