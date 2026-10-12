// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceAttemptCountTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MessageServiceSendDMBookkeepingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: WP-208 actual Room/process-store and real-session consumers on simulated SDK31, not HIL.
package com.meshcoreone.android.core.data.messaging

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.contracts.domain.errors.MessagePollingException
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.data.backup.backupEnvelope
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.data.repository.OriginalCase
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.event.ContactMessage
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import com.meshcoreone.android.core.services.messaging.ChatSendQueueService
import com.meshcoreone.android.core.services.messaging.MessageService
import com.meshcoreone.android.core.services.messaging.MessagePollingService
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class MessagingRoomIntegrationTest {
    private val scheduler = TestCoroutineScheduler()
    private val process = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val radio = RadioId(UUID.fromString("AAAAAAAA-1111-2222-3333-444444444444"))
    private val other = RadioId(UUID.fromString("BBBBBBBB-1111-2222-3333-444444444444"))
    private val self = Bytes(ByteArray(32) { 1 })
    private val target = Bytes(ByteArray(32) { 0x22 })
    private val base = Instant.parse("2026-10-05T12:00:00Z")
    private var wallOffsetMillis = 0L
    private val clock = object : SessionClock {
        override val now: Duration get() = scheduler.currentTime.milliseconds
        override val wallClock: Clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = base.plusMillis(scheduler.currentTime + wallOffsetMillis)
        }
        override suspend fun sleepFor(duration: Duration) { delay(duration) }
    }
    private lateinit var db: MeshCoreDatabase
    private lateinit var store: RoomPersistenceStore
    private lateinit var contact: ContactDTO
    private val generations = mutableListOf<RadioGeneration>()
    private val pollers = mutableListOf<MessagePollingService>()
    private fun database(name: String? = null): MeshCoreDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val builder = if (name == null) Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            else Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
        val immediate = Executor { it.run() }
        return builder.allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
    }
    @Before fun open() {
        db = database()
        store = RoomPersistenceStore(db, process, clock.wallClock)
        contact = ContactDTO(radioId = radio, publicKey = target, name = "Peer", lastHeardTimestamp = 0u)
    }
    @After fun close() {
        try {
            runTest(scheduler) {
                var failure: Exception? = null
                suspend fun release(action: suspend () -> Unit) {
                    try { action() }
                    catch (cause: Exception) {
                        val first = failure
                        if (first == null) failure = cause else first.addSuppressed(cause)
                    }
                }
                withContext(NonCancellable) {
                    pollers.asReversed().forEach { release { it.close() } }
                    generations.asReversed().forEach { release { it.stop() } }
                    release { store.close() }
                }
                failure?.let { throw it }
            }
        }
        finally { process.cancel(); db.close() }
    }
    private suspend fun seed() {
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = self, nodeName = "Local"))
        store.saveContact(contact)
    }
    private inner class RadioGeneration(
        number: Long,
        persistence: PersistenceStoreProtocol = store,
        config: com.meshcoreone.android.core.services.messaging.MessageServiceConfig =
            com.meshcoreone.android.core.services.messaging.MessageServiceConfig(),
        sessionTimeout: Double = 2.0,
    ) {
        val scope = CoroutineScope(SupervisorJob(process.coroutineContext[Job]) + StandardTestDispatcher(scheduler))
        val token = SessionToken(ProcessEpoch(UUID.fromString("CCCCCCCC-1111-2222-3333-444444444444")), Generation(number), radio)
        val signals = RoomSignals(token)
        val firmware = RoomFirmware()
        val session = MeshCoreSession(firmware, SessionConfiguration(defaultTimeout = sessionTimeout), clock, scope.coroutineContext)
        val service = MessageService(token, self, session, persistence, signals, scope, config, clock)
        val queue = ChatSendQueueService(token, persistence, service, MessagingChannelQuery { null },
            OutgoingChannelReactionIndexer { _, _, _, _, _ -> }, signals, scope, clock = clock)
        init { generations += this }
        suspend fun start() { session.start() }
        suspend fun stop() { queue.shutdown(); service.close(); session.stop(); scope.cancel() }
        fun poller() = MessagePollingService(token, session, store, signals, scope, clock).also { pollers += it }
    }

    @Test fun freshAndRecoveredAttemptsUseTheActualCommittedCounterAndWireTimestamp() = runTest(scheduler) {
        seed()
        val first = RadioGeneration(1); first.start()
        val fresh = first.service.createPendingMessage("fresh", contact)
        store.updateMessageTimestamp(EntityKey(radio, fresh.id), 100u)
        val row = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(fresh.id, contact.id), radio)
        store.upsertPendingSend(row.copy(sequence = 1))
        first.signals.set(DeviceConnectionState.CONNECTED)
        first.queue.hydrate(); runCurrent()
        assertEquals(0L, store.fetchPendingSends(radio).single().attemptCount)
        first.firmware.error = 3u
        first.signals.set(DeviceConnectionState.READY); runCurrent(); advanceTimeBy(3501); runCurrent()
        assertEquals(1L, store.fetchPendingSends(radio).single().attemptCount)
        val stamp = assertNotNull(store.fetchMessage(EntityKey(radio, fresh.id))).timestamp
        assertNotEquals(100u, stamp)
        first.stop()
        val second = RadioGeneration(2); second.start(); second.firmware.error = 3u
        second.signals.set(DeviceConnectionState.CONNECTED); second.queue.hydrate(); runCurrent()
        second.signals.set(DeviceConnectionState.READY); runCurrent(); advanceTimeBy(3501); runCurrent()
        assertEquals(2L, store.fetchPendingSends(radio).single().attemptCount)
        assertEquals(stamp, second.firmware.sent().first().readUInt32LE(3))
        assertEquals(stamp, assertNotNull(store.fetchMessage(EntityKey(radio, fresh.id))).timestamp)
        second.stop()
    }

    @Test fun warmUpPurgesOnlyLegacyNullAndForgottenRadiosAndPreservesCurrentZeroRows() = runTest(scheduler) {
        seed()
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "pending", timestamp = 100u)
        store.saveMessage(message)
        val current = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.upsertPendingSend(current.copy(sequence = 1))
        store.upsertPendingSend(current.copy(id = UUID.randomUUID(), sequence = 2, attemptCount = null))
        store.upsertPendingSend(current.copy(id = UUID.randomUUID(), radioId = other, sequence = 1))
        store.warmUp()
        assertEquals(listOf(current.copy(sequence = 1)), store.fetchPendingSends(radio))
        assertTrue(store.fetchPendingSends(other).isEmpty())
    }

    @Test fun coldStoreReopenRecoversTheSameRadioMessageAndPendingSendWithoutRewritingSortDate() = runTest(scheduler) {
        // Robolectric isolates each test directory; native Windows SQLite still limits path length.
        val name = "cold.db"
        store.close(); db.close(); db = database(name); store = RoomPersistenceStore(db, process, clock.wallClock)
        seed()
        val sort = base.minusSeconds(86400)
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "cold", timestamp = 100u,
            createdAt = base.minusSeconds(100), sortDate = sort)
        store.saveMessage(message)
        val pending = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio).copy(sequence = 1, attemptCount = 1)
        store.upsertPendingSend(pending)
        store.close(); db.close(); db = database(name); store = RoomPersistenceStore(db, process, clock.wallClock)
        store.warmUp()
        assertEquals(pending, store.fetchPendingSends(radio).single())
        assertEquals(sort, assertNotNull(store.fetchMessage(EntityKey(radio, message.id))).sortDate)
        val g = RadioGeneration(3); g.start(); g.firmware.error = 3u
        g.signals.set(DeviceConnectionState.CONNECTED); g.queue.hydrate(); runCurrent()
        g.signals.set(DeviceConnectionState.READY); runCurrent(); advanceTimeBy(3501); runCurrent()
        assertEquals(100u, g.firmware.sent().first().readUInt32LE(3))
        assertEquals(sort, assertNotNull(store.fetchMessage(EntityKey(radio, message.id))).sortDate); g.stop()
    }

    @Test fun actualHydrationDrainsOneRadioInFIFOOrderAndLeavesTheOtherPartitionUntouched() = runTest(scheduler) {
        seed(); store.saveDevice(DeviceDTO(radioId = other, publicKey = target, nodeName = "Other"))
        val g = RadioGeneration(4); g.start()
        for ((sequence, text) in listOf(3L to "three", 1L to "one", 2L to "two")) {
            val m = MessageDTO(radioId = radio, contactID = contact.id, text = text, timestamp = 100u)
            store.saveMessage(m); store.upsertPendingSend(PendingSendDTO.fromEnvelope(
                DirectMessageEnvelope(m.id, contact.id), radio).copy(sequence = sequence))
        }
        val foreign = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(UUID.randomUUID(), contact.id), other).copy(sequence = 1)
        store.upsertPendingSend(foreign)
        g.signals.set(DeviceConnectionState.READY); g.queue.hydrate(); runCurrent(); g.queue.awaitDrainCompletion()
        assertEquals(listOf("one", "two", "three"), g.firmware.sent().map { it.slice(13, it.size).toByteArray().toString(Charsets.UTF_8) })
        assertTrue(store.fetchPendingSends(radio).isEmpty()); assertEquals(listOf(foreign), store.fetchPendingSends(other))
        g.stop()
    }

    @Test fun actualBackupConsumerBackfillsOnlyNilIncomingKeysAndUsesOutgoingUUIDIdentity() = runTest(scheduler) {
        seed()
        val incoming = MessageDTO(radioId = radio, contactID = contact.id, text = "e\u0301", timestamp = UInt.MAX_VALUE,
            direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED, sortDate = base.minusSeconds(123))
        val emptyKey = incoming.copy(id = UUID.randomUUID(), deduplicationKey = "")
        val outgoing = incoming.copy(id = UUID.randomUUID(), direction = MessageDirection.OUTGOING, deduplicationKey = null)
        for (message in listOf(incoming, emptyKey, outgoing)) store.saveMessage(message)
        val exported = db.backupEnvelope("test", "test", base).messages.associateBy { it.id }
        assertEquals(DeduplicationKey.contentBased(contact.id, null, null, UInt.MAX_VALUE, "e\u0301"), exported[incoming.id]?.deduplicationKey)
        assertEquals("", exported[emptyKey.id]?.deduplicationKey); assertNull(exported[outgoing.id]?.deduplicationKey)
        assertNull(assertNotNull(store.fetchMessage(EntityKey(radio, incoming.id))).deduplicationKey)
        assertEquals(incoming.sortDate, exported[incoming.id]?.sortDate)
        assertEquals("out-${outgoing.id.canonicalString()}", com.meshcoreone.android.core.data.backup.messageBackupKey(outgoing))
    }

    @Test fun deliveredManualRetryRowDoesNotEmitAnotherPacketOrLoseItsPersistedStatus() = runTest(scheduler) {
        seed()
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "already delivered",
            timestamp = 100u, status = MessageStatus.DELIVERED)
        store.saveMessage(message)
        val dto = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.replacePendingSendForRetry(message.id, dto)
        val g = RadioGeneration(5); g.start(); g.signals.set(DeviceConnectionState.READY)
        g.queue.hydrate(); runCurrent(); g.queue.awaitDrainCompletion()
        assertTrue(g.firmware.sent().isEmpty()); assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertTrue(store.fetchPendingSends(radio).isEmpty()); g.stop()
    }

    @Test fun twoRadioGenerationsDoNotCloseTheProcessStoreOrDuplicateMonitors() = runTest(scheduler) {
        seed()
        val first = RadioGeneration(6); first.start(); first.service.startEventMonitoring(); first.service.startEventMonitoring()
        val message = first.service.sendDirectMessage("one physical generation", contact)
        runCurrent(); assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        first.stop()
        val second = RadioGeneration(7); second.start(); second.service.startEventMonitoring()
        assertEquals(message.id, store.fetchMessage(EntityKey(radio, message.id))?.id)
        assertEquals(contact.id, store.fetchContact(EntityKey(radio, contact.id))?.id)
        val another = second.service.sendDirectMessage("successor", contact); runCurrent()
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, another.id))?.status); second.stop()
    }

    @Test fun actualClosedRepositoryFailureCannotAdvanceAttemptCountOrSendOnTheWire() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(8); g.start()
        val message = g.service.createPendingMessage("storage failure", contact)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio).copy(sequence = 1))
        g.signals.set(DeviceConnectionState.CONNECTED); g.queue.hydrate(); runCurrent()
        store.close()
        g.signals.set(DeviceConnectionState.READY); runCurrent()
        assertTrue(g.firmware.sent().isEmpty())
        assertFalse(g.queue.shutdown().isComplete); g.service.close(); g.session.stop(); g.scope.cancel()
    }

    @Test fun acceptedBlockedInsertFinishesBeforeShutdownAndSuccessorHydration() = runTest(scheduler) {
        seed()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val actual = store
        val blocked = object : PersistenceStoreProtocol by actual {
            override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDTO): Long {
                entered.complete(Unit); release.await()
                return actual.insertPendingSendAssigningSequence(dto)
            }
        }
        val first = RadioGeneration(9, blocked); first.start()
        val message = first.service.createPendingMessage("accepted before shutdown", contact)
        val enqueue = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { first.queue.enqueueDM(DirectMessageEnvelope(message.id, contact.id)) }
        val closing = async(start = CoroutineStart.UNDISPATCHED) { first.queue.shutdown() }
        assertFalse(entered.isCompleted); assertFalse(closing.isCompleted)
        runCurrent(); assertTrue(entered.isCompleted)
        var hydrated = false
        val second = RadioGeneration(10)
        val successor = async { closing.await(); second.start(); second.queue.hydrate(); hydrated = true }
        runCurrent(); assertFalse(hydrated); assertTrue(store.fetchPendingSends(radio).isEmpty())
        enqueue.cancelAndJoin()
        release.complete(Unit); successor.await()
        assertEquals(1, store.fetchPendingSends(radio).size)
        second.signals.set(DeviceConnectionState.READY); runCurrent(); second.queue.awaitDrainCompletion()
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(1, second.firmware.sent().size); assertTrue(first.firmware.sent().isEmpty())
        first.stop(); second.stop()
    }

    @Test fun acceptedInsertStorageFailureIsIncludedInShutdownInsteadOfBeingLostAfterItReturns() = runTest(scheduler) {
        seed()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val actual = store
        val blocked = object : PersistenceStoreProtocol by actual {
            override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDTO): Long {
                entered.complete(Unit); release.await()
                return actual.insertPendingSendAssigningSequence(dto)
            }
        }
        val g = RadioGeneration(11, blocked); g.start()
        val message = g.service.createPendingMessage("failing accepted insert", contact)
        val enqueue = backgroundScope.async {
            try {
                g.queue.enqueueDM(DirectMessageEnvelope(message.id, contact.id))
                null
            } catch (failure: ChatSendQueueServiceException) { failure }
        }
        runCurrent(); assertTrue(entered.isCompleted)
        val closing = async { g.queue.shutdown() }; runCurrent(); assertFalse(closing.isCompleted)
        store.close(); release.complete(Unit)
        val failure = assertNotNull(enqueue.await())
        assertIs<PersistenceStoreException>(failure.cause)
        val report = closing.await()
        assertFalse(report.isComplete)
        assertTrue(report.issues.any { it.cause is ChatSendQueueServiceException && it.cause.cause is PersistenceStoreException })
        assertTrue(g.firmware.sent().isEmpty()); g.service.close(); g.session.stop(); g.scope.cancel()
    }

    @Test fun realAckDuringBlockedRetryStatusPreventsAnotherWireAttempt() = runTest(scheduler) {
        seed()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val actual = store
        val blocked = object : PersistenceStoreProtocol by actual {
            override suspend fun updateMessageRetryStatus(key: EntityKey, status: MessageStatus, retryAttempt: Long, maxRetryAttempts: Long) {
                entered.complete(Unit); release.await()
                actual.updateMessageRetryStatus(key, status, retryAttempt, maxRetryAttempts)
            }
        }
        val g = RadioGeneration(12, blocked, com.meshcoreone.android.core.services.messaging.MessageServiceConfig(maxAttempts = 2, floodAfter = 5))
        g.start(); g.service.startEventMonitoring(); g.firmware.acknowledge = false
        val sending = async { g.service.sendMessageWithRetry("Room retry gap", contact) }
        runCurrent(); advanceTimeBy(13); runCurrent(); assertTrue(entered.isCompleted)
        g.firmware.pushAck(assertNotNull(g.firmware.lastAck)); runCurrent()
        release.complete(Unit)
        assertEquals(MessageStatus.DELIVERED, sending.await().status); assertEquals(1, g.firmware.sent().size)
        g.stop()
    }

    @Test fun trueAckAndFinalReadFailureRecoverOneResendClaimWithoutRecountingANewClaim() = runTest(scheduler) {
        seed()
        var countWritten = false; var failFinal = true
        val actual = store
        val fault = object : PersistenceStoreProtocol by actual {
            override suspend fun incrementMessageSendCount(key: EntityKey): Long =
                actual.incrementMessageSendCount(key).also { countWritten = true }
            override suspend fun fetchMessage(key: EntityKey): MessageDTO? {
                if (countWritten && failFinal) {
                    failFinal = false
                    throw PersistenceStoreException(PersistenceStoreError.FetchFailed("final-read-only"), IllegalStateException("deterministic"))
                }
                return actual.fetchMessage(key)
            }
        }
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "Room resend recovery", timestamp = 100u, status = MessageStatus.SENT)
        store.saveMessage(message)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id, true), radio).copy(sequence = 1))
        val g = RadioGeneration(13, fault); g.start(); g.service.startEventMonitoring()
        g.queue.hydrate(); runCurrent(); g.signals.set(DeviceConnectionState.READY); runCurrent()
        assertEquals(2L, store.fetchMessage(EntityKey(radio, message.id))?.sendCount); assertEquals(1, g.firmware.sent().size)
        assertTrue(store.hasPendingSend(EntityKey(radio, message.id)))
        g.queue.transportDidOpen(); runCurrent(); g.queue.awaitDrainCompletion()
        assertEquals(2L, store.fetchMessage(EntityKey(radio, message.id))?.sendCount); assertEquals(1, g.firmware.sent().size)
        advanceTimeBy(1000); g.queue.enqueueDM(DirectMessageEnvelope(message.id, contact.id, true)); runCurrent(); g.queue.awaitDrainCompletion()
        assertEquals(3L, store.fetchMessage(EntityKey(radio, message.id))?.sendCount); assertEquals(2, g.firmware.sent().size)
        g.stop()
    }

    @Test fun persistedCallbackUsesRealRoomAndCanCloseWithoutJoiningItsOwnSend() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(14); g.start(); var callbacks = 0
        withTimeout(2000) {
            assertFailsWith<com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException> {
                g.service.sendMessageWithRetry("Room callback close", contact, onMessageCreated = {
                    callbacks++
                    assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, it.id))?.status)
                    assertTrue(g.service.close().isComplete)
                })
            }
        }
        assertEquals(1, callbacks); assertTrue(g.firmware.sent().isEmpty())
        assertEquals(contact.id, store.fetchContact(EntityKey(radio, contact.id))?.id)
        g.queue.shutdown(); g.session.stop(); g.scope.cancel()
    }

    @Test fun unacknowledgedResendRetiredByFailAllDoesNotIncrementTheActualRoomCount() = runTest(scheduler) {
        seed()
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "unacknowledged manual resend",
            timestamp = 100u, status = MessageStatus.SENT)
        store.saveMessage(message)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id, true), radio).copy(sequence = 1))
        val g = RadioGeneration(15); g.start(); g.firmware.acknowledge = false
        g.queue.hydrate(); runCurrent(); g.signals.set(DeviceConnectionState.READY); runCurrent()
        g.service.failAllPendingMessages(); advanceTimeBy(13); runCurrent(); g.queue.awaitDrainCompletion()
        val actual = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(MessageStatus.FAILED, actual.status); assertEquals(1L, actual.sendCount)
        assertEquals(1, g.firmware.sent().size); assertTrue(store.fetchPendingSends(radio).isEmpty()); g.stop()
    }

    @Test fun actualRoomExpiryCannotTurnMissingAckTrackingIntoAConfirmedResend() = runTest(scheduler) {
        seed()
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "expired manual resend",
            timestamp = 100u, status = MessageStatus.SENT)
        store.saveMessage(message)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id, true), radio).copy(sequence = 1))
        val g = RadioGeneration(16, config = com.meshcoreone.android.core.services.messaging.MessageServiceConfig(ackGiveUpWindow = 0.0))
        g.start(); g.firmware.acknowledge = false
        g.queue.hydrate(); runCurrent(); g.signals.set(DeviceConnectionState.READY); runCurrent()
        wallOffsetMillis = 1000
        g.service.checkExpiredAcks(); advanceTimeBy(13); runCurrent(); g.queue.awaitDrainCompletion()
        val actual = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(MessageStatus.FAILED, actual.status); assertEquals(1L, actual.sendCount)
        assertEquals(1, g.firmware.sent().size); g.stop()
    }

    @Test fun genuineRoomAckRetiresItsLookupBeforeAcceptanceAndCountsExactlyOneResend() = runTest(scheduler) {
        seed()
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "early confirmed resend",
            timestamp = 100u, status = MessageStatus.SENT)
        store.saveMessage(message)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id, true), radio).copy(sequence = 1))
        val g = RadioGeneration(17); g.start(); g.service.startEventMonitoring()
        val gate = CompletableDeferred<Unit>(); g.firmware.acceptanceGate = gate; g.firmware.ackBeforeAcceptance = true
        g.queue.hydrate(); runCurrent(); g.signals.set(DeviceConnectionState.READY); runCurrent()
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(1L, store.fetchMessage(EntityKey(radio, message.id))?.sendCount)
        gate.complete(Unit); runCurrent(); g.queue.awaitDrainCompletion()
        assertEquals(2L, store.fetchMessage(EntityKey(radio, message.id))?.sendCount)
        assertEquals(1, g.firmware.sent().size); g.stop()
    }

    @Test fun manualPollingRoomConsumerCanCloseWithAnExplicitUnfinishedRecordReport() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(18); g.start(); g.signals.set(DeviceConnectionState.READY)
        val p = g.poller()
        var report: TeardownReport? = null
        var savedID: UUID? = null
        p.setContactMessageHandler { wire, resolved, _ ->
            assertEquals(contact.id, resolved?.id)
            val message = MessageDTO(radioId = radio, contactID = contact.id, text = wire.text,
                timestamp = wire.senderTimestamp.epochSecond.toUInt(), direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED)
            store.saveMessage(message); savedID = message.id
            report = p.close()
        }
        p.startMessageEventMonitoring()
        g.firmware.incomingMessages += incomingContactPacket("manual Room consumer")
        val polling = backgroundScope.async { p.pollAllMessages() }
        withTimeout(2000) { assertFailsWith<CancellationException> { polling.await() } }
        runCurrent(); assertFalse(assertNotNull(report).isComplete); assertEquals(1, p.undeliveredCount)
        assertEquals(0L, p.pendingHandlerCount); assertFalse(p.hasMessageHandlersWired)
        assertEquals("manual Room consumer", store.fetchMessage(EntityKey(radio, assertNotNull(savedID)))?.text)
        assertEquals(contact.id, store.fetchContact(EntityKey(radio, contact.id))?.id); g.stop()
    }

    @Test fun livePollingRoomConsumerCanAwaitItsOwnGenerationCloseWithoutLeakingHandlers() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(19); g.start(); g.signals.set(DeviceConnectionState.READY)
        val p = g.poller()
        val report = CompletableDeferred<TeardownReport>()
        p.setContactMessageHandler { _, resolved, _ ->
            assertEquals(contact.id, resolved?.id)
            report.complete(p.close())
        }
        p.startMessageEventMonitoring()
        g.firmware.pushPacket(incomingContactPacket("live Room consumer"))
        assertFalse(withTimeout(2000) { report.await() }.isComplete)
        runCurrent(); assertEquals(1, p.undeliveredCount); assertEquals(0L, p.pendingHandlerCount)
        assertFalse(p.hasMessageHandlersWired); assertEquals(contact.id, store.fetchContact(EntityKey(radio, contact.id))?.id)
        g.stop()
    }

    @Test fun deletedAcceptedAckDoesNotPoisonActualRoomExpiryOrTheLiveMonitor() = runTest(scheduler) {
        seed(); val g = RadioGeneration(20); g.start(); g.signals.set(DeviceConnectionState.READY)
        g.firmware.acknowledge = false; g.service.startEventMonitoring(); g.service.startAckExpiryChecking()
        val deleted = g.service.sendDirectMessage("deleted accepted DM", contact)
        val deletedAck = assertNotNull(g.firmware.lastAck)
        val later = g.service.sendDirectMessage("later accepted DM", contact)
        store.deleteMessage(EntityKey(radio, deleted.id)); g.firmware.pushAck(deletedAck); runCurrent()
        assertNull(store.fetchMessage(EntityKey(radio, deleted.id))); assertEquals(1, g.service.pendingAckCount)
        advanceTimeBy(35_001); runCurrent()
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, later.id))?.status)
        assertEquals(0, g.service.pendingAckCount)
        assertTrue(g.service.isAckExpiryCheckingActive); assertTrue(g.service.isEventMonitoringActive)
        g.firmware.acknowledge = true
        val next = g.service.sendDirectMessage("monitor still receives", contact); runCurrent()
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, next.id))?.status)
        assertEquals(3, g.firmware.sent().size); g.stop()
    }

    @Test fun actualRoomExpiryIsolatesOneStorageFailureAndCanRecoverItsRetainedEntry() = runTest(scheduler) {
        seed(); val actual = store
        var blockedID: UUID? = null
        val cause = PersistenceStoreException(PersistenceStoreError.SaveFailed("one expiry"), IllegalStateException("deterministic"))
        val faulty = object : PersistenceStoreProtocol by actual {
            override suspend fun updateMessageStatusUnlessDelivered(key: EntityKey, status: MessageStatus): Boolean {
                if (key.id == blockedID && status == MessageStatus.FAILED) throw cause
                return actual.updateMessageStatusUnlessDelivered(key, status)
            }
        }
        val g = RadioGeneration(21, faulty); g.start(); g.signals.set(DeviceConnectionState.READY); g.firmware.acknowledge = false
        val first = g.service.sendDirectMessage("first expiry", contact)
        val later = g.service.sendDirectMessage("later expiry", contact); blockedID = first.id
        wallOffsetMillis = 60_000; g.service.checkExpiredAcks()
        assertEquals(MessageStatus.SENT, store.fetchMessage(EntityKey(radio, first.id))?.status)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, later.id))?.status)
        assertEquals(1, g.service.pendingAckCount)
        blockedID = null; g.service.checkExpiredAcks()
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, first.id))?.status)
        assertEquals(0, g.service.pendingAckCount); assertTrue(g.service.close().isComplete); g.stop()
    }

    @Test fun realSessionPushAfterManualNoMoreIsDeliveredOnceIntoRoomWithLiveContext() = runTest(scheduler) {
        seed(); val g = RadioGeneration(22); g.start(); g.signals.set(DeviceConnectionState.READY)
        val p = g.poller(); val saved = mutableListOf<UUID>()
        p.setContactMessageHandler { wire, resolved, context ->
            assertEquals(DeliveryContext.Live, context); saved += saveIncomingContact(wire, resolved).id
        }
        p.startAutoFetch(radio); runCurrent()
        g.firmware.afterNoMore = {
            g.firmware.incomingMessages += incomingContactPacket("late Room message")
            g.firmware.pushPacket(Bytes.of(ResponseCode.MESSAGES_WAITING.rawValue.toInt()))
        }
        assertEquals(0L, p.pollAllMessages()); advanceUntilIdle()
        assertEquals(1, saved.size)
        assertEquals("late Room message", store.fetchMessage(EntityKey(radio, saved.single()))?.text)
        assertEquals(1, store.fetchMessages(EntityKey(radio, contact.id)).size)
        assertTrue(g.firmware.incomingMessages.isEmpty()); assertTrue(p.close().isComplete); g.stop()
    }

    @Test fun disconnectedInlineSendCommitsFailedInRoomButSettledAcceptedSendStaysSent() = runTest(scheduler) {
        seed(); val g = RadioGeneration(23); g.start(); g.signals.set(DeviceConnectionState.READY); g.firmware.acknowledge = false
        val settled = g.service.sendDirectMessage("settled before disconnect", contact)
        g.firmware.acceptanceGate = CompletableDeferred()
        val sending = backgroundScope.async { g.service.sendDirectMessage("inline at disconnect", contact) }
        runCurrent()
        val inline = store.fetchMessages(EntityKey(radio, contact.id)).single { it.text == "inline at disconnect" }
        g.signals.set(DeviceConnectionState.DISCONNECTED); withTimeout(2000) { g.service.close() }
        assertFailsWith<CancellationException> { sending.await() }
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, inline.id))?.status)
        assertEquals(MessageStatus.SENT, store.fetchMessage(EntityKey(radio, settled.id))?.status)
        assertEquals(contact.id, store.fetchContact(EntityKey(radio, contact.id))?.id)
        assertEquals(2, g.firmware.sent().size); g.stop()
    }

    @Test fun disconnectedQueuedRetryKeepsItsActualRoomEnvelopeAndClearsTheRetryingStatus() = runTest(scheduler) {
        seed(); val g = RadioGeneration(24); g.start(); g.signals.set(DeviceConnectionState.READY); g.firmware.acknowledge = false
        val message = g.service.createPendingMessage("queued disconnect", contact)
        val pending = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio).copy(sequence = 1)
        store.upsertPendingSend(pending); g.queue.hydrate(); runCurrent(); advanceTimeBy(13); runCurrent()
        assertEquals(MessageStatus.RETRYING, store.fetchMessage(EntityKey(radio, message.id))?.status)
        val stamp = store.fetchMessage(EntityKey(radio, message.id))?.timestamp
        g.signals.set(DeviceConnectionState.DISCONNECTED); g.queue.shutdown()
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(stamp, store.fetchMessage(EntityKey(radio, message.id))?.timestamp)
        assertEquals(pending.id, store.fetchPendingSends(radio).single().id)
        assertEquals(1L, store.fetchPendingSends(radio).single().attemptCount)
        advanceTimeBy(1000); runCurrent(); assertEquals(2, g.firmware.sent().size); g.stop()
    }

    @Test fun malformedRoomEnvelopeDoesNotStopValidLaterHydrationOrLoseItsRepairableRow() = runTest(scheduler) {
        seed(); val g = RadioGeneration(25); g.start(); g.signals.set(DeviceConnectionState.READY)
        val malformed = g.service.createPendingMessage("malformed pending", contact)
        val bad = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(malformed.id, contact.id), radio).copy(sequence = 1)
        store.upsertPendingSend(bad.copy(contactID = null))
        val valid = g.service.createPendingMessage("valid later pending", contact)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(valid.id, contact.id), radio).copy(sequence = 2))
        assertFailsWith<ChatSendQueueServiceException> { g.queue.hydrate() }; runCurrent(); g.queue.awaitDrainCompletion()
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, valid.id))?.status)
        assertEquals(listOf(bad.id), store.fetchPendingSends(radio).map { it.id })
        assertEquals(1, g.firmware.sent().size)
        store.upsertPendingSend(bad); g.queue.hydrate(); runCurrent(); g.queue.awaitDrainCompletion()
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, malformed.id))?.status)
        assertTrue(store.fetchPendingSends(radio).isEmpty()); assertEquals(2, g.firmware.sent().size)
        assertTrue(g.queue.shutdown().isComplete); g.stop()
    }

    @Test fun preflightFaultRestoresActualFailedRoomRowToPendingWithoutAdvancingItsCounter() = runTest(scheduler) {
        seed(); val actual = store
        val cause = PersistenceStoreException(PersistenceStoreError.SaveFailed("preflight"), IllegalStateException("deterministic"))
        val faulty = object : PersistenceStoreProtocol by actual {
            override suspend fun incrementPendingSendAttemptCount(key: EntityKey): Long? = throw cause
        }
        val g = RadioGeneration(26, faulty); g.start(); g.signals.set(DeviceConnectionState.READY)
        val message = g.service.createPendingMessage("preflight status", contact)
        store.updateMessageStatus(EntityKey(radio, message.id), MessageStatus.FAILED)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio).copy(sequence = 1))
        g.queue.hydrate(); runCurrent()
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(0L, store.fetchPendingSends(radio).single().attemptCount)
        assertTrue(g.firmware.sent().isEmpty())
        assertTrue(g.queue.shutdown().issues.any { it.cause === cause }); g.stop()
    }

    @Test fun missingCLIConsumerDoesNotBlockLaterRoomPersistenceAndItsRecordCanBeRetried() = runTest(scheduler) {
        seed(); val g = RadioGeneration(27); g.start(); g.signals.set(DeviceConnectionState.READY)
        val p = g.poller(); val saved = mutableListOf<UUID>()
        p.setContactMessageHandler { wire, resolved, _ -> saved += saveIncomingContact(wire, resolved).id }
        g.firmware.incomingMessages += incomingContactPacket("unwired CLI", 1u)
        g.firmware.incomingMessages += incomingContactPacket("plain persisted after CLI")
        assertFailsWith<MessagePollingException> { p.pollAllMessages() }
        assertEquals(1, saved.size); assertEquals(1, p.undeliveredCount)
        assertEquals("plain persisted after CLI", store.fetchMessage(EntityKey(radio, saved.single()))?.text)
        assertTrue(g.firmware.incomingMessages.isEmpty())
        p.setCLIMessageHandler { wire, resolved -> saved += saveIncomingContact(wire, resolved).id }
        assertEquals(0L, p.pollAllMessages()); assertEquals(2, saved.size)
        assertEquals(2, store.fetchMessages(EntityKey(radio, contact.id)).size)
        assertEquals(0, p.undeliveredCount); assertTrue(p.close().isComplete); g.stop()
    }

    @Test fun committedInlineRoomSaveAtDisconnectFailsBeforeTheWireAndPreservesTheProcessStore() = runTest(scheduler) {
        seed(); val actual = store
        val committed = CompletableDeferred<UUID>(); val release = CompletableDeferred<Unit>()
        val blocked = object : PersistenceStoreProtocol by actual {
            override suspend fun saveMessage(dto: MessageDTO) {
                actual.saveMessage(dto); committed.complete(dto.id); release.await()
            }
        }
        val g = RadioGeneration(28, blocked); g.start(); g.signals.set(DeviceConnectionState.READY)
        val send = backgroundScope.async {
            try { g.service.sendDirectMessage("committed at disconnect", contact); null }
            catch (failure: MessageServiceException) { failure }
        }
        runCurrent(); val id = committed.await()
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, id))?.status)
        g.signals.set(DeviceConnectionState.DISCONNECTED); release.complete(Unit)
        assertEquals(MessageServiceError.NotConnected, assertNotNull(send.await()).error)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, id))?.status)
        assertTrue(g.firmware.sent().isEmpty())
        assertEquals(contact.id, store.fetchContact(EntityKey(radio, contact.id))?.id); g.stop()
    }

    @Test fun acceptedRoomBookkeepingFinishesBeforeDisconnectTeardownWithoutInventingAnAck() = runTest(scheduler) {
        seed(); val actual = store
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val blocked = object : PersistenceStoreProtocol by actual {
            override suspend fun updateMessageAck(key: EntityKey, ackCode: UInt, status: MessageStatus, roundTripTime: UInt?) {
                if (status == MessageStatus.SENT) { entered.complete(Unit); release.await() }
                actual.updateMessageAck(key, ackCode, status, roundTripTime)
            }
        }
        val g = RadioGeneration(29, blocked); g.start(); g.signals.set(DeviceConnectionState.READY); g.firmware.acknowledge = false
        val send = backgroundScope.async { g.service.sendDirectMessage("accepted Room bookkeeping", contact) }
        runCurrent(); assertTrue(entered.isCompleted)
        val message = store.fetchMessages(EntityKey(radio, contact.id)).single()
        g.signals.set(DeviceConnectionState.DISCONNECTED)
        val closing = backgroundScope.async { g.service.close() }; runCurrent(); assertFalse(closing.isCompleted)
        release.complete(Unit); closing.await()
        assertFailsWith<CancellationException> { send.await() }
        val saved = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(MessageStatus.SENT, saved.status); assertNull(saved.roundTripTime)
        assertEquals(1, g.firmware.sent().size); g.stop()
    }

    @Test
    @OriginalCase("MessageServiceSendDMBookkeepingTests::sendDirectMessage keeps ACK tracking and does not report failure when post-send bookkeeping fails()")
    fun sourceAcceptedDMKeepsAckTrackingWhenItsSelectedRealRoomRowWasDeleted() = runTest(scheduler) {
        contact = contact.copy(publicKey = Bytes(ByteArray(32) { 0xAB.toByte() }), name = "TestContact",
            typeRawValue = 1u, lastHeardTimestamp = null)
        seed()
        val g = RadioGeneration(28, sessionTimeout = 10.0)
        g.firmware.acknowledge = false
        g.firmware.acceptedAck = Bytes.of(0xAB, 0xCD, 0xEF, 0x12)
        g.firmware.suggestedTimeout = 5000u
        g.firmware.acceptanceGate = CompletableDeferred()
        g.start()
        val events = g.service.statusEvents()
        val sending = g.scope.async { g.service.sendDirectMessage("hi", contact) }
        runCurrent()
        assertEquals(1, g.firmware.sent().size)
        val saved = store.fetchMessages(EntityKey(radio, contact.id), 10, 0).first()
        store.deleteMessage(EntityKey(radio, saved.id))
        assertNull(store.fetchMessage(EntityKey(radio, saved.id)))
        g.firmware.acceptanceGate?.complete(Unit)
        assertFailsWith<Exception> { sending.await() }
        assertEquals(1, g.service.pendingAckCount)
        g.service.finishStatusEvents()
        assertFalse(events.events.toList().any { it.event is MessageStatusEvent.Failed })
        g.stop()
    }

    private suspend fun saveIncomingContact(wire: ContactMessage, resolved: ContactDTO?): MessageDTO {
        assertEquals(contact.id, resolved?.id)
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = wire.text,
            timestamp = wire.senderTimestamp.epochSecond.toUInt(), direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED)
        store.saveMessage(message)
        return message
    }

    private fun incomingContactPacket(text: String, type: UByte = 0u): Bytes =
        ByteWriter().appendUInt8(ResponseCode.CONTACT_MESSAGE_RECEIVED.rawValue).append(target.prefix(6))
            .appendUInt8(0u).appendUInt8(type).appendUInt32LE(42u).append(Bytes.utf8(text)).toBytes()

    private class RoomSignals(private val token: SessionToken) : ConnectionSignals {
        private val value = MutableStateFlow(snapshot(token, DeviceConnectionState.CONNECTED))
        override val snapshot: kotlinx.coroutines.flow.StateFlow<ConnectionSnapshot> get() = value
        private val channels = mutableListOf<kotlinx.coroutines.channels.Channel<ConnectionSnapshot>>()
        fun set(rung: DeviceConnectionState) {
            val next = snapshot(token, rung); value.value = next
            channels.forEach { it.trySend(next).getOrThrow() }
        }
        override suspend fun subscribeTransitions(): ConnectionSubscription {
            val channel = kotlinx.coroutines.channels.Channel<ConnectionSnapshot>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            channels += channel
            return object : ConnectionSubscription {
                override val initial = value.value
                override val transitions: Flow<ConnectionSnapshot> = flow { try { for (next in channel) emit(next) } finally { close() } }
                override fun close() { channels.remove(channel); channel.cancel() }
            }
        }
        companion object {
            fun snapshot(token: SessionToken, state: DeviceConnectionState) =
                ConnectionSnapshot(state,
                    if (state == DeviceConnectionState.DISCONNECTED) ConnectionState.Disconnected else ConnectionState.Connected,
                    null, ConnectionIntent.WantsConnection(),
                    if (state == DeviceConnectionState.DISCONNECTED) null else token, null)
        }
    }

    private inner class RoomFirmware : MeshTransport {
        private val mock = MockTransport()
        var error: UByte? = null
        var acknowledge = true
        var ackBeforeAcceptance = false
        var acceptanceGate: CompletableDeferred<Unit>? = null
        var acceptedAck: Bytes? = null
        var suggestedTimeout: UInt = 10u
        var lastAck: Bytes? = null
        val incomingMessages = ArrayDeque<Bytes>()
        var afterNoMore: (suspend () -> Unit)? = null
        fun sent(): List<Bytes> = mock.sentData.filter { it[0] == CommandCode.SEND_MESSAGE.rawValue }
        override suspend fun isConnected() = mock.isConnected()
        override suspend fun connect() = mock.connect()
        override suspend fun disconnect() = mock.disconnect()
        override suspend fun receivedData() = mock.receivedData()
        override suspend fun supportsWriteWithoutResponse() = false
        override suspend fun supportsPipelinedReads() = false
        override suspend fun send(data: Bytes) {
            mock.send(data)
            when (CommandCode.fromRawValue(data[0])) {
                CommandCode.APP_START -> mock.simulateReceive(ByteWriter().appendUInt8(ResponseCode.SELF_INFO.rawValue)
                    .appendUInt8(1u).appendUInt8(22u).appendUInt8(22u).append(self)
                    .appendInt32LE(0).appendInt32LE(0).appendUInt8(0u).appendUInt8(0u).appendUInt8(0u)
                    .appendUInt32LE(915_000u).appendUInt32LE(125_000u).appendUInt8(7u).appendUInt8(5u).append(Bytes.utf8("Test")).toBytes())
                CommandCode.SEND_MESSAGE -> {
                    error?.let { mock.simulateError(it); return }
                    val input = ByteWriter().appendUInt32LE(data.readUInt32LE(3))
                        .appendUInt8((data[2].toInt() and 3).toUByte()).append(data.slice(13, data.size)).append(self).toBytes()
                    val ack = acceptedAck ?: Bytes(MessageDigest.getInstance("SHA-256").digest(input.toByteArray())).prefix(4)
                    lastAck = ack
                    if (acknowledge && ackBeforeAcceptance) pushAck(ack)
                    acceptanceGate?.await()
                    mock.simulateReceive(ByteWriter().appendUInt8(ResponseCode.MESSAGE_SENT.rawValue).appendUInt8(0u)
                        .append(ack).appendUInt32LE(suggestedTimeout).toBytes())
                    if (acknowledge && !ackBeforeAcceptance) pushAck(ack)
                }
                CommandCode.GET_CONTACT_BY_KEY -> mock.simulateError(2u)
                CommandCode.RESET_PATH -> mock.simulateOK()
                CommandCode.GET_MESSAGE -> {
                    val empty = incomingMessages.isEmpty()
                    mock.simulateReceive(if (empty) Bytes.of(ResponseCode.NO_MORE_MESSAGES.rawValue.toInt()) else incomingMessages.removeFirst())
                    if (empty) afterNoMore?.also { afterNoMore = null }?.invoke()
                }
                else -> throw AssertionError("Unexpected raw Room-test command")
            }
        }
        suspend fun pushAck(ack: Bytes) {
            mock.simulateReceive(ByteWriter().appendUInt8(ResponseCode.ACK.rawValue).append(ack).appendUInt32LE(123u).toBytes())
        }
        suspend fun pushPacket(packet: Bytes) { mock.simulateReceive(packet) }
    }
}
