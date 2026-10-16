package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.channel.ChannelBatchRead
import one.meshcore.shared.channel.ChannelService
import one.meshcore.shared.channel.ChannelSession
import one.meshcore.shared.channel.InMemoryChannelStore
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.InMemoryContactStore
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
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.TelemetryModes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [NodeConfigSessionAdapter]: radio unit conversion, auto-add invert, contact local-save
 * swallow, and name truncation. Pure-JVM over a recording [MeshSession] double.
 */
class NodeConfigSessionAdapterTest {
    private val radioID = Uuid.normalized("B0000000-0000-0000-0000-000000000001")

    @Test
    fun setRadioParamsDividesKhzAndHzByThousand() = runBlocking {
        val session = RecordingConfigSession()
        val adapter = adapter(session)

        // 869_618 kHz → 869.618 MHz; 62_500 Hz → 62.5 kHz on the wire.
        adapter.setRadioParams(
            frequencyKHz = 869_618u,
            bandwidthKHz = 62_500u,
            spreadingFactor = 7u,
            codingRate = 5u,
        )

        assertEquals(869.618, session.lastRadioFrequency)
        assertEquals(62.5, session.lastRadioBandwidth)
        assertEquals(7, session.lastSpreadingFactor)
        assertEquals(5, session.lastCodingRate)
        assertEquals(null, session.lastClientRepeat)
    }

    @Test
    fun setOtherParamsInvertsAutoAddToManualAdd() = runBlocking {
        val session = RecordingConfigSession()
        val adapter = adapter(session)

        adapter.setOtherParams(
            autoAddContacts = true,
            telemetryModes = TelemetryModes(base = 1, location = 2, environment = 3),
            advertLocationPolicyRaw = 4,
            multiAcks = 1,
        )

        assertEquals(false, session.lastManualAddContacts)
        assertEquals(3, session.lastTelEnv)
        assertEquals(2, session.lastTelLoc)
        assertEquals(1, session.lastTelBase)
        assertEquals(4, session.lastAdvertPolicy)
        assertEquals(1, session.lastMultiAcks)
    }

    @Test
    fun setContactAddsDeviceAndPersistsLocally() = runBlocking {
        val session = RecordingConfigSession()
        val store = InMemoryContactStore()
        val adapter = adapter(session, store)
        val publicKey = ByteArray(32) { 0x42 }
        val contact = sampleContact(publicKey, "Repeater")

        adapter.setContact(radioID, contact)

        assertEquals(1, session.addedContacts.size)
        assertContentEquals(publicKey, session.addedContacts.single().publicKey)
        val saved = store.fetchContact(radioID, publicKey)
        assertEquals("Repeater", saved?.name)
    }

    @Test
    fun setNodeNameTruncatesToUsableByteLimit() = runBlocking {
        val session = RecordingConfigSession()
        val adapter = adapter(session)
        val longName = "x".repeat(40)

        adapter.setNodeName(longName)

        assertEquals(31, session.lastName!!.encodeToByteArray().size)
    }

    @Test
    fun setContactStillSucceedsWhenLocalSaveThrows() = runBlocking {
        val session = RecordingConfigSession()
        val store = ThrowingContactStore()
        val adapter = adapter(session, store)

        // Must not throw: device add landed, local failure is swallowed.
        adapter.setContact(radioID, sampleContact(ByteArray(32) { 1 }, "Node"))
        assertEquals(1, session.addedContacts.size)
        assertTrue(store.saveCalled)
    }

    @Test
    fun cancelledLocalSaveDoesNotReportTheContactWriteAsComplete() = runBlocking {
        val cancelled = CancellationException("save cancelled")
        val session = RecordingConfigSession()
        val store = ThrowingContactStore(cancelled)
        val adapter = adapter(session, store)

        val thrown = assertFailsWith<CancellationException> {
            adapter.setContact(radioID, sampleContact(ByteArray(32) { 1 }, "Node"))
        }

        assertSame(cancelled, thrown)
        assertEquals(1, session.addedContacts.size)
        assertTrue(store.saveCalled)
    }

    private fun adapter(session: MeshSession, store: ContactStore = InMemoryContactStore()) = NodeConfigSessionAdapter(
        session = session,
        channelService = ChannelService(NoOpChannelSession(), InMemoryChannelStore()),
        contactStore = store,
    )
}

/** Records config-related MeshSession calls; unused ops throw. */
@Suppress("TooManyFunctions")
private class RecordingConfigSession : MeshSession {
    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    var lastRadioFrequency: Double? = null
    var lastRadioBandwidth: Double? = null
    var lastSpreadingFactor: Int? = null
    var lastCodingRate: Int? = null
    var lastClientRepeat: Boolean? = null
    var lastManualAddContacts: Boolean? = null
    var lastTelEnv: Int? = null
    var lastTelLoc: Int? = null
    var lastTelBase: Int? = null
    var lastAdvertPolicy: Int? = null
    var lastMultiAcks: Int? = null
    var lastName: String? = null
    val addedContacts = mutableListOf<MeshContact>()

    override suspend fun setRadio(
        frequency: Double,
        bandwidth: Double,
        spreadingFactor: Int,
        codingRate: Int,
        clientRepeat: Boolean?,
    ) {
        lastRadioFrequency = frequency
        lastRadioBandwidth = bandwidth
        lastSpreadingFactor = spreadingFactor
        lastCodingRate = codingRate
        lastClientRepeat = clientRepeat
    }

