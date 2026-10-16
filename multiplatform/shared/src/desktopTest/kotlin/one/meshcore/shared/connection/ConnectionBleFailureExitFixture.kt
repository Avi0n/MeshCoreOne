package one.meshcore.shared.connection

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionBleFailureExitSession.Companion.ORIGIN_NAME
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RoomNodeStore
import one.meshcore.shared.persistence.productionSchemaPolicy
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.remotenode.testUuid
import one.meshcore.shared.transport.BondLostException
import one.meshcore.shared.transport.MeshLink
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

internal class ConnectionBleFailureExitFixture(val attempts: Int, val bond: Boolean) {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val first = ConnectionBleFailureExitSession(PUBLIC_A)
    val states = mutableListOf<ConnectionState>()
    val callers = mutableListOf<Job>()
    val failure: Throwable = if (bond) BondLostException(BOND_MESSAGE) else IllegalStateException(FAIL_MESSAGE)
    val firstLink = FakeMeshLink()
    var bondStart: Deferred<*>? = null
    var bondEntryGeneration: Long? = null
    val bondLink: ConnectionBleFailureExitLink = ConnectionBleFailureExitLink(firstLink) { caller ->
        bondStart = scope.coroutineContext.job.children.filterIsInstance<Deferred<*>>().single { it !== caller }
        bondEntryGeneration = manager.connectGeneration
    }
    var failCallback = true
    val callbackEntered = CompletableDeferred<Job>()
    private var callbackFailureConsumed = false
    private val links = mutableListOf<FakeMeshLink>()
    private val handles = mutableMapOf<MeshLink, String>()
    private var firstCreated = false
    private val backingPrefs = InMemoryPreferencesStore()
    val last = LastConnectionStore(backingPrefs)
    val intent = ConnectionIntentStore(backingPrefs)
    private val file = File.createTempFile("ble-failure-exit", ".db").also { it.delete() }
    val database = Room.databaseBuilder<MeshDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .productionSchemaPolicy()
        .build()
    private val rows = ConnectionBleFailureExitRows(database)
    val manager: ConnectionManager = ConnectionManager(
        database = database,
        scope = scope,
        linkFactory = { handle ->
            val fake = if (links.isEmpty()) firstLink else FakeMeshLink()
            val installed = if (bond && links.isEmpty()) {
                fake.failConnectWith = failure
                bondLink
            } else {
                fake
            }
            links += fake
            handles[installed] = handle
            installed
        },
        sessionFactory = { link ->
            if (!firstCreated) {
                firstCreated = true
                first
            } else {
                FakeMeshSession(selfInfo = defaultSelfInfo(if (handles[link] == DEVICE_A) PUBLIC_A else PUBLIC_B))
            }
        },
        nowEpochSeconds = { NOW },
        ports = ConnectionManagerPorts(
            connectRetryPolicy = ConnectRetryPolicy(
                maxAttempts = attempts,
                unverifiedAttempts = attempts,
                baseBackoff = Duration.ZERO,
            ),
            hasSystemPairingRegistry = { false },
            lastConnectionStore = last,
            intentStore = intent,
            onConversationsChanged = {
                if (!bond && failCallback && !callbackFailureConsumed) {
                    callbackFailureConsumed = true
                    callbackEntered.complete(currentCoroutineContext().job)
                    first.armStop()
                    throw failure
                }
            },
        ),
    )

    suspend fun start(): HeldConnect {
        callers += scope.launch(start = CoroutineStart.UNDISPATCHED) { manager.state.collect { states += it } }
        val caller = scope.async(CoroutineName(ORIGIN_NAME)) {
            runCatching { manager.connect(DEVICE_A) }
        }.also { callers += it }
        val operation = first.contactEntered.await()
        assertActiveOwner(caller, operation)
        barrier()
        assertEquals(ConnectionState.Syncing(DEVICE_A), manager.state.value)
        assertEquals(1, first.getContactsSince.size)
        val held = HeldConnect(
            caller,
            requireNotNull(manager.services),
            first,
            links.single(),
            manager.connectGeneration,
        )
        rows.seed(requireNotNull(manager.services).remoteNodeService.store, requireNotNull(manager.radioId))
        return held
    }

    suspend fun seedBondRows() {
        val store = RoomNodeStore(
            SEED_RADIO,
            database.remoteNodeSessionDao(),
            database.roomMessageDao(),
            database.contactDao(),
            now = { NOW },
        )
        rows.seed(store, SEED_RADIO)
    }

    suspend fun failedCleanup(held: HeldConnect): CoroutineContext {
        first.contactsGate?.complete(Unit)
        assertActiveOwner(held.caller, callbackEntered.await())
        val context = first.stopCompleted.await()
        assertActiveOwner(held.caller, context.job)
        assertEquals(ORIGIN_NAME, context[CoroutineName]?.name)
        assertFalse(held.caller.isCompleted)
        assertTrue(scope.coroutineContext.job.isActive)
        assertEquals(held.generation, manager.connectGeneration)
        assertSame(held.container, manager.services)
        assertSame(held.session, manager.session)
        assertSame(held.link, manager.link)
        assertTrue(held.container.sendQueue.isRetired)
        assertFalse(held.container.isEventMonitoringActive)
        assertEquals(1, first.autoFetchStopCount)
        assertEquals(1, firstLink.connectCount)
        assertEquals(1, first.getContactsSince.size)
        assertEquals(1, first.getChannelsCallCount)
        assertEquals(0, first.getChannelCallCount)
        assertEquals(1, first.getMessageCount)
        assertEquals(0, first.autoFetchStartCount)
        return context
    }

