package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.channel.ChannelTimeoutException
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.chat.ChatDeviceException
import one.meshcore.shared.contact.ContactDeviceException
import one.meshcore.shared.meshcore.AutoAddConfig
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.CommandResult
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.DeviceOtherParams
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshBridgeException
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
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.InMemoryRemoteNodeStore
import one.meshcore.shared.remotenode.MeshError
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.room.RoomMessageRetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/** Verifies the commonMain node/room session adapters over a recording [MeshSession] double. */
class NodeSessionAdaptersTest {

    @Test
    fun nodeSessionAdapterMapsDeviceErrorToTypedMeshError() = runBlocking {
        val session = RecordingMeshSession().apply {
            loginError = MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, "device error code=5 (fileIOError)")
        }
        val adapter = NodeSessionAdapter(session) { 0L }
        try {
            adapter.sendLogin(ByteArray(32), "pw")
            fail("expected a MeshError")
        } catch (e: MeshError.DeviceError) {
            assertEquals(5, e.code)
        }
    }

    @Test
    fun nodeSessionAdapterStampsWallClockOnCommand() = runBlocking {
        val session = RecordingMeshSession()
        val adapter = NodeSessionAdapter(session) { 1_234L }
        adapter.sendCommand(ByteArray(32) { 1 }, "reboot")
        assertEquals(1_234L, session.lastCommandTimestamp)
        assertEquals("reboot", session.lastCommand)
    }

    @Test
    fun roomServerRetryReturnsSentInfoOnAck() = runBlocking {
        val session = RecordingMeshSession().apply { autoAck = true }
        val adapter = RoomServerSessionAdapter(session)
        val result = adapter.sendMessageWithRetry(
            destination = ByteArray(32) { 7 },
            text = "hi room",
            timestamp = 100L,
            retry = RoomMessageRetry(maxAttempts = 3, floodAfter = 2, maxFloodAttempts = 2),
        )
        assertTrue(result != null)
        assertEquals(1, session.sendMessageCount)
    }

    @Test
    fun roomServerRetryReturnsNullAndFloodsWhenNeverAcked() = runBlocking {
        val session = RecordingMeshSession().apply {
            autoAck = false
            ackTimeoutMs = 20
        }
        val adapter = RoomServerSessionAdapter(session)
        val result = adapter.sendMessageWithRetry(
            destination = ByteArray(32) { 7 },
            text = "hi room",
            timestamp = 100L,
            retry = RoomMessageRetry(maxAttempts = 3, floodAfter = 2, maxFloodAttempts = 2),
        )
        assertNull(result)
        assertEquals(3, session.sendMessageCount)
        // The path is reset to flood exactly once, when attempts reaches floodAfter.
        assertEquals(1, session.resetPathCount)
    }

    @Test
    fun aFailedRoomPathResetStaysOnDirectRetries() = runBlocking {
        val session = RecordingMeshSession().apply {
            autoAck = false
            ackTimeoutMs = 20
            resetPathError = IllegalStateException("radio down")
        }
        val result = RoomServerSessionAdapter(session).sendMessageWithRetry(
            destination = ByteArray(PUBLIC_KEY_SIZE) { 7 },
            text = "hi room",
            timestamp = 100L,
            retry = RoomMessageRetry(maxAttempts = 3, floodAfter = 0, maxFloodAttempts = 1),
        )
        assertNull(result)
        assertEquals(3, session.sendMessageCount)
        assertEquals(1, session.resetPathCount)
    }

    @Test
    fun aCancelledRoomPathResetStopsTheSend() = runBlocking {
        val cancelled = CancellationException("stopped")
        val session = RecordingMeshSession().apply {
            autoAck = false
            ackTimeoutMs = 20
            resetPathError = cancelled
        }
        val thrown = assertFailsWith<CancellationException> {
            RoomServerSessionAdapter(session).sendMessageWithRetry(
                destination = ByteArray(PUBLIC_KEY_SIZE) { 7 },
                text = "hi room",
                timestamp = 100L,
                retry = RoomMessageRetry(maxAttempts = 3, floodAfter = 0, maxFloodAttempts = 1),
            )
        }
        assertSame(cancelled, thrown)
        assertEquals(0, session.sendMessageCount)
        assertEquals(1, session.resetPathCount)
    }

    @Test
    fun aRoomPathResetErrorStopsTheSend() = runBlocking {
        val probe = RoomPathResetProbeError()
        val session = RecordingMeshSession().apply {
            autoAck = false
            ackTimeoutMs = 20
            resetPathError = probe
        }
        val thrown = assertFailsWith<RoomPathResetProbeError> {
            RoomServerSessionAdapter(session).sendMessageWithRetry(
                destination = ByteArray(PUBLIC_KEY_SIZE) { 7 },
                text = "hi room",
                timestamp = 100L,
                retry = RoomMessageRetry(maxAttempts = 3, floodAfter = 0, maxFloodAttempts = 1),
            )
        }
        assertSame(probe, thrown)
        assertEquals(0, session.sendMessageCount)
    }

    @Test
    fun nodeSessionAdapterRequestsStatusAsRepeater() = runBlocking {
        val session = RecordingMeshSession()
        val adapter = NodeSessionAdapter(session) { 0L }

        val status = adapter.requestStatus(ByteArray(32) { 3 })

        assertEquals(ContactType.REPEATER, session.statusRequests.single().second)
        assertEquals("repeater", status.layout)
        assertEquals(4321, status.rxAirtime)
        assertEquals(12, status.receiveErrors)
        assertNull(status.roomServerPostedCount)
    }

    @Test
    fun roomAdminSessionAdapterRequestsStatusAsRoom() = runBlocking {
        val session = RecordingMeshSession()
        val store = InMemoryRemoteNodeStore()
        val coordinator = NodeCliCoordinator(
            NodeSessionAdapter(session) { 0L },
            store,
            InMemoryNodePasswordStore(),
            CoroutineScope(Dispatchers.Unconfined),
        )
        val adapter = RoomAdminSessionAdapter(session, coordinator, store)

        val status = adapter.requestStatus(ByteArray(32) { 4 }, 5.seconds)

        assertEquals(ContactType.ROOM, session.statusRequests.single().second)
        assertEquals("roomServer", status.layout)
        assertEquals(17, status.roomServerPostedCount)
        assertEquals(9, status.roomServerPostPushCount)
        assertEquals(0, status.rxAirtime)
    }

    @Test
    fun roomServerHistorySyncRequestsStatusAsRoom() = runBlocking {
        val session = RecordingMeshSession()

        RoomServerSessionAdapter(session).requestHistorySync(ByteArray(32) { 5 })

        assertEquals(ContactType.ROOM, session.statusRequests.single().second)
    }

    @Test
    fun nodeSessionAdapterDoesNotResetPathOnStatusTimeout() = runBlocking {
        val session = RecordingMeshSession().apply { remainingStatusTimeouts = 1 }
        val adapter = NodeSessionAdapter(session) { 0L }

        try {
            adapter.requestStatus(ByteArray(32) { 3 })
            fail("expected a timeout")
        } catch (_: MeshError.Timeout) {
        }

        assertEquals(1, session.statusRequests.size)
        assertEquals(0, session.resetPathCount)
    }

    @Test
    fun roomAdminSessionAdapterDoesNotResetPathOnStatusTimeout() = runBlocking {
        val session = RecordingMeshSession().apply { remainingStatusTimeouts = 1 }
        val store = InMemoryRemoteNodeStore()
        val coordinator = NodeCliCoordinator(
            NodeSessionAdapter(session) { 0L },
            store,
            InMemoryNodePasswordStore(),
            CoroutineScope(Dispatchers.Unconfined),
        )
        val adapter = RoomAdminSessionAdapter(session, coordinator, store)

        try {
            adapter.requestStatus(ByteArray(32) { 4 }, 5.seconds)
            fail("expected a timeout")
        } catch (_: MeshError.Timeout) {
        }

        assertEquals(1, session.statusRequests.size)
        assertEquals(0, session.resetPathCount)
    }

    @Test
    fun scopedChannelAdapterForwardsScopeAndPreservesFirmwareErrors() = runBlocking {
        var forwarded: ResolvedFloodScope? = null
        val native = object : MeshSession by RecordingMeshSession() {
            override suspend fun sendChannelMessage(
                channel: Int,
                text: String,
                timestamp: Long,
                floodScope: ResolvedFloodScope,
            ) {
                forwarded = floodScope
                throw MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, "device error code=5 (fileIOError)")
            }
        }
        val chat = ChatSessionAdapter(native) { null }
        try {
            chat.sendChannelMessage(0, "hi", 1L, ResolvedFloodScope.Unscoped)
            fail("expected a chat device error")
        } catch (error: ChatDeviceException) {
            assertEquals(FIRMWARE_FILE_IO, error.firmwareCode)
        }
        assertEquals(ResolvedFloodScope.Unscoped, forwarded)
    }

    @Test
    fun sessionAdaptersMapBridgeErrorsOntoTypedFailures() = runBlocking {
        val deviceError = MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, "device error code=5 (fileIOError)")
        val chat = ChatSessionAdapter(RecordingMeshSession().apply { sendError = deviceError }) { null }
        try {
            chat.sendDirectMessage(ByteArray(PUBLIC_KEY_SIZE), "hi", 1L, 0)
            fail("expected a chat device error")
        } catch (e: ChatDeviceException) {
            assertEquals(FIRMWARE_FILE_IO, e.firmwareCode)
        }

        val contact = ContactSessionAdapter(RecordingMeshSession().apply { contactError = deviceError })
        try {
            contact.removeContact(ByteArray(PUBLIC_KEY_SIZE))
            fail("expected a contact device error")
        } catch (e: ContactDeviceException) {
            assertEquals(FIRMWARE_FILE_IO, e.firmwareCode)
        }

        val channel = ChannelSessionAdapter(
            RecordingMeshSession().apply {
                channelError = MeshBridgeException(BridgeErrorCode.TIMEOUT, "slow")
            },
        )
        try {
            channel.getChannel(0)
            fail("expected a channel timeout")
        } catch (_: ChannelTimeoutException) {
        }

        val disconnected = MeshBridgeException(BridgeErrorCode.NOT_CONNECTED, "down")
        val passthrough = ChatSessionAdapter(RecordingMeshSession().apply { sendError = disconnected }) { null }
        try {
            passthrough.sendDirectMessage(ByteArray(PUBLIC_KEY_SIZE), "hi", 1L, 0)
            fail("expected the bridge error")
        } catch (e: MeshBridgeException) {
            assertEquals(BridgeErrorCode.NOT_CONNECTED, e.code)
        }
    }
}