    override suspend fun setOtherParams(params: DeviceOtherParams) {
        lastManualAddContacts = params.manualAddContacts
        lastTelEnv = params.telemetryModeEnvironment
        lastTelLoc = params.telemetryModeLocation
        lastTelBase = params.telemetryModeBase
        lastAdvertPolicy = params.advertisementLocationPolicy
        lastMultiAcks = params.multiAcks
    }

    override suspend fun setName(name: String) {
        lastName = name
    }

    override suspend fun addContact(contact: MeshContact) {
        addedContacts += contact
    }

    override suspend fun start(): SelfInfo = notCalled()
    override suspend fun queryDevice(): DeviceCapabilities = notCalled()
    override suspend fun getTime(): Long = notCalled()
    override suspend fun getContacts(since: Long?): List<MeshContact> = notCalled()
    override suspend fun sendMessage(
        destination: ByteArray,
        text: String,
        timestamp: Long,
        attempt: Int,
    ): MessageSentInfo = notCalled()
    override suspend fun sendChannelMessage(
        channel: Int,
        text: String,
        timestamp: Long,
        floodScope: ResolvedFloodScope,
    ) = notCalled()

    override suspend fun sendChannelMessage(channel: Int, text: String, timestamp: Long) = notCalled()
    override suspend fun getChannel(index: Int): ChannelInfo = notCalled()
    override suspend fun getChannels(indices: ByteArray): CommandResult.ChannelsResult = notCalled()
    override suspend fun setChannel(index: Int, name: String, secret: ByteArray) = notCalled()
    override suspend fun getMessage(): GetMessageResult = notCalled()
    override suspend fun getContact(publicKey: ByteArray): MeshContact? = notCalled()
    override suspend fun resetPath(publicKey: ByteArray) = notCalled()
    override suspend fun removeContact(publicKey: ByteArray) = notCalled()
    override suspend fun changeContactFlags(contact: MeshContact, flags: Int) = notCalled()
    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo = notCalled()

    override suspend fun sendTrace(tag: Long?, authCode: Long?, flags: Int, path: ByteArray?): MessageSentInfo =
        notCalled()
    override suspend fun shareContact(publicKey: ByteArray) = notCalled()
    override suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo = notCalled()
    override suspend fun sendLogout(destination: ByteArray) = notCalled()
    override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): MessageSentInfo = notCalled()
    override suspend fun requestStatus(publicKey: ByteArray, type: ContactType): StatusResponse = notCalled()
    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse = notCalled()
    override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse = notCalled()
    override suspend fun sendCommand(destination: ByteArray, command: String, timestamp: Long): MessageSentInfo =
        notCalled()
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
    override suspend fun setCoordinates(latitude: Double, longitude: Double) = notCalled()
    override suspend fun setTime(epochSeconds: Long) = notCalled()
    override suspend fun setTxPower(power: Int) = notCalled()
    override suspend fun getAutoAddConfig(): AutoAddConfig = notCalled()

    override suspend fun setAutoAddConfig(bitmask: Int, maxHops: Int) = notCalled()
    override suspend fun stop() = notCalled()
    override fun reportConnected() = notCalled()
    override fun reportConnectFailed(message: String) = notCalled()
    override fun reportDisconnected() = notCalled()
    override fun deliverReceived(bytes: ByteArray) = notCalled()
    override fun close() = notCalled()

    private fun notCalled(): Nothing = throw NotImplementedError("not exercised by adapter tests")
}

/** Channel session the adapter never drives for these tests. */
private class NoOpChannelSession : ChannelSession {
    override suspend fun getChannel(index: Int): ChannelInfo =
        ChannelInfo(index = index, name = "", secret = ByteArray(16))

    override suspend fun getChannels(indices: List<Int>): ChannelBatchRead =
        ChannelBatchRead(received = emptyList(), missing = emptyList())

    override suspend fun setChannel(index: Int, name: String, secret: ByteArray) = Unit
}

/** Contact store that records save attempts and always throws on write. */
@Suppress("TooManyFunctions")
private class ThrowingContactStore(private val failure: Throwable = IllegalStateException("local save failed")) :
    ContactStore {
    var saveCalled = false

    override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> = emptyList()
    override suspend fun fetchBlockedContacts(radioID: Uuid): List<ContactDto> = emptyList()
    override suspend fun fetchConversations(radioID: Uuid): List<ContactDto> = emptyList()
    override suspend fun fetchContact(id: Uuid): ContactDto? = null
    override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? = null
    override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid {
        saveCalled = true
        throw failure
    }

    override suspend fun batchSaveContacts(radioID: Uuid, frames: List<ContactFrame>): Int = 0
    override suspend fun saveContact(dto: ContactDto) {
        saveCalled = true
        throw failure
    }

    override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? {
        saveCalled = true
        throw failure
    }

    override suspend fun deleteContact(id: Uuid) = Unit
    override suspend fun deleteContactIfUnreferenced(id: Uuid) = Unit
    override suspend fun deleteMessagesForContact(contactID: Uuid) = Unit
    override suspend fun clearUnreadCount(contactID: Uuid) = Unit
    override suspend fun clearUnreadMentionCount(contactID: Uuid) = Unit
    override suspend fun touchContactHeard(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean = false
}
