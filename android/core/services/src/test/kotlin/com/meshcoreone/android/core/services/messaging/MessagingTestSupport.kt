// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/MessageService+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: WP-208 deterministic real-session/raw-transport harness; unsupported fake roles throw.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.ByteReader
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.*
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransportException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.DynamicTest

internal val RADIO = RadioId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
internal val PEER_RADIO = RadioId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
internal val SELF = Bytes(ByteArray(32) { 0x01 })
internal val TARGET = Bytes(ByteArray(32) { 0x22 })
internal val CONTACT = UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")
internal val BASE_TIME = Instant.parse("2026-10-05T12:00:00Z")
internal fun token(radio: RadioId = RADIO, generation: Long = 1) =
    SessionToken(ProcessEpoch(UUID.fromString("33333333-3333-3333-3333-333333333333")), Generation(generation), radio)

internal class TestMessagingClock(val scheduler: TestCoroutineScheduler) : SessionClock {
    var offset: Long = 0
    override val now: Duration get() = scheduler.currentTime.milliseconds
    override val wallClock: Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = BASE_TIME.plusMillis(scheduler.currentTime + offset)
    }
    override suspend fun sleepFor(duration: Duration) { delay(duration) }
}

internal class TestSignals(initial: SessionToken = token()) : ConnectionSignals {
    private val state = MutableStateFlow(TestSignals.snapshot(initial, DeviceConnectionState.READY))
    override val snapshot: StateFlow<ConnectionSnapshot> = state.asStateFlow()
    private val lock = Any()
    private val subscribers = mutableSetOf<kotlinx.coroutines.channels.Channel<ConnectionSnapshot>>()
    val subscriberCount: Int get() = synchronized(lock) { subscribers.size }
    fun set(token: SessionToken?, rung: DeviceConnectionState) {
        val value = TestSignals.snapshot(token, rung)
        synchronized(lock) {
            state.value = value
            subscribers.forEach { it.trySend(value).getOrThrow() }
        }
    }
    override suspend fun subscribeTransitions(): ConnectionSubscription = synchronized(lock) {
        val queue = kotlinx.coroutines.channels.Channel<ConnectionSnapshot>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        subscribers += queue
        object : ConnectionSubscription {
            override val initial = state.value
            override val transitions: Flow<ConnectionSnapshot> = flow {
                try { for (next in queue) emit(next) } finally { close() }
            }
            override fun close() { synchronized(lock) { subscribers.remove(queue); queue.cancel() } }
        }
    }
    companion object {
        fun snapshot(token: SessionToken?, rung: DeviceConnectionState) = ConnectionSnapshot(
            rung, if (rung == DeviceConnectionState.DISCONNECTED) ConnectionState.Disconnected else ConnectionState.Connected,
            null, ConnectionIntent.WantsConnection(), token, null,
        )
    }
}

private fun unsupportedStore(): PersistenceStoreProtocol = PersistenceStoreProtocol::class.java.cast(
    Proxy.newProxyInstance(PersistenceStoreProtocol::class.java.classLoader, arrayOf(PersistenceStoreProtocol::class.java)) { _, method, _ ->
        throw UnsupportedOperationException("Undeclared test operation: ${method.name}")
    },
)

