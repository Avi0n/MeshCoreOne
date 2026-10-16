package one.meshcore.shared.connection

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import one.meshcore.shared.chat.IncomingMessageEvent
import one.meshcore.shared.chat.contactMessage
import one.meshcore.shared.contact.ContactServiceEvent
import one.meshcore.shared.contact.events
import one.meshcore.shared.contact.hasPendingAdvertKey
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ContactEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.MessageEntity
import one.meshcore.shared.persistence.RoomDiscoveredNodeStore
import one.meshcore.shared.persistence.direction
import one.meshcore.shared.persistence.productionSchemaPolicy
import one.meshcore.shared.persistence.toDto
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.transport.MeshLink
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class ConnectionInitialBacklogMaterializerFixture {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val first = ConnectionInitialBacklogMaterializerSession(
        PUBLIC_A,
        emptyList(),
        backlog = mutableListOf(
            GetMessageResult.ContactMessageReceived(
                contactMessage(
                    BODY,
                    NOW.toLong(),
                    senderPrefix = oldFrame.publicKey.copyOf(PREFIX_BYTES),
                ),
            ),
        ),
        holdReturn = true,
    )
    private val freshA = ConnectionInitialBacklogMaterializerSession(PUBLIC_A, listOf(newFrame, newOnly))
    private val freshB = ConnectionInitialBacklogMaterializerSession(PUBLIC_B, listOf(foreign))
    private var firstCreated = false
    private val links = mutableListOf<FakeMeshLink>()
    private val handles = mutableMapOf<MeshLink, String>()
    private val callers = mutableListOf<Job>()
    private val prefs = InMemoryPreferencesStore()
    private val file = File.createTempFile("initial-backlog-materializer", ".db").also { it.delete() }
    val database = Room.databaseBuilder<MeshDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver()).productionSchemaPolicy().build()
    val rows = ConnectionInitialContactReturnRows(database)
    val manager = ConnectionManager(
        database = database,
        scope = scope,
        linkFactory = { handle ->
            FakeMeshLink().also {
                links += it
                handles[it] = handle
            }
        },
        sessionFactory = { link ->
            when {
                !firstCreated -> first.also { firstCreated = true }
                handles[link] == DEVICE_A -> freshA
                else -> freshB
            }
        },
        nowEpochSeconds = { NOW },
        ports = ConnectionManagerPorts(
            hasSystemPairingRegistry = { false },
            lastConnectionStore = LastConnectionStore(prefs),
            intentStore = ConnectionIntentStore(prefs),
            discoveredNodes = RoomDiscoveredNodeStore(database.discoveredNodeDao()),
        ),
    )

    private var contactEvents: ReceiveChannel<ContactServiceEvent>? = null
    private var incomingEvents: ReceiveChannel<IncomingMessageEvent>? = null
    var liveEventsQualified = false
        private set
    var liveRetentionQualified = false
        private set

    init {
        first.onGetChannels = {
            first.eventSubscribers.first { it > 0 }
            val container = requireNotNull(manager.services)
            assertSame(first, manager.session)
            assertTrue(container.contactService.isSyncingContacts)
            assertTrue(database.contactDao().listByRadio(container.radioId).isEmpty())
            contactEvents = container.contactService.events()
            incomingEvents = container.incomingBroadcaster.subscribe()
            first.contacts = listOf(oldFrame)
            first.emit(MeshEvent.Advertisement(oldFrame.publicKey.copyOf()))
            while (!container.contactService.hasPendingAdvertKey(oldFrame.publicKey)) yield()
            assertTrue(database.contactDao().listByRadio(container.radioId).isEmpty())
        }
        first.beforeNextMessage = nextMessage@{
            val current = manager.services
            if (manager.session !== first || current?.sendQueue?.isRetired != false) return@nextMessage
            val container = requireNotNull(current)
            assertSame(first, manager.session)
            assertTrue(container.contactService.isSyncingContacts)
            assertTrue(container.contactService.hasPendingAdvertKey(oldFrame.publicKey))
            val p = contact(container.radioId)
            assertContentEquals(oldFrame.publicKey, p.publicKey)
            assertEquals(1, database.messageDao().listByRadio(container.radioId).count { it.text == BODY })
            val stored = database.messageDao().listByRadio(container.radioId).single { it.text == BODY }
            assertIncoming(stored, container.radioId, p.id)
            val applied = requireNotNull(contactEvents).tryReceive().getOrNull()
            assertEquals(
                ContactServiceEvent.ContactAdvertisementApplied(container.radioId, oldFrame.publicKey),
                applied,
            )
            assertNull(requireNotNull(contactEvents).tryReceive().getOrNull())
            val event = requireNotNull(incomingEvents).tryReceive().getOrNull()
            assertTrue(event is IncomingMessageEvent.DirectMessageReceived)
            assertEquals(p.id, event.contactID)
            assertEquals(
                expectedIncoming(stored.id, container.radioId, p.id).toDto().copy(senderKeyPrefix = null),
                event.message.copy(senderKeyPrefix = null),
            )
            assertContentEquals(oldFrame.publicKey.copyOf(PREFIX_BYTES), event.message.senderKeyPrefix)
            assertNull(requireNotNull(incomingEvents).tryReceive().getOrNull())
            liveEventsQualified = true
            liveRetentionQualified = true
        }
    }

    suspend fun start(): Held {
        val caller = scope.async { runCatching { manager.connect(DEVICE_A) } }.also { callers += it }
        val (operation, frame) = first.completed.await()
        assertTrue(caller.isActive && operation.isActive && caller.owns(operation))
        assertEquals(listOf<Long?>(null), first.getContactsSince)
        assertEquals(1, first.getChannelsCallCount)
        assertEquals(1, first.getMessageCount)
        assertEquals(listOf(oldFrame.publicKey.toList()), first.getContactKeys.map { it.toList() })
        assertContentEquals(oldFrame.publicKey, frame.publicKey)
        assertNotSame(oldFrame.publicKey, frame.publicKey)
        assertNotSame(oldFrame.outPath, frame.outPath)
        val container = requireNotNull(manager.services)
        assertSame(first, manager.session)
        assertEquals(ConnectionState.Syncing(DEVICE_A), manager.state.value)
        assertTrue(container.contactService.isSyncingContacts && manager.initialSyncInProgress)
        assertTrue(container.contactService.hasPendingAdvertKey(oldFrame.publicKey))
        assertTrue(container.isEventMonitoringActive && !container.sendQueue.isRetired)
        assertFalse(first.closed)
        assertTrue(database.contactDao().listByRadio(container.radioId).isEmpty())
        assertTrue(database.messageDao().listByRadio(container.radioId).isEmpty())
        noOutgoing()
        return Held(
            caller,
            container,
            operation,
            frame,
            manager.connectGeneration,
            requireNotNull(manager.link) as FakeMeshLink,
        )
    }

    fun assertIncoming(row: MessageEntity, radio: Uuid, contact: Uuid?) {
        assertNotNull(row.senderKeyPrefix)
        assertEquals(
            expectedIncoming(row.id, radio, contact).copy(senderKeyPrefix = null),
            row.copy(senderKeyPrefix = null),
        )
        assertContentEquals(oldFrame.publicKey.copyOf(PREFIX_BYTES), row.senderKeyPrefix)
    }

    private fun expectedIncoming(id: Uuid, radio: Uuid, contact: Uuid?) = MessageEntity(
        id = id, radioID = radio, text = BODY, timestamp = NOW.toLong(), createdAt = NOW, sortDate = NOW,
        directionRawValue = MessageDirection.INCOMING.raw, statusRawValue = MessageStatus.DELIVERED.raw,
        textTypeRawValue = TextType.PLAIN.raw, pathLength = 3, isRead = false, heardRepeats = 0,
        sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0, linkPreviewFetched = false,
        containsSelfMention = false, mentionSeen = false, failureSeen = false, timestampCorrected = false,
        routeTypeRawValue = -1, contactID = contact, snr = -5.0,
        senderKeyPrefix = oldFrame.publicKey.copyOf(PREFIX_BYTES),
        deduplicationKey = "dm-${contact?.value ?: "unknown"}-${NOW.toLong()}-D4D3EBC5",
    )

    suspend fun contact(radio: Uuid): ContactEntity = database.contactDao().listByRadio(radio)
        .single { it.publicKey.contentEquals(oldFrame.publicKey) }

    suspend fun replace(handle: String): ServiceContainer {
        manager.connect(handle)
        barrier()
        val session = if (handle == DEVICE_A) freshA else freshB
        val current = requireNotNull(manager.services)
        assertSame(session, manager.session)
        assertEquals(ConnectionState.Ready(handle), manager.state.value)
        assertTrue(current.isAutoFetching && current.isEventMonitoringActive && !current.sendQueue.isRetired)
        assertEquals(listOf<Long?>(null), session.getContactsSince)
        assertEquals(1, session.autoFetchStartCount)
        assertFalse(manager.initialSyncInProgress)
        noOutgoing()
        return current
    }

    suspend fun settleCancelledLookup(held: Held) {
        if (!held.operation.isCancelled) return
        assertFalse(first.completedReturnForwarded)
        joinOld(held)
    }

    suspend fun joinOld(held: Held): Throwable? {
        first.release.complete(Unit)
        val cause = try {
            held.caller.await().exceptionOrNull()
        } catch (cancelled: CancellationException) {
            cancelled
        }
        held.caller.join()
        held.operation.join()
        assertTrue(cause == null || cause is CancellationException, "unexpected old failure: $cause")
        barrier()
        assertTrue(held.caller.isCompleted && held.operation.isCompleted)
        assertFalse(held.container.contactService.isSyncingContacts)
        return cause
    }

    suspend fun assertCurrent(container: ServiceContainer, handle: String, generation: Long) {
        assertSame(container, manager.services)
        assertSame(if (handle == DEVICE_A) freshA else freshB, manager.session)
        assertEquals(ConnectionState.Ready(handle), manager.state.value)
        assertEquals(generation, manager.connectGeneration)
        assertEquals(container.radioId, manager.radioId)
        assertEquals(handle, manager.currentDeviceId)
        assertEquals(TransportType.BLUETOOTH, manager.currentTransportType)
        assertTrue(manager.wantsConnection && scope.coroutineContext.job.isActive)
        assertTrue(container.isAutoFetching && container.isEventMonitoringActive && !container.sendQueue.isRetired)
        assertFalse(manager.initialSyncInProgress)
        assertNull(manager.resyncJob)
        noOutgoing()
    }

    suspend fun noOutgoing() {
        assertTrue(listOf(first, freshA, freshB).all { it.sentMessages.isEmpty() })
        assertTrue(links.all { it.sent.isEmpty() })
        assertTrue(database.messageDao().listAll().all { it.direction == MessageDirection.INCOMING })
        database.deviceDao().listAll().forEach {
            assertTrue(database.pendingSendDao().listByRadio(it.radioID).isEmpty())
        }
    }

    suspend fun watermark(radio: Uuid) = database.deviceDao().listByRadio(radio).single().lastContactSync
    suspend fun preferences() = prefs.snapshot()
    suspend fun barrier() = coroutineScope { async(dispatcher) { Unit }.await() }

    fun close() {
        listOf(first, freshA, freshB).forEach { it.release.complete(Unit) }
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    callers.forEach { it.cancel() }
                    callers.forEach { it.join() }
                    manager.disconnect()
                    assertNull(manager.services)
                    assertNull(manager.session)
                    assertNull(manager.link)
                }
            }
        } finally {
            try {
                runBlocking(dispatcher) { withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() } }
            } finally {
                try {
                    database.close()
                } finally {
                    dispatcher.close()
                    val owned = listOf("", "-wal", "-shm").map { File(file.absolutePath + it) }
                    owned.forEach { it.delete() }
                    assertTrue(owned.none { it.exists() })
                }
            }
        }
    }

    data class Held(
        val caller: Deferred<Result<Unit>>,
        val container: ServiceContainer,
        val operation: Job,
        val frame: MeshContact,
        val generation: Long,
        val link: FakeMeshLink,
    )

    companion object {
        const val DEVICE_A = "AA:BB:CC:DD:EE:A1"
        const val DEVICE_B = "AA:BB:CC:DD:EE:B2"
        const val TIMEOUT_MS = 10_000L
        val PUBLIC_A = ByteArray(32) { 17 }
        private val PUBLIC_B = ByteArray(32) { 34 }
        const val NOW = 1_700_000_000.0
        const val BODY = "Synthetic initial backlog DM"
        private const val PREFIX_BYTES = 6
        val oldFrame = ConnectionInitialContactReturnRows.frame(ConnectionInitialContactReturnRows.KEY_P, fresh = false)
        val newFrame = ConnectionInitialContactReturnRows.frame(ConnectionInitialContactReturnRows.KEY_P, fresh = true)
        val newOnly = ConnectionInitialContactReturnRows.frame(ConnectionInitialContactReturnRows.KEY_N, fresh = true)
        private val foreign = ConnectionInitialContactReturnRows.frame(
            ConnectionInitialContactReturnRows.KEY_B,
            fresh = true,
        )
        private fun Job.owns(other: Job): Boolean = this === other || children.any { it.owns(other) }
    }
}
