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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.meshcore.ContactFetchResult
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.direction
import one.meshcore.shared.persistence.productionSchemaPolicy
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

internal class ConnectionInitialContactReturnFixture {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val first = ConnectionInitialContactReturnSession(PUBLIC_A, listOf(oldFrame), holdReturn = true)
    private val freshA = ConnectionInitialContactReturnSession(PUBLIC_A, listOf(newFrame, newOnly))
    private val freshB = ConnectionInitialContactReturnSession(PUBLIC_B, listOf(foreign))
    private var firstCreated = false
    private val links = mutableListOf<FakeMeshLink>()
    private val handles = mutableMapOf<MeshLink, String>()
    private val callers = mutableListOf<Job>()
    private val prefs = InMemoryPreferencesStore()
    private val file = File.createTempFile("initial-contact-return", ".db").also { it.delete() }
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
        ),
    )

    suspend fun start(): Held {
        val caller = scope.async { runCatching { manager.connect(DEVICE_A) } }.also { callers += it }
        val (operation, fetch) = first.completed.await()
        barrier()
        assertTrue(caller.isActive && operation.isActive && caller.owns(operation))
        assertEquals(listOf<Long?>(null), first.getContactsSince)
        assertEquals(1, fetch.reportedTotal)
        assertEquals(1, fetch.contacts.size)
        assertContentEquals(oldFrame.publicKey, fetch.contacts.single().publicKey)
        assertNotSame(oldFrame.publicKey, fetch.contacts.single().publicKey)
        assertNotSame(oldFrame.outPath, fetch.contacts.single().outPath)
        val container = requireNotNull(manager.services)
        assertSame(first, manager.session)
        assertEquals(ConnectionState.Syncing(DEVICE_A), manager.state.value)
        assertTrue(container.contactService.isSyncingContacts && manager.initialSyncInProgress)
        assertTrue(container.isEventMonitoringActive && !container.sendQueue.isRetired)
        assertFalse(first.closed)
        assertTrue(database.contactDao().listByRadio(container.radioId).isEmpty())
        noOutgoing()
        return Held(
            caller,
            container,
            operation,
            fetch,
            manager.connectGeneration,
            requireNotNull(manager.link) as FakeMeshLink,
        )
    }

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

    suspend fun joinOld(held: Held) {
        first.release.complete(Unit)
        val result = held.caller.await()
        val cause = result.exceptionOrNull()
        assertTrue(cause == null || cause is CancellationException, "unexpected old failure: $cause")
        barrier()
        assertTrue(held.caller.isCompleted && held.operation.isCompleted)
        assertFalse(held.container.contactService.isSyncingContacts)
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
        val fetch: ContactFetchResult,
        val generation: Long,
        val link: FakeMeshLink,
    )

    companion object {
        const val DEVICE_A = "AA:BB:CC:DD:EE:A1"
        const val DEVICE_B = "AA:BB:CC:DD:EE:B2"
        const val TIMEOUT_MS = 10_000L
        val PUBLIC_A = ByteArray(32) { 17 }
        private val PUBLIC_B = ByteArray(32) { 34 }
        private const val NOW = 1_700_000_000.0
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