internal class RecordingStore : PersistenceStoreProtocol by unsupportedStore() {
    val messages = linkedMapOf<EntityKey, MessageDTO>()
    val contacts = linkedMapOf<EntityKey, ContactDTO>()
    val channels = linkedMapOf<EntityKey, ChannelDTO>()
    val devices = linkedMapOf<RadioId, DeviceDTO>()
    val pending = linkedMapOf<EntityKey, PendingSendDTO>()
    val calls = mutableListOf<String>()
    val faults = mutableMapOf<String, PersistenceStoreException>()
    var before: (suspend (String) -> Unit)? = null
    private suspend fun access(name: String) {
        calls += name
        before?.invoke(name)
        faults[name]?.let { throw it }
    }
    override suspend fun saveMessage(dto: MessageDTO) { access("saveMessage"); messages[EntityKey(dto.radioId, dto.id)] = dto }
    override suspend fun fetchMessage(key: EntityKey): MessageDTO? { access("fetchMessage"); return messages[key] }
    override suspend fun fetchMessage(deduplicationKey: String, radioId: RadioId): MessageDTO? =
        messages.values.firstOrNull { it.radioId == radioId && it.deduplicationKey == deduplicationKey }
    override suspend fun isDuplicateMessage(deduplicationKey: String, radioId: RadioId): Boolean =
        fetchMessage(deduplicationKey, radioId) != null
    override suspend fun fetchMessages(contact: EntityKey, limit: Long, offset: Long): SnapshotList<MessageDTO> =
        messages.values.filter { it.radioId == contact.radioId && it.contactID == contact.id }.snapshot()
    override suspend fun fetchMessages(radioId: RadioId, channelIndex: UByte, limit: Long, offset: Long): SnapshotList<MessageDTO> =
        messages.values.filter { it.radioId == radioId && it.channelIndex == channelIndex }.snapshot()
    override suspend fun updateMessageStatus(key: EntityKey, status: MessageStatus) {
        access("updateMessageStatus"); messages[key]?.let { messages[key] = it.copy(status = status) }
    }
    override suspend fun updateMessageStatusUnlessDelivered(key: EntityKey, status: MessageStatus): Boolean {
        access("updateMessageStatusUnlessDelivered")
        val row = messages[key] ?: return false
        if (row.status == MessageStatus.DELIVERED) return false
        messages[key] = row.copy(status = status)
        return true
    }
    override suspend fun clearRetryingToSent(key: EntityKey): Boolean {
        access("clearRetryingToSent")
        val row = messages[key] ?: return false
        if (row.status == MessageStatus.FAILED || row.status == MessageStatus.DELIVERED) return false
        messages[key] = row.copy(status = MessageStatus.SENT, retryAttempt = 0, maxRetryAttempts = 0)
        return true
    }
    override suspend fun updateMessageAck(key: EntityKey, ackCode: UInt, status: MessageStatus, roundTripTime: UInt?) {
        access("updateMessageAck")
        val row = messages[key] ?: return
        if (row.status == MessageStatus.DELIVERED && status != MessageStatus.DELIVERED ||
            row.status == MessageStatus.FAILED && status == MessageStatus.DELIVERED) return
        messages[key] = row.copy(ackCode = ackCode, status = status, roundTripTime = roundTripTime ?: row.roundTripTime)
    }
    override suspend fun updateMessageRetryStatus(key: EntityKey, status: MessageStatus, retryAttempt: Long, maxRetryAttempts: Long) {
        access("updateMessageRetryStatus")
        messages[key]?.takeIf { it.status != MessageStatus.DELIVERED }?.let {
            messages[key] = it.copy(status = status, retryAttempt = retryAttempt, maxRetryAttempts = maxRetryAttempts)
        }
    }
    override suspend fun updateMessageTimestamp(key: EntityKey, timestamp: UInt) {
        access("updateMessageTimestamp"); messages[key]?.let { messages[key] = it.copy(timestamp = timestamp) }
    }
    override suspend fun incrementMessageSendCount(key: EntityKey): Long {
        access("incrementMessageSendCount")
        val row = messages[key] ?: return 0
        val count = Math.incrementExact(row.sendCount)
        messages[key] = row.copy(sendCount = count)
        return count
    }
    override suspend fun updateMessageHeardRepeats(key: EntityKey, heardRepeats: Long) {
        access("updateMessageHeardRepeats"); messages[key]?.let { messages[key] = it.copy(heardRepeats = heardRepeats) }
    }
    override suspend fun deleteMessageRepeats(message: EntityKey) { access("deleteMessageRepeats") }
    override suspend fun deleteMessage(key: EntityKey) { messages.remove(key); deletePendingSendsForMessage(key) }
    override suspend fun hasOutgoingSentDM(radioId: RadioId, ackCode: UInt): Boolean =
        messages.values.any { it.radioId == radioId && it.ackCode == ackCode && it.status == MessageStatus.SENT && it.channelIndex == null }
    override suspend fun fetchContact(key: EntityKey): ContactDTO? { access("fetchContact"); return contacts[key] }
    override suspend fun fetchContactByPrefix(radioId: RadioId, publicKeyPrefix: Bytes): ContactDTO? {
        access("fetchContactByPrefix")
        return contacts.values.filter { it.radioId == radioId && it.publicKey.prefix(publicKeyPrefix.size) == publicKeyPrefix }.singleOrNull()
    }
    override suspend fun saveContact(dto: ContactDTO) { contacts[EntityKey(dto.radioId, dto.id)] = dto }
    override suspend fun saveContact(radioId: RadioId, frame: ContactFrame): ContactSaveResult {
        val existing = contacts.values.firstOrNull { it.radioId == radioId && it.publicKey == frame.publicKey }
        val dto = existing?.updating(frame) ?: ContactDTO.fromFrame(radioId, frame)
        saveContact(dto)
        return ContactSaveResult(dto.id, existing == null)
    }
    override suspend fun updateContactLastMessage(key: EntityKey, date: Instant?) {
        access("updateContactLastMessage"); contacts[key]?.let { contacts[key] = it.copy(lastMessageDate = date) }
    }
    override suspend fun fetchConversations(radioId: RadioId): SnapshotList<ContactDTO> =
        contacts.values.filter { it.radioId == radioId && it.lastMessageDate != null }.snapshot()
    override suspend fun fetchDevice(radioId: RadioId): DeviceDTO? { access("fetchDevice"); return devices[radioId] }
    override suspend fun fetchDevice(id: UUID): DeviceDTO? = devices.values.firstOrNull { it.id == id }
    override suspend fun saveDevice(dto: DeviceDTO) { devices[dto.radioId] = dto }
    override suspend fun fetchChannel(radioId: RadioId, index: UByte): ChannelDTO? {
        access("fetchChannel"); return channels.values.firstOrNull { it.radioId == radioId && it.index == index }
    }
    override suspend fun updateChannelLastMessage(key: EntityKey, date: Instant?) {
        access("updateChannelLastMessage"); channels[key]?.let { channels[key] = it.copy(lastMessageDate = date) }
    }
    override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDTO): Long {
        access("insertPendingSendAssigningSequence")
        val sequence = Math.incrementExact(pending.values.filter { it.radioId == dto.radioId }.maxOfOrNull { it.sequence } ?: 0)
        pending[EntityKey(dto.radioId, dto.id)] = dto.copy(sequence = sequence)
        return sequence
    }
    override suspend fun upsertPendingSend(dto: PendingSendDTO) { pending[EntityKey(dto.radioId, dto.id)] = dto }
    override suspend fun fetchPendingSends(radioId: RadioId): SnapshotList<PendingSendDTO> {
        access("fetchPendingSends"); return pending.values.filter { it.radioId == radioId }.sortedBy { it.sequence }.snapshot()
    }
    override suspend fun fetchPendingSendsForMessage(key: EntityKey): SnapshotList<PendingSendDTO> {
        access("fetchPendingSendsForMessage")
        return pending.values.filter { it.radioId == key.radioId && it.messageID == key.id }.sortedBy { it.sequence }.snapshot()
    }
    override suspend fun hasPendingSend(key: EntityKey): Boolean {
        access("hasPendingSend"); return pending.values.any { it.radioId == key.radioId && it.messageID == key.id }
    }
    override suspend fun incrementPendingSendAttemptCount(key: EntityKey): Long? {
        access("incrementPendingSendAttemptCount")
        val row = pending.values.firstOrNull { it.radioId == key.radioId && it.messageID == key.id } ?: return null
        val next = Math.incrementExact(row.attemptCount ?: 0)
        pending[EntityKey(row.radioId, row.id)] = row.copy(attemptCount = next)
        return next
    }
    override suspend fun deletePendingSendsForMessage(key: EntityKey) {
        access("deletePendingSendsForMessage")
        pending.entries.removeIf { it.value.radioId == key.radioId && it.value.messageID == key.id }
    }
}