    suspend fun replacement(handle: String = DEVICE_B): HeldConnect {
        val caller = scope.async { runCatching { manager.connect(handle) } }.also { callers += it }
        caller.await().getOrThrow()
        barrier()
        return currentResources(caller)
    }

    fun linkCount() = links.size

    fun currentResources(caller: Deferred<Result<Unit>>): HeldConnect = HeldConnect(
        caller,
        requireNotNull(manager.services),
        requireNotNull(manager.session) as FakeMeshSession,
        requireNotNull(manager.link) as FakeMeshLink,
        manager.connectGeneration,
    )

    fun assertActiveOwner(caller: Job, operation: Job) {
        assertTrue(caller.isActive)
        assertTrue(operation.isActive)
        assertTrue(caller.owns(operation))
    }

    suspend fun assertResources(held: HeldConnect) {
        assertSame(held.container, manager.services)
        assertSame(held.session, manager.session)
        assertSame(held.link, manager.link)
        assertEquals(held.generation, manager.connectGeneration)
        assertEquals(held.container.radioId, manager.radioId)
        assertTrue(manager.wantsConnection)
        assertTrue(scope.coroutineContext.job.isActive)
        assertFalse(held.container.sendQueue.isRetired)
        assertTrue(held.container.isEventMonitoringActive)
        assertTrue(held.container.isAutoFetching)
        assertFalse(held.session.closed)
        assertFalse(held.link.disconnected)
        assertEquals(1, held.session.autoFetchStartCount)
        assertEquals(1, held.session.getTimeCount)
        assertContentEquals(
            held.container.selfPublicKey,
            database.deviceDao().listByRadio(held.container.radioId).single().publicKey,
        )
        assertNoOutgoing()
    }

    suspend fun joinedFailure(caller: Deferred<Result<Unit>>) {
        val caught = caller.await().exceptionOrNull()
        val recovered = caught?.javaClass == failure.javaClass &&
            caught?.cause === failure &&
            caught.stackTrace.any { it.className.startsWith("_COROUTINE.") }
        assertTrue(caught === failure || recovered)
        assertFalse(caller.isCancelled)
        assertTrue(caller.isCompleted)
        assertTrue(scope.coroutineContext.job.isActive)
        barrier()
    }

    fun reachability() = listOf(
        manager.currentDeviceId,
        manager.currentTransportType,
        manager.wifiHost,
        manager.wifiPort,
    )

    suspend fun storedRows() = rows.snapshot()

    suspend fun preferenceSnapshot() = backingPrefs.snapshot()

    suspend fun assertNoOutgoing() {
        assertTrue(first.sentMessages.isEmpty())
        (manager.session as? FakeMeshSession)?.let { assertTrue(it.sentMessages.isEmpty()) }
        assertTrue(links.all { it.sent.isEmpty() })
        assertTrue(database.messageDao().listAll().isEmpty())
        database.deviceDao().listAll().forEach {
            assertTrue(database.pendingSendDao().listByRadio(it.radioID).isEmpty())
        }
        assertTrue(database.pendingSendDao().listByRadio(ConnectionBleFailureExitRows.FOREIGN_RADIO).isEmpty())
        assertTrue(database.pendingSendDao().listByRadio(SEED_RADIO).isEmpty())
    }

    suspend fun barrier() = coroutineScope { async(dispatcher) { Unit }.await() }

    fun close() {
        first.releaseAll()
        bondLink.release.complete(Unit)
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    callers.forEach { it.cancel() }
                    callers.forEach { it.join() }
                    manager.disconnect()
                    assertEquals(ConnectionState.Disconnected, manager.state.value)
                    assertNull(manager.services)
                    assertNull(manager.session)
                    assertNull(manager.link)
                }
            }
        } finally {
            try {
                runBlocking(dispatcher) {
                    try {
                        withTimeout(TIMEOUT_MS) {
                            callers.forEach { it.cancel() }
                            callers.forEach { it.join() }
                        }
                    } finally {
                        withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() }
                    }
                }
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
        val session: FakeMeshSession,
        val link: FakeMeshLink,
        val generation: Long,
    )

    companion object {
        const val DEVICE_B = "AA:BB:CC:DD:EE:91"
        const val TIMEOUT_MS = 10_000L
        private const val FAIL_MESSAGE = "fixture conversation refresh failed"
        const val DEVICE_A = "AA:BB:CC:DD:EE:92"
        private const val BOND_MESSAGE = "fixture genuine link bond failure"
        private val SEED_RADIO = testUuid(87)
        private const val KEY_BYTES = 32
        private val PUBLIC_A = ByteArray(KEY_BYTES) { 17 }
        private val PUBLIC_B = ByteArray(KEY_BYTES) { 34 }
        private const val NOW = 1_700_000_000.0
        private fun Job.owns(other: Job): Boolean = this === other || children.any { it.owns(other) }
    }
}