private const val PUBLIC_KEY_SIZE = 32
private const val FIRMWARE_FILE_IO = 5

/**
 * A [MeshSession] double recording the node/room command surface. `events` replays its last frame so a
 * collector that subscribes after the emit (the adapter sends, then waits) still observes the ACK. Ops the
 * adapters under test never call throw, so an accidental new dependency is caught rather than silently
 * defaulted.
 */
private class RecordingMeshSession : MeshSession {
    private val _events = MutableSharedFlow<MeshEvent>(replay = 1, extraBufferCapacity = 8)
    override val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    var loginError: Throwable? = null
    var sendError: Throwable? = null
    var contactError: Throwable? = null
    var channelError: Throwable? = null
    var lastCommand: String? = null
    var lastCommandTimestamp: Long? = null
    var autoAck = false
    var ackTimeoutMs: Long = 5_000
    var sendMessageCount = 0
    var resetPathCount = 0
    var resetPathError: Throwable? = null
    var remainingStatusTimeouts = 0
    val statusRequests = mutableListOf<Pair<ByteArray, ContactType>>()

    private val ackCode = ByteArray(4) { 0x11 }

    override suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo {
        loginError?.let { throw it }
        return sentInfo()
    }

    override suspend fun sendCommand(destination: ByteArray, command: String, timestamp: Long): MessageSentInfo {
        lastCommand = command
        lastCommandTimestamp = timestamp
        return sentInfo()
    }

