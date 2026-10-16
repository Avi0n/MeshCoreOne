package one.meshcore.shared.connection

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CompletableDeferred
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
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.productionSchemaPolicy
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.transport.MeshLink
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class ConnectionSyncSuppressionFixture(val resync: Boolean = false) {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val notifier = ConnectionSyncSuppressionNotifier.create(scope) { NOW }
    var loop: Job? = null
        private set
    val loopCompletion = CompletableDeferred<Throwable?>()
    val first = ConnectionSyncSuppressionSession(PUBLIC_A, holdBacklog = true, resync = resync)
    val replacementA = ConnectionSyncSuppressionSession(PUBLIC_A, holdBacklog = false)
    val replacementB = ConnectionSyncSuppressionSession(PUBLIC_B, holdBacklog = false)
    private val warm = FakeMeshSession(selfInfo = defaultSelfInfo(PUBLIC_A))
    private var priming = false
    private var firstCreated = false
    private val links = mutableListOf<FakeMeshLink>()
    private val handles = mutableMapOf<MeshLink, String>()
    private val callers = mutableListOf<Job>()
    private val prefs = InMemoryPreferencesStore()
    private val file = File.createTempFile("initial-sync-flag", ".db").also { it.delete() }
    val database = Room.databaseBuilder<MeshDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .productionSchemaPolicy()
        .build()
    private val rows = ConnectionSyncSuppressionRows(database)
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
                priming -> warm
                !firstCreated -> first.also { firstCreated = true }
                handles[link] == DEVICE_A -> replacementA
                else -> replacementB
            }
        },
        nowEpochSeconds = { NOW },
        ports = ConnectionManagerPorts(
            hasSystemPairingRegistry = { false },
            lastConnectionStore = LastConnectionStore(prefs),
            intentStore = ConnectionIntentStore(prefs),
            resyncPolicy = ResyncPolicy(),
            notifier = notifier,
        ),
    )

    suspend fun primeA() {
        priming = true
        manager.connect(DEVICE_A)
        assertEquals(ConnectionState.Ready(DEVICE_A), manager.state.value)
        assertEquals(1, warm.autoFetchStartCount)
        manager.disconnect()
        assertTrue(warm.closed)
        assertNull(manager.services)
        assertFalse(manager.initialSyncInProgress)
        priming = false
    }

    suspend fun start(): HeldConnect {
        val caller = scope.async { runCatching { manager.connect(DEVICE_A) } }.also { callers += it }
        owner(caller, first.contactEntered.await())
        barrier()
        val held = resources(caller)
        assertHeld(held, DEVICE_A)
        assertTrue(manager.initialSyncInProgress)
        rows.seed(held.container.remoteNodeService.store, held.container.radioId)
        return held
    }

    suspend fun backlog(held: HeldConnect) {
        first.contactsGate?.complete(Unit)
        val origin = if (resync) {
            held.caller.await().getOrThrow()
            assertEquals(ConnectionState.Syncing(DEVICE_A), manager.state.value)
            assertFalse(manager.initialSyncInProgress)
            requireNotNull(manager.resyncJob).also {
                loop = it
                callers += it
                it.invokeOnCompletion { cause -> loopCompletion.complete(cause) }
            }
        } else {
            held.caller
        }
        owner(origin, first.backlogCompleted.await())
        barrier()
        assertSame(held.container, manager.services)
        assertEquals(if (resync) RESYNC_CALLS else INITIAL_CALLS, first.getMessageCount)
        assertEquals(if (resync) RESYNC_CALLS else INITIAL_CALLS, first.getContactsSince.size)
        assertEquals(if (resync) RESYNC_CALLS else INITIAL_CALLS, first.getChannelsCallCount)
        assertEquals(NO_CALLS, first.getChannelCallCount)
        if (resync) assertEquals(INITIAL_CALLS, first.autoFetchStopCount)
        assertEquals(if (resync) INITIAL_CALLS else NO_CALLS, first.autoFetchStartCount)
        assertEquals(NO_CALLS, first.getTimeCount)
        assertEquals(!resync, manager.initialSyncInProgress)
        assertTrue(notifier.coordinator.isSuppressingNotifications)
    }

    suspend fun joinOld(held: HeldConnect) {
        first.backlogRelease.complete(Unit)
        if (resync) {
            requireNotNull(loop).join()
            assertNull(loopCompletion.await())
            assertFalse(requireNotNull(loop).isCancelled)
        } else {
            held.caller.await().getOrThrow()
            assertFalse(held.caller.isCancelled)
        }
        barrier()
    }

    suspend fun replace(sameGeneration: Boolean): HeldConnect {
        val session = if (sameGeneration) replacementA else replacementB
        val handle = if (sameGeneration) DEVICE_A else DEVICE_B
        val caller = scope.async {
            runCatching {
                if (sameGeneration) manager.rebuildConnectedBleAppStack(handle) else manager.connect(handle)
            }
        }.also { callers += it }
        owner(caller, session.contactEntered.await())
        barrier()
        return resources(caller).also {
            assertHeld(it, handle)
            assertTrue(manager.initialSyncInProgress)
            assertSame(loop, manager.resyncJob)
        }
    }

    private fun resources(caller: Deferred<Result<Unit>>) = HeldConnect(
        caller,
        requireNotNull(manager.services),
        requireNotNull(manager.session) as ConnectionSyncSuppressionSession,
        requireNotNull(manager.link) as FakeMeshLink,
        manager.connectGeneration,
    )

    suspend fun assertHeld(held: HeldConnect, handle: String) {
        assertSame(held.container, manager.services)
        assertSame(held.session, manager.session)
        assertSame(held.link, manager.link)
        assertEquals(held.generation, manager.connectGeneration)
        assertEquals(held.container.radioId, manager.radioId)
        assertEquals(handle, manager.currentDeviceId)
        assertEquals(TransportType.BLUETOOTH, manager.currentTransportType)
        assertEquals(ConnectionState.Syncing(handle), manager.state.value)
        assertTrue(manager.wantsConnection)
        assertTrue(scope.coroutineContext.job.isActive)
        owner(held.caller, held.session.contactEntered.await())
        assertFalse(held.container.sendQueue.isRetired)
        assertTrue(held.container.isEventMonitoringActive)
        assertFalse(held.container.isAutoFetching)
        assertFalse(held.session.closed)
        assertFalse(held.link.disconnected)
        assertEquals(1, held.session.getContactsSince.size)
        assertEquals(0, held.session.getChannelsCallCount)
        assertEquals(0, held.session.getChannelCallCount)
        assertEquals(0, held.session.getMessageCount)
        assertEquals(0, held.session.autoFetchStartCount)
        assertEquals(0, held.session.getTimeCount)
        assertContentEquals(
            held.container.selfPublicKey,
            database.deviceDao().listByRadio(held.container.radioId).single().publicKey,
        )
        noOutgoing()
    }

    suspend fun finish(held: HeldConnect) {
        held.session.releaseAll()
        if (resync) {
            requireNotNull(loop).join()
            assertNull(loopCompletion.await())
        } else {
            held.caller.await().getOrThrow()
        }
        barrier()
        assertEquals(ConnectionState.Ready(DEVICE_A), manager.state.value)
        assertSame(held.container, manager.services)
        assertEquals(held.generation, manager.connectGeneration)
        assertFalse(manager.initialSyncInProgress)
        if (resync) assertSame(loop, manager.resyncJob) else assertNull(manager.resyncJob)
        assertTrue(held.container.isAutoFetching)
        assertTrue(held.container.isEventMonitoringActive)
        assertFalse(held.container.sendQueue.isRetired)
        assertFalse(held.session.closed)
        assertEquals(if (resync) RESYNC_CALLS else INITIAL_CALLS, held.session.autoFetchStartCount)
        assertEquals(INITIAL_CALLS, held.session.getTimeCount)
        assertFalse(held.container.contactService.isSyncingContacts)
        noOutgoing()
    }

    suspend fun snapshot() = rows.snapshot()

    suspend fun preferenceSnapshot() = prefs.snapshot()

    suspend fun noOutgoing() {
        assertTrue(listOf(first, replacementA, replacementB, warm).all { it.sentMessages.isEmpty() })
        assertTrue(links.all { it.sent.isEmpty() })
        assertTrue(database.messageDao().listAll().isEmpty())
        database.deviceDao().listAll().forEach {
            assertTrue(database.pendingSendDao().listByRadio(it.radioID).isEmpty())
        }
        assertTrue(database.pendingSendDao().listByRadio(ConnectionSyncSuppressionRows.FOREIGN_RADIO).isEmpty())
    }

    suspend fun barrier() = coroutineScope { async(dispatcher) { Unit }.await() }

    private fun owner(caller: Job, operation: Job) {
        assertTrue(caller.isActive)
        assertTrue(operation.isActive)
        assertTrue(caller.owns(operation))
    }

    fun close() {
        listOf(first, replacementA, replacementB).forEach { it.releaseAll() }
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
                    try {
                        dispatcher.close()
                    } finally {
                        val owned = listOf("", "-wal", "-shm").map { File(file.absolutePath + it) }
                        owned.forEach { it.delete() }
                        assertTrue(owned.none { it.exists() })
                    }
                }
            }
        }
    }

    data class HeldConnect(
        val caller: Deferred<Result<Unit>>,
        val container: ServiceContainer,
        val session: ConnectionSyncSuppressionSession,
        val link: FakeMeshLink,
        val generation: Long,
    )

    companion object {
        const val DEVICE_A = "AA:BB:CC:DD:EE:A1"
        const val DEVICE_B = "AA:BB:CC:DD:EE:B2"
        const val TIMEOUT_MS = 10_000L
        private const val INITIAL_CALLS = 1
        private const val RESYNC_CALLS = 2
        private const val NO_CALLS = 0
        private const val KEY_BYTES = 32
        private val PUBLIC_A = ByteArray(KEY_BYTES) { 17 }
        private val PUBLIC_B = ByteArray(KEY_BYTES) { 34 }
        private const val NOW = 1_700_000_000.0
        private fun Job.owns(other: Job): Boolean = this === other || children.any { it.owns(other) }
    }
}
