package one.meshcore.shared.connection

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.app.AppStateRuntime
import one.meshcore.shared.app.AppStateStores
import one.meshcore.shared.app.AppStateViewModel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.productionSchemaPolicy
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

internal class ConnectionSyncActivityFixture {
    private val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val prefs = InMemoryPreferencesStore()
    private val last = LastConnectionStore(prefs)
    private val intent = ConnectionIntentStore(prefs)
    val ui = ConnectionUiState(scope)
    val first = ConnectionSyncActivitySession(ByteArray(32) { 31 }, holdBatch = true)
    val second = ConnectionSyncActivitySession(ByteArray(32) { 63 }, holdBatch = false)
    val links = mutableListOf<FakeMeshLink>()
    private val file = File.createTempFile("sync-activity-owner", ".db").also { it.delete() }
    val database = Room.databaseBuilder<MeshDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver()).productionSchemaPolicy().build()
    val manager = ConnectionManager(
        database = database,
        scope = scope,
        linkFactory = { FakeMeshLink().also { links += it } },
        sessionFactory = { if (links.size == 1) first else second },
        nowEpochSeconds = { NOW },
        ports = ConnectionManagerPorts(
            lastConnectionStore = last,
            intentStore = intent,
            hasSystemPairingRegistry = { false },
            connectionUi = ui,
            channelRetryPolicy = ChannelRetryPolicy(initialDelay = RETRY_DELAY_MS.milliseconds),
        ),
    )
    val app = AppStateViewModel(
        connectionManager = manager,
        stores = AppStateStores(last, intent, prefs, database),
        scope = scope,
        runtime = AppStateRuntime(ui, NavigationCoordinator(scope)),
    )
    private val history = ConnectionInitialContactReturnRows(database)
    private val rows = ConnectionInitialSyncFlagRows(database)
    private val callers = mutableListOf<Job>()
    lateinit var original: Deferred<Result<Unit>>
        private set
    lateinit var oldContainer: ServiceContainer
        private set
    private var oldRadioValue: Uuid? = null
    val oldRadio: Uuid get() = requireNotNull(oldRadioValue)

    suspend fun start() {
        await { app.connectionUiServicesSeen && app.connectionUiServices == null }
        original = scope.async { runCatching { manager.connect(A) } }.also { callers += it }
        assertTrue(original.owns(first.completed.await()))
        oldContainer = requireNotNull(manager.services)
        oldRadioValue = oldContainer.radioId
        await { app.connectionUiServices === oldContainer && ui.syncActivityCount.value == 1 }
        assertEquals(ConnectionState.Syncing(A), manager.state.value)
        history.enrich(oldRadio, ByteArray(32) { 31 })
        assertTrue(original.isActive)
        assertFalse(first.closed)
        assertEquals(1, first.getChannelsCallCount)
        assertEquals(0, first.getChannelCallCount)
        assertEquals(0, first.getMessageCount)
        assertNoSends()
    }

    suspend fun replaceAndHoldCurrentRetry(): Current {
        manager.disconnect()
        await {
            app.connectionUiServices == null && ui.syncActivityCount.value == 0 && first.eventSubscribers.value == 0
        }
        assertTrue(first.closed)
        assertEquals(1, first.closeCount)
        assertTrue(links.first().disconnected)
        if (!original.isActive) {
            assertTrue(first.completed.await().isCancelled && !first.forwarded)
            assertTrue(original.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        }
        assertNull(manager.services)
        manager.connect(B)
        val container = requireNotNull(manager.services)
        assertTrue(container.radioId != oldRadio)
        assertTrue(
            database.deviceDao().listAll().single {
                it.radioID == container.radioId
            }.publicKey.contentEquals(ByteArray(32) { 63 }),
        )
        val operation = second.completed.await()
        val worker = requireNotNull(manager.channelRetryJob)
        assertTrue(worker.owns(operation))
        assertTrue(worker.isActive && operation.isActive)
        assertEquals(ConnectionState.Ready(B), manager.state.value)
        await {
            app.connectionUiServices === container &&
                app.connectionState.value == DeviceConnectionState.READY &&
                app.statusPill.value == StatusPillState.Syncing
        }
        assertEquals(1, ui.syncActivityCount.value)
        assertEquals(1, second.getChannelsCallCount)
        assertEquals(3, second.getChannelCallCount)
        assertEquals(1, second.getMessageCount)
        assertEquals(1, second.autoFetchStartCount)
        assertNull(container.channelService.getChannel(container.radioId, ConnectionSyncActivitySession.SLOT))
        assertFalse(second.forwarded)
        history.enrich(container.radioId, ByteArray(32) { 63 })
        return Current(container, container.radioId, manager.connectGeneration, worker, operation)
    }

    suspend fun releaseOriginal(): Result<Unit> {
        first.release.complete(Unit)
        val result = original.await()
        result.exceptionOrNull()?.let { assertTrue(it is kotlinx.coroutines.CancellationException) }
        original.join()
        first.completed.await().join()
        if (!first.forwarded) {
            assertTrue(first.completed.await().isCancelled)
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        }
        assertTrue(scope.coroutineContext.job.isActive)
        settleProjection()
        return result
    }

    suspend fun assertCurrent(current: Current) {
        assertSame(current.container, manager.services)
        assertSame(second, manager.session)
        assertSame(links.last(), manager.link)
        assertEquals(current.radio, manager.radioId)
        assertEquals(current.generation, manager.connectGeneration)
        assertEquals(B, manager.currentDeviceId)
        assertEquals(ConnectionState.Ready(B), manager.state.value)
        assertEquals(DeviceConnectionState.READY, app.connectionState.value)
        assertEquals(TransportType.BLUETOOTH, manager.currentTransportType)
        assertTrue(manager.wantsConnection)
        assertSame(current.worker, manager.channelRetryJob)
        assertTrue(current.worker.isActive && current.operation.isActive)
        assertFalse(second.forwarded)
        assertNull(current.container.channelService.getChannel(current.radio, ConnectionSyncActivitySession.SLOT))
        assertEquals(3, second.getChannelCallCount)
        assertTrue(first.closedBacklogAdmissions in 0..1)
        if (first.closedBacklogAdmissions == 1) assertTrue(first.forwarded)
        if (!first.forwarded) assertEquals(0, first.closedBacklogAdmissions)
        assertEquals(0, first.getMessageCount)
        assertEquals(0, first.autoFetchStartCount)
        assertNoSends()
    }

    suspend fun snapshot() = listOf(
        rows.snapshot(),
        history.snapshot(oldRadio),
        manager.radioId?.takeIf {
            it !=
                oldRadio
        }?.let { history.snapshot(it) },
    )
    suspend fun preferences() = prefs.snapshot()

    suspend fun settleProjection() {
        await {
            app.statusPill.value == statusPillState(
                StatusPillInputs(
                    ui.syncFailedPillVisible.value,
                    ui.syncActivityCount.value,
                    app.connectionState.value,
                    ui.showReadyToast.value,
                    ui.disconnectedPillVisible.value,
                ),
            )
        }
    }

    suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(TIMEOUT_MS) { while (!condition()) delay(POLL_MS) }
    }

    private fun assertNoSends() {
        assertTrue(first.sentMessages.isEmpty() && second.sentMessages.isEmpty())
        assertTrue(links.all { it.sent.isEmpty() })
    }

    fun close() {
        first.release.complete(Unit)
        second.release.complete(Unit)
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    callers.forEach { it.join() }
                    manager.channelRetryJob?.join()
                    manager.disconnect()
                    assertNull(manager.services)
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
                        check(executor.awaitTermination(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    } finally {
                        val owned = listOf("", "-wal", "-shm").map { File(file.absolutePath + it) }
                        owned.forEach { it.delete() }
                        check(owned.none { it.exists() })
                    }
                }
            }
        }
    }

    data class Current(
        val container: ServiceContainer,
        val radio: Uuid,
        val generation: Long,
        val worker: Job,
        val operation: Job,
    )

    private fun Job.owns(other: Job): Boolean = this === other || children.any { it.owns(other) }

    companion object {
        const val A = "sync-activity-A"
        const val B = "sync-activity-B"
        const val NOW = 1_700_000_000.0
        const val TIMEOUT_MS = 10_000L
        private const val POLL_MS = 1L
        private const val RETRY_DELAY_MS = 200L
    }
}
