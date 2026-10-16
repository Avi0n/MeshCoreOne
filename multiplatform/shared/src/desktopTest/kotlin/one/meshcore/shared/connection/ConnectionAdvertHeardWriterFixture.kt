package one.meshcore.shared.connection

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.contact.ContactPathEventCollector
import one.meshcore.shared.contact.ContactServiceEvent
import one.meshcore.shared.contact.events
import one.meshcore.shared.contact.hasPendingAdvertKey
import one.meshcore.shared.contact.updateContactAvatar
import one.meshcore.shared.contact.updateContactPreferences
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ContactEntity
import one.meshcore.shared.persistence.MeshDatabase
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
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class ConnectionAdvertHeardWriterFixture {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineName(QueuedAdvertHeardWriterDispatcher.ORIGIN),
    )
    private val first = ConnectionInitialContactReturnSession(PUBLIC_A, listOf(oldFrame, oldOnly))
    private val freshA = ConnectionInitialContactReturnSession(PUBLIC_A, listOf(newFrame, newOnly))
    private val freshB = ConnectionInitialContactReturnSession(PUBLIC_B, listOf(foreign))
    private var firstCreated = false
    private var initial: ServiceContainer? = null
    private val links = mutableListOf<FakeMeshLink>()
    private val handles = mutableMapOf<MeshLink, String>()
    private val collectors = mutableListOf<Job>()
    private val observers = mutableListOf<Job>()
    private val prefs = InMemoryPreferencesStore()
    private val file = File.createTempFile("advert-heard-writer", ".db").also { it.delete() }
    val queries = QueuedAdvertHeardWriterDispatcher()
    private var original: Job? = null
    private var finiteWriter: Job? = null
    val database = Room.databaseBuilder<MeshDatabase>(name = file.absolutePath)
        .setQueryCoroutineContext(queries)
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
        nowEpochSeconds = { NOW.toDouble() },
        ports = ConnectionManagerPorts(
            hasSystemPairingRegistry = { false },
            lastConnectionStore = LastConnectionStore(prefs),
            intentStore = ConnectionIntentStore(prefs),
            discoveredNodes = RoomDiscoveredNodeStore(database.discoveredNodeDao()),
        ),
    )

    suspend fun readyA(): ServiceContainer {
        manager.connect(DEVICE_A)
        barrier()
        val container = requireNotNull(manager.services).also { initial = it }
        assertSame(first, manager.session)
        assertCurrent(container, DEVICE_A, manager.connectGeneration)
        assertTrue(first.eventSubscribers.value > 0)
        rows.enrich(container.radioId, PUBLIC_A)
        val p = contact(container.radioId)
        database.contactDao().upsert(p.copy(lastHeardTimestamp = NOW - OLD_HEARD_OFFSET))
        noOutgoing()
        return container
    }

    suspend fun queueAdvert(container: ServiceContainer): Held {
        val collector = collectorJob(container.contactPathCollector).also {
            original = it
            collectors += it
        }
        assertTrue(collector.isActive)
        val appliedEvents = container.contactService.events()
        val applied = scope.async(start = CoroutineStart.UNDISPATCHED) {
            appliedEvents.consumeAsFlow().first {
                it is ContactServiceEvent.ContactAdvertisementApplied &&
                    it.radioID == container.radioId &&
                    it.publicKey.contentEquals(KEY_P)
            }
        }.also { observers += it }
        barrier()
        val before = contact(container.radioId)
        assertTrue(requireNotNull(before.lastHeardTimestamp) < NOW)
        queries.arm()
        first.emit(MeshEvent.Advertisement(KEY_P.copyOf()))
        val work = queries.entered.await()
        finiteWriter = finiteRoomOperation(work, collector)
        assertTrue(collector.isActive && requireNotNull(finiteWriter).isActive)
        assertFalse(work.ran.get())
        assertFalse(applied.isCompleted)
        assertFalse(container.contactService.hasPendingAdvertKey(KEY_P))
        assertEquals(QueuedAdvertHeardWriterDispatcher.ORIGIN, work.context[CoroutineName]?.name)
        assertTrue(work.frames.any { it.substringBefore('-') == QueuedAdvertHeardWriterDispatcher.HEARD_FRAME })
        assertTrue(work.frames.any(QueuedAdvertHeardWriterDispatcher::isOuterWriter))
        assertEquals(rows.contactSnapshot(before), rows.contactSnapshot(contact(container.radioId)))
        assertCurrent(container, DEVICE_A, manager.connectGeneration)
        return Held(container, collector, requireNotNull(finiteWriter), work, applied, before)
    }

    suspend fun replace(handle: String): ServiceContainer {
        manager.connect(handle)
        barrier()
        val current = requireNotNull(manager.services)
        assertSame(if (handle == DEVICE_A) freshA else freshB, manager.session)
        assertCurrent(current, handle, manager.connectGeneration)
        return current
    }

    suspend fun editCurrent(container: ServiceContainer): ContactEntity {
        val p = contact(container.radioId)
        container.contactService.updateContactPreferences(p.id, nickname = "Fresh local nickname")
        container.contactService.updateContactAvatar(p.id, byteArrayOf(21, 22, 23))
        val edited = contact(container.radioId)
        container.contactStore.saveContact(edited.toDto().copy(lastHeardTimestamp = NOW + FRESH_HEARD_OFFSET))
        val committed = contact(container.radioId)
        assertEquals("Fresh local nickname", committed.nickname)
        assertContentEquals(byteArrayOf(21, 22, 23), committed.avatarImageData)
        assertEquals(NOW + FRESH_HEARD_OFFSET, committed.lastHeardTimestamp)
        return committed
    }

    suspend fun releaseAndJoin(held: Held, live: Boolean) {
        queries.release()
        held.room.join()
        held.work.returned.await()
        assertTrue(held.room.isCompleted && held.work.ran.get())
        if (live) {
            assertTrue(held.collector.isActive)
            assertTrue(held.applied.await() is ContactServiceEvent.ContactAdvertisementApplied)
        } else {
            assertTrue(held.collector.isCancelled)
            held.collector.join()
            assertTrue(held.collector.isCompleted)
        }
        barrier()
        assertTrue(scope.coroutineContext.job.isActive)
    }

    suspend fun assertRetired(held: Held) {
        assertTrue(held.collector.isCancelled && held.room.isActive)
        assertFalse(held.work.ran.get())
        assertTrue(held.container.sendQueue.isRetired && first.closed)
        assertFalse(held.container.isEventMonitoringActive)
        assertNotSame(held.container, manager.services)
        noOutgoing()
    }

    suspend fun assertCurrent(container: ServiceContainer, handle: String, generation: Long) {
        assertSame(container, manager.services)
        assertSame(
            if (container === initial) {
                first
            } else if (handle == DEVICE_A) {
                freshA
            } else {
                freshB
            },
            manager.session,
        )
        assertEquals(ConnectionState.Ready(handle), manager.state.value)
        assertEquals(generation, manager.connectGeneration)
        assertEquals(container.radioId, manager.radioId)
        assertEquals(handle, manager.currentDeviceId)
        assertTrue(manager.wantsConnection && scope.coroutineContext.job.isActive)
        assertTrue(container.isAutoFetching && container.isEventMonitoringActive && !container.sendQueue.isRetired)
        assertFalse(manager.initialSyncInProgress)
        assertNull(manager.resyncJob)
        noOutgoing()
    }

    suspend fun contact(radio: Uuid): ContactEntity = database.contactDao().listByRadio(radio)
        .single { it.publicKey.contentEquals(KEY_P) }

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

    private fun collectorJob(collector: ContactPathEventCollector): Job =
        collector.javaClass.getDeclaredField("job").let {
            it.isAccessible = true
            requireNotNull(it.get(collector) as Job?)
        }

    private fun finiteRoomOperation(work: QueuedAdvertHeardWriterDispatcher.Work, collector: Job): Job {
        val roomScope = database.javaClass.getMethod("getCoroutineScope").invoke(database) as CoroutineScope
        assertNotSame(roomScope.coroutineContext.job, work.context.job)
        assertNotSame(scope.coroutineContext.job, work.context.job)
        assertNotSame(collector, work.context.job)
        return work.context.job
    }

    fun close() {
        queries.release()
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    observers.forEach { it.cancelAndJoin() }
                    collectors.forEach { it.cancelAndJoin() }
                    if (queries.entered.isCompleted) {
                        val work = queries.entered.await()
                        (finiteWriter ?: finiteRoomOperation(work, requireNotNull(original))).join()
                        work.returned.await()
                    }
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
        val container: ServiceContainer,
        val collector: Job,
        val room: Job,
        val work: QueuedAdvertHeardWriterDispatcher.Work,
        val applied: Deferred<ContactServiceEvent>,
        val before: ContactEntity,
    )

    companion object {
        const val DEVICE_A = "AA:BB:CC:DD:EE:A1"
        const val DEVICE_B = "AA:BB:CC:DD:EE:B2"
        const val TIMEOUT_MS = 10_000L
        const val NOW = 1_700_000_000L
        private const val OLD_HEARD_OFFSET = 60L
        private const val FRESH_HEARD_OFFSET = 30L
        val PUBLIC_A = ByteArray(32) { 17 }
        private val PUBLIC_B = ByteArray(32) { 34 }
        private val KEY_P = ConnectionInitialContactReturnRows.KEY_P
        private val oldFrame = ConnectionInitialContactReturnRows.frame(KEY_P, fresh = false)
        private val oldOnly = ConnectionInitialContactReturnRows.frame(
            ConnectionInitialContactReturnRows.KEY_N,
            fresh = false,
        )
        private val newFrame = ConnectionInitialContactReturnRows.frame(KEY_P, fresh = true)
        private val newOnly = ConnectionInitialContactReturnRows.frame(
            ConnectionInitialContactReturnRows.KEY_N,
            fresh = true,
        )
        private val foreign = ConnectionInitialContactReturnRows.frame(
            ConnectionInitialContactReturnRows.KEY_B,
            fresh = true,
        )
    }
}