    override suspend fun sendMessage(
        destination: ByteArray,
        text: String,
        timestamp: Long,
        attempt: Int,
    ): MessageSentInfo {
        sendError?.let { throw it }
        sendMessageCount += 1
        if (autoAck) _events.tryEmit(MeshEvent.Acknowledgement(ackCode))
        return sentInfo()
    }

    override suspend fun resetPath(publicKey: ByteArray) {
        resetPathCount += 1
        resetPathError?.let { throw it }
    }

    /**
     * Stands in for the bridge: the frame it hands back is already decoded with the layout the requested
     * [ContactType] selects, so an adapter asking as the wrong type gets the wrong counters.
     */
    override suspend fun requestStatus(publicKey: ByteArray, type: ContactType): StatusResponse {
        statusRequests += publicKey to type
        if (remainingStatusTimeouts > 0) {
            remainingStatusTimeouts -= 1
            throw MeshBridgeException(BridgeErrorCode.TIMEOUT, "stale path")
        }
        return if (type == ContactType.ROOM) roomStatusFrame() else repeaterStatusFrame()
    }

    private fun sentInfo() = MessageSentInfo(route = 0, expectedAck = ackCode, suggestedTimeoutMs = ackTimeoutMs)

    // Unused by the adapters under test.
    override suspend fun start(): SelfInfo = notCalled()
    override suspend fun queryDevice(): DeviceCapabilities = notCalled()
    override suspend fun getTime(): Long = notCalled()
    override suspend fun getContacts(since: Long?): List<MeshContact> = notCalled()
    override suspend fun sendChannelMessage(
        channel: Int,
        text: String,
        timestamp: Long,
        floodScope: ResolvedFloodScope,
    ) = notCalled()