internal class FirmwareTransport(private val senderPublicKey: Bytes = SELF) : MeshTransport {
    val mock = MockTransport()
    var appStartReply: Bytes = selfInfoPacket(senderPublicKey)
    var beforeReply: (suspend (Bytes) -> Unit)? = null
    var holdMessageReplies = false
    var holdGetReplies = false
    var acknowledge = false
    var ackBeforeAcceptance = false
    var acceptanceGate: CompletableDeferred<Unit>? = null
    var expectedAckOverride: Bytes? = null
    var suggestedTimeout: UInt = 10u
    var directError: UByte? = null
    var channelError: UByte? = null
    val channelReplies = mutableMapOf<UByte, ChannelInfo>()
    val incomingMessages = ArrayDeque<Bytes>()
    var afterNoMore: (suspend () -> Unit)? = null
    val sentData: List<Bytes> get() = mock.sentData
    var disconnectCalls = 0
        private set
    override suspend fun isConnected() = mock.isConnected()
    override suspend fun receivedData() = mock.receivedData()
    override suspend fun supportsWriteWithoutResponse() = false
    override suspend fun supportsPipelinedReads() = false
    override suspend fun connect() = mock.connect()
    override suspend fun disconnect() { disconnectCalls++; mock.disconnect() }
    override suspend fun send(data: Bytes) {
        try { mock.send(data) }
        catch (failure: MockTransportException.SendFailed) { throw MeshTransportError.SendFailed(failure.reason) }
        catch (failure: MockTransportException.NotConnected) { throw MeshTransportError.NotConnected }
        beforeReply?.invoke(data)
        when (CommandCode.fromRawValue(data[0])) {
            CommandCode.APP_START -> mock.simulateReceive(appStartReply)
            CommandCode.SEND_MESSAGE -> {
                directError?.let { mock.simulateError(it); return }
                if (holdMessageReplies) return
                val reader = ByteReader(data, 3)
                val stamp = reader.readUInt32LE()
                reader.readBytes(6)
                val text = reader.readBytes(reader.remaining).toByteArray().toString(Charsets.UTF_8)
                val input = ByteWriter().appendUInt32LE(stamp).appendUInt8((data[2].toInt() and 3).toUByte())
                    .append(Bytes.utf8(text)).append(senderPublicKey).toBytes().toByteArray()
                val predicted = Bytes(MessageDigest.getInstance("SHA-256").digest(input)).prefix(4)
                val ack = expectedAckOverride ?: predicted
                if (acknowledge && ackBeforeAcceptance) pushAck(ack, 123u)
                acceptanceGate?.await()
                mock.simulateReceive(ByteWriter().appendUInt8(ResponseCode.MESSAGE_SENT.rawValue).appendUInt8(0u)
                    .append(ack).appendUInt32LE(suggestedTimeout).toBytes())
                if (acknowledge && !ackBeforeAcceptance) pushAck(ack, 123u)
            }
            CommandCode.SEND_CHANNEL_MESSAGE -> {
                channelError?.let { mock.simulateError(it); return }
                mock.simulateOK()
            }
            CommandCode.RESET_PATH -> mock.simulateOK()
            CommandCode.GET_CONTACT_BY_KEY -> mock.simulateError(2u)
            CommandCode.GET_CHANNEL -> {
                val channel = channelReplies[data[1]]
                if (channel == null) mock.simulateError(2u)
                else mock.simulateReceive(ByteWriter().appendUInt8(ResponseCode.CHANNEL_INFO.rawValue).appendUInt8(channel.index)
                    .append(Bytes.utf8(channel.name).paddedOrTruncated(32)).append(channel.secret).toBytes())
            }
            CommandCode.GET_MESSAGE -> if (!holdGetReplies) {
                val empty = incomingMessages.isEmpty()
                mock.simulateReceive(if (empty) Bytes.of(ResponseCode.NO_MORE_MESSAGES.rawValue.toInt()) else incomingMessages.removeFirst())
                if (empty) afterNoMore?.also { afterNoMore = null }?.invoke()
            }
            else -> throw AssertionError("Unexpected deterministic firmware command ${data[0]}")
        }
    }
    suspend fun pushAck(code: Bytes, trip: UInt? = null) {
        val builder = ByteWriter().appendUInt8(ResponseCode.ACK.rawValue).append(code)
        if (trip != null) builder.appendUInt32LE(trip)
        mock.simulateReceive(builder.toBytes())
    }
    companion object {
        fun selfInfoPacket(publicKey: Bytes = SELF): Bytes = ByteWriter().appendUInt8(ResponseCode.SELF_INFO.rawValue)
            .appendUInt8(1u).appendUInt8(22u).appendUInt8(22u).append(publicKey)
            .appendInt32LE(0).appendInt32LE(0).appendUInt8(0u).appendUInt8(0u).appendUInt8(0u)
            .appendUInt32LE(915_000u).appendUInt32LE(125_000u).appendUInt8(7u).appendUInt8(5u)
            .append(Bytes.utf8("Test")).toBytes()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class Harness(
    val test: TestScope,
    config: MessageServiceConfig = MessageServiceConfig(),
    val store: RecordingStore = RecordingStore(),
    generation: Long = 1,
    sessionTimeout: Double = 2.0,
    private val eventDispatcher: EventDispatcher? = null,
    val contact: ContactDTO = ContactDTO(CONTACT, RADIO, TARGET, "Peer", lastHeardTimestamp = 0u),
    val localPublicKey: Bytes = SELF,
    startupPacket: Bytes? = null,
    radio: RadioId = RADIO,
) {
    val token = token(radio, generation)
    private val fixtureJob = SupervisorJob(test.coroutineContext[Job])
    val scope = CoroutineScope(test.coroutineContext + fixtureJob)
    val signals = TestSignals(token)
    val clock = TestMessagingClock(test.testScheduler)
    val transport = FirmwareTransport(localPublicKey).also { if (startupPacket != null) it.appStartReply = startupPacket }
    val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = sessionTimeout), clock, scope.coroutineContext)
    private val serviceSession = eventDispatcher?.let { dispatcher ->
        object : MeshCoreSessionProtocol by session {
            override fun events(): Flow<MeshEvent> = dispatcher.subscribe()
            override fun events(filter: EventFilter): Flow<MeshEvent> = dispatcher.subscribe(filter)
        }
    } ?: session
    val diagnostics = mutableListOf<MessagingDiagnostic>()
    private val queues = mutableListOf<ChatSendQueueService>()
    private val pollers = mutableListOf<MessagePollingService>()
    val service = MessageService(token, localPublicKey, serviceSession, store, signals, scope, config, clock,
        jitter = { 1.0 }, reporter = MessagingIssueReporter { diagnostics += it })
    init { synchronized(fixtures) { fixtures.getOrPut(test) { mutableListOf() } += this } }
    suspend fun start() {
        store.saveContact(contact)
        session.start()
        assertEquals(localPublicKey, session.currentSelfInfo?.publicKey)
    }
    fun stored(id: UUID): MessageDTO = assertNotNull(store.messages[EntityKey(RADIO, id)])
    suspend fun message(
        status: MessageStatus = MessageStatus.SENT, channel: UByte? = null,
        text: String = "hello", timestamp: UInt = 100u,
    ): MessageDTO {
        val dto = MessageDTO(radioId = RADIO, contactID = if (channel == null) CONTACT else null,
            channelIndex = channel, text = text, timestamp = timestamp, createdAt = clock.wallClock.instant(), status = status)
        store.saveMessage(dto)
        return dto
    }
    fun tracking(
        message: MessageDTO, code: Bytes = Bytes.of(0xAB, 0xCD, 0xEF, 0x12), age: Long = 0,
        timeout: Double = 30.0, delivered: Boolean = false,
    ) = PendingAck(message.id, CONTACT, SnapshotSet(listOf(code)), clock.wallClock.instant().minusSeconds(age), timeout, delivered)
    suspend fun close() {
        var failure: Exception? = null
        suspend fun release(action: suspend () -> Unit) {
            try { action() }
            catch (cause: Exception) {
                val first = failure
                if (first == null) failure = cause else first.addSuppressed(cause)
            }
        }
        try {
            pollers.asReversed().forEach { release { it.close() } }
            queues.asReversed().forEach { release { it.shutdown() } }
            release { service.close() }
            release { session.stop() }
        }
        finally {
            fixtureJob.cancelAndJoin()
            eventDispatcher?.finishAllSubscriptions()
        }
        failure?.let { throw it }
    }
    fun sends(code: CommandCode): List<Bytes> = transport.sentData.filter { it[0] == code.rawValue }
    fun queue(
        config: ChatSendQueueConfig = ChatSendQueueConfig(),
        query: MessagingChannelQuery = MessagingChannelQuery { null },
        indexer: OutgoingChannelReactionIndexer = OutgoingChannelReactionIndexer { _, _, _, _, _ -> },
        progress: ChatSendQueueProgress = ChatSendQueueProgress(),
    ) = ChatSendQueueService(token, store, service, query, indexer, signals, scope, config, clock,
        MessagingIssueReporter { diagnostics += it }, progress = progress).also { queues += it }
    fun poller(role: MeshCoreSessionProtocol = session) =
        MessagePollingService(token, role, store, signals, scope, clock, MessagingIssueReporter { diagnostics += it })
            .also { pollers += it }
    suspend fun pending(message: MessageDTO, attempt: Long? = 0, isResend: Boolean = false): PendingSendDTO {
        val channelIndex = message.channelIndex
        val dto = if (channelIndex == null) PendingSendDTO.fromEnvelope(
            DirectMessageEnvelope(message.id, CONTACT, isResend), RADIO, enqueuedAt = clock.wallClock.instant())
        else PendingSendDTO.fromEnvelope(ChannelMessageEnvelope(message.id, channelIndex, isResend,
            message.text, message.timestamp, "Test"), RADIO, enqueuedAt = clock.wallClock.instant())
        val persisted = dto.copy(sequence = 1, attemptCount = attempt)
        store.upsertPendingSend(persisted)
        return persisted
    }
}

