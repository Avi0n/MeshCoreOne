package one.meshcore.shared.room

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.remotenode.testStatusResponse
import one.meshcore.shared.remotenode.testUuid
import kotlin.time.Duration

/** A room-server session DTO fixture; read-write + connected by default so `canPost` holds. */
@Suppress("LongParameterList")
fun roomSession(
    id: Uuid = testUuid(1),
    radioID: Uuid = testUuid(99),
    publicKey: ByteArray = testPublicKey(),
    isConnected: Boolean = true,
    permissionLevel: RoomPermissionLevel = RoomPermissionLevel.READ_WRITE,
    unreadCount: Int = 0,
    lastSyncTimestamp: Long = 0,
): RemoteNodeSessionDto = RemoteNodeSessionDto(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = "Test Room",
    role = RemoteNodeRole.ROOM_SERVER,
    latitude = 0.0,
    longitude = 0.0,
    isConnected = isConnected,
    permissionLevel = permissionLevel,
    unreadCount = unreadCount,
    notificationLevel = NotificationLevel.ALL,
    isPinned = false,
    neighborCount = 0,
    lastSyncTimestamp = lastSyncTimestamp,
)

/** A room-server contact fixture for [RoomServerService.joinRoom]. */
fun roomContact(
    radioID: Uuid = testUuid(99),
    publicKey: ByteArray = testPublicKey(),
    name: String = "Test Room",
): ContactDto = ContactDto(
    id = testUuid(50),
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    typeRawValue = ContactType.ROOM.raw,
    flags = 0,
    outPathLength = 0,
    outPath = ByteArray(0),
    lastAdvertTimestamp = 0,
    latitude = 0.0,
    longitude = 0.0,
    lastModified = 0,
    isBlocked = false,
    isMuted = false,
    isFavorite = false,
    unreadCount = 0,
)

/** A room-message DTO fixture; an inbound message from a named author by default. */
@Suppress("LongParameterList")
fun roomMessage(
    id: Uuid,
    sessionID: Uuid = testUuid(1),
    text: String = "hello",
    timestamp: Long = 1_720_000_000L,
    isFromSelf: Boolean = false,
    authorName: String? = "Alice",
    statusRawValue: Int = one.meshcore.shared.model.MessageStatus.DELIVERED.raw,
    authorKeyPrefix: ByteArray = byteArrayOf(1, 2, 3, 4),
): RoomMessageDto = RoomMessageDto(
    id = id,
    sessionID = sessionID,
    authorKeyPrefix = authorKeyPrefix,
    text = text,
    timestamp = timestamp,
    createdAt = timestamp.toDouble(),
    isFromSelf = isFromSelf,
    deduplicationKey = "$timestamp-$text",
    statusRawValue = statusRawValue,
    retryAttempt = 0,
    maxRetryAttempts = 0,
    authorName = authorName,
)

/** A firmware send outcome with a decodable little-endian ACK code. */
fun roomSentInfo(ack: ByteArray = byteArrayOf(1, 2, 3, 4), suggestedTimeoutMs: Long = 1234): MessageSentInfo =
    MessageSentInfo(route = 0, expectedAck = ack, suggestedTimeoutMs = suggestedTimeoutMs)

/** A minimal telemetry frame for [RoomAdminSession] fakes. */
fun roomTelemetry(publicKey: ByteArray = testPublicKey()): TelemetryResponse =
    TelemetryResponse(publicKeyPrefix = publicKey.copyOf(6), tag = null, rawData = ByteArray(0))

/**
 * A room status frame in the shape production receives: the request carries the room node type, so the
 * bridge decodes it with the room-server layout and the counters arrive in their own fields, with no
 * RX airtime or receive-error reading to be had from a room server.
 */
fun roomStatus(posted: Int = 42, pushed: Int = 7): StatusResponse = testStatusResponse().copy(
    layout = "roomServer",
    rxAirtime = 0,
    receiveErrors = 0,
    roomServerPostedCount = posted,
    roomServerPostPushCount = pushed,
)

/**
 * A [RoomServerSession] fake recording every post and returning a configurable outcome: a non-null
 * [sendResult] models a delivered ACK, null models exhausted retries with no ACK (`.sent`), and a set
 * [sendError] models a thrown send (`.failed`). [onSend] optionally suspends inside the post so a test can
 * observe an in-flight retry.
 */
class FakeRoomServerSession : RoomServerSession {
    val sendCalls = mutableListOf<Triple<ByteArray, String, Long>>()
    val pathDiscoveryCalls = mutableListOf<ByteArray>()
    val historySyncCalls = mutableListOf<ByteArray>()

    var sendResult: MessageSentInfo? = roomSentInfo()
    var sendError: Throwable? = null
    var historySyncError: Throwable? = null
    var pathDiscoveryError: Throwable? = null
    var onSend: (suspend () -> Unit)? = null

    override suspend fun sendMessageWithRetry(
        destination: ByteArray,
        text: String,
        timestamp: Long,
        retry: RoomMessageRetry,
    ): MessageSentInfo? {
        sendCalls += Triple(destination, text, timestamp)
        onSend?.invoke()
        sendError?.let { throw it }
        return sendResult
    }

    override suspend fun sendPathDiscovery(destination: ByteArray) {
        pathDiscoveryCalls += destination
        pathDiscoveryError?.let { throw it }
    }

    override suspend fun requestHistorySync(destination: ByteArray) {
        historySyncCalls += destination
        historySyncError?.let { throw it }
    }
}

/** A [RoomAdminSession] fake recording every device op and returning configurable responses. */
class FakeRoomAdminSession : RoomAdminSession {
    val statusCalls = mutableListOf<ByteArray>()
    val telemetryCalls = mutableListOf<ByteArray>()
    val commandCalls = mutableListOf<Pair<ByteArray, String>>()
    val rawCommandCalls = mutableListOf<Pair<ByteArray, String>>()

    var statusResponse: StatusResponse = roomStatus()
    var statusError: Throwable? = null
    var telemetryResponse: TelemetryResponse = roomTelemetry()
    var telemetryError: Throwable? = null
    var commandResponse: String = "ok"

    override suspend fun requestStatus(publicKey: ByteArray, timeout: Duration): StatusResponse {
        statusCalls += publicKey
        statusError?.let { throw it }
        return statusResponse
    }

    override suspend fun requestTelemetry(publicKey: ByteArray, timeout: Duration): TelemetryResponse {
        telemetryCalls += publicKey
        telemetryError?.let { throw it }
        return telemetryResponse
    }

    override suspend fun sendCommand(publicKey: ByteArray, command: String, timeout: Duration): String {
        commandCalls += publicKey to command
        return commandResponse
    }

    override suspend fun sendRawCommand(publicKey: ByteArray, command: String, timeout: Duration): String {
        rawCommandCalls += publicKey to command
        return commandResponse
    }
}