    override suspend fun sendChannelMessage(channel: Int, text: String, timestamp: Long) = notCalled()
    override suspend fun getChannel(index: Int): ChannelInfo {
        channelError?.let { throw it }
        notCalled()
    }
    override suspend fun getChannels(indices: ByteArray): CommandResult.ChannelsResult = notCalled()
    override suspend fun setChannel(index: Int, name: String, secret: ByteArray) = notCalled()
    override suspend fun getMessage(): GetMessageResult = notCalled()
    override suspend fun getContact(publicKey: ByteArray): MeshContact? = notCalled()
    override suspend fun addContact(contact: MeshContact) = notCalled()
    override suspend fun removeContact(publicKey: ByteArray) {
        contactError?.let { throw it }
        notCalled()
    }
    override suspend fun changeContactFlags(contact: MeshContact, flags: Int) = notCalled()
    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo = notCalled()

    override suspend fun sendTrace(tag: Long?, authCode: Long?, flags: Int, path: ByteArray?): MessageSentInfo =
        notCalled()
    override suspend fun shareContact(publicKey: ByteArray) = notCalled()
    override suspend fun sendLogout(destination: ByteArray) = notCalled()
    override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): MessageSentInfo = notCalled()
    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse = notCalled()
    override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse = notCalled()
    override suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: Int,
        offset: Int,
        orderBy: Int,
        pubkeyPrefixLength: Int,
    ): NeighboursResponse = notCalled()
    override suspend fun getSelfInfo(): SelfInfo = notCalled()
    override suspend fun exportPrivateKey(): ByteArray = notCalled()
    override suspend fun importPrivateKey(privateKey: ByteArray) = notCalled()
    override suspend fun setName(name: String) = notCalled()
    override suspend fun setCoordinates(latitude: Double, longitude: Double) = notCalled()
    override suspend fun setTime(epochSeconds: Long) = notCalled()
    override suspend fun setOtherParams(params: DeviceOtherParams) = notCalled()
    override suspend fun setRadio(
        frequency: Double,
        bandwidth: Double,
        spreadingFactor: Int,
        codingRate: Int,
        clientRepeat: Boolean?,
    ) = notCalled()
    override suspend fun setTxPower(power: Int) = notCalled()
    override suspend fun getAutoAddConfig(): AutoAddConfig = notCalled()

    override suspend fun setAutoAddConfig(bitmask: Int, maxHops: Int) = notCalled()
    override suspend fun stop() = notCalled()
    override fun reportConnected() = notCalled()
    override fun reportConnectFailed(message: String) = notCalled()
    override fun reportDisconnected() = notCalled()
    override fun deliverReceived(bytes: ByteArray) = notCalled()
    override fun close() = notCalled()

    private fun notCalled(): Nothing = throw NotImplementedError("not exercised by the adapter tests")
}

/** The trailing 32-bit field a repeater frame carries as RX airtime, plus the repeater-only error count. */
private fun repeaterStatusFrame(): StatusResponse = statusFrame(layout = "repeater").copy(
    rxAirtime = 4321,
    receiveErrors = 12,
)

/** The same trailing field read as a room server's two packed 16-bit counters. */
private fun roomStatusFrame(): StatusResponse = statusFrame(layout = "roomServer").copy(
    roomServerPostedCount = 17,
    roomServerPostPushCount = 9,
)

private fun statusFrame(layout: String): StatusResponse = StatusResponse(
    layout = layout,
    publicKeyPrefix = ByteArray(6),
    battery = 3850,
    txQueueLength = 1,
    noiseFloor = -120,
    lastRSSI = -87,
    packetsReceived = 1000,
    packetsSent = 500,
    airtime = 500,
    uptime = 3600,
    sentFlood = 200,
    sentDirect = 100,
    receivedFlood = 400,
    receivedDirect = 300,
    fullEvents = 7,
    lastSNR = 8.5,
    directDuplicates = 11,
    floodDuplicates = 22,
    rxAirtime = 0,
    receiveErrors = 0,
)

private class RoomPathResetProbeError : Error("probe")