internal fun original(
    suite: String, name: String, signature: String = "()", row: String = "",
    assertion: suspend TestScope.() -> Unit,
): DynamicTest = DynamicTest.dynamicTest("$suite::$name$signature$row") { runMessagingCase(assertion) }

internal fun native(name: String, assertion: suspend TestScope.() -> Unit): DynamicTest =
    DynamicTest.dynamicTest("WP-208::$name") { runMessagingCase(assertion) }

private val fixtures = java.util.IdentityHashMap<TestScope, MutableList<Harness>>()

private fun runMessagingCase(assertion: suspend TestScope.() -> Unit) = runTest {
    val test = this
    var originalFailure: Throwable? = null
    try { assertion() }
    catch (failure: Throwable) { originalFailure = failure; throw failure }
    finally {
        val owned = synchronized(fixtures) { fixtures.remove(test).orEmpty().asReversed() }
        withContext(NonCancellable) {
            var cleanupFailure: Exception? = null
            for (fixture in owned) {
                try { fixture.close() }
                catch (failure: Exception) {
                    val prior = originalFailure
                    if (prior != null) prior.addSuppressed(failure)
                    else {
                        val first = cleanupFailure
                        if (first == null) cleanupFailure = failure else first.addSuppressed(failure)
                    }
                }
            }
            cleanupFailure?.let { throw it }
        }
    }
}

internal suspend fun statuses(service: MessageService, subscription: SessionEventSubscription<MessageStatusEvent>): List<MessageStatusEvent> {
    service.finishStatusEvents()
    return subscription.events.toList().map { it.event }
}
internal fun storageFailure() = PersistenceStoreException(PersistenceStoreError.SaveFailed("deterministic"), IllegalStateException("test fault"))

internal fun reviewContactPacket(text: String, type: UByte = 0u): Bytes {
    val packet = ByteWriter().appendUInt8(ResponseCode.CONTACT_MESSAGE_RECEIVED.rawValue).append(TARGET.prefix(6))
        .appendUInt8(0u).appendUInt8(type).appendUInt32LE(42u)
    if (type == 2.toUByte()) packet.append(Bytes.of(0x80, 0xFF, 1, 2))
    return packet.append(Bytes.utf8(text)).toBytes()
}
