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
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.productionSchemaPolicy
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.transport.MeshLink
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class ConnectionChannelFrameFixture {
    private val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val prefs = InMemoryPreferencesStore()
    val first = ConnectionChannelFrameSession(KEY_A, listOf(oldTarget, contrast), holdReturn = true)
    private val other = ConnectionChannelFrameSession(KEY_B, listOf(foreign))
    private val fresh = ConnectionChannelFrameSession(KEY_A, listOf(newTarget, contrast))
    private val links = mutableListOf<FakeMeshLink>()
    private val handles = mutableMapOf<MeshLink, String>()
    private var oldCreated = false
    private val callers = mutableListOf<Job>()
    private val file = File.createTempFile("channel-frame-return", ".db").also { it.delete() }
    val database = Room.databaseBuilder<MeshDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver()).productionSchemaPolicy().build()
    val rows = ConnectionChannelFrameRows(database)
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
                !oldCreated -> first.also { oldCreated = true }
                handles[link] == A -> fresh
                else -> other
            }
        },
        nowEpochSeconds = { ConnectionChannelFrameRows.NOW },
        ports = ConnectionManagerPorts(
            hasSystemPairingRegistry = { false },
            lastConnectionStore = LastConnectionStore(prefs),
            intentStore = ConnectionIntentStore(prefs),
        ),
    )
    lateinit var original: Deferred<Result<Unit>>
        private set
    lateinit var oldContainer: ServiceContainer
        private set
    private var oldRadioValue: Uuid? = null
    val oldRadio get() = requireNotNull(oldRadioValue)
    private var foreignRadioValue: Uuid? = null
    val foreignRadio get() = requireNotNull(foreignRadioValue)

    suspend fun start() {
        original = scope.async { runCatching { manager.connect(A) } }.also { callers += it }
        assertTrue(original.owns(first.completed.await()))
        oldContainer = requireNotNull(manager.services)
        oldRadioValue = oldContainer.radioId
        assertEquals(ConnectionState.Syncing(A), manager.state.value)
        assertTrue(original.isActive)
        assertFalse(first.closed)
        assertEquals(listOf((0 until ConnectionChannelFrameSession.SLOT_COUNT).map(Int::toByte)), first.requests)
        assertEquals(1, first.getChannelsCallCount)
        assertEquals(0, first.getChannelCallCount)
        assertEquals(0, first.getMessageCount)
        assertNull(oldContainer.channelService.getChannel(oldRadio, ConnectionChannelFrameRows.TARGET))
        noSends()
    }

    suspend fun replace(): Current {
        manager.disconnect()
        await { first.eventSubscribers.value == 0 }
        assertTrue(first.closed)
        assertEquals(1, first.closeCount)
        assertTrue(links.first().disconnected)
        if (!original.isActive) {
            assertTrue(first.completed.await().isCancelled && !first.forwarded)
            assertTrue(original.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        }
        manager.connect(B)
        assertEquals(ConnectionState.Ready(B), manager.state.value)
        foreignRadioValue = requireNotNull(manager.services).radioId
        assertTrue(foreignRadio != oldRadio)
        manager.connect(A, forceFullSync = true)
        assertEquals(ConnectionState.Ready(A), manager.state.value)
        val container = requireNotNull(manager.services)
        assertNotSame(oldContainer, container)
        assertEquals(oldRadio, container.radioId)
        assertTrue(other.closed)
        assertEquals(1, fresh.getChannelsCallCount)
        assertEquals(1, fresh.getMessageCount)
        assertEquals(1, fresh.autoFetchStartCount)
        assertTrue(database.deviceDao().listAll().single { it.radioID == oldRadio }.publicKey.contentEquals(KEY_A))
        rows.seed(oldRadio, newTarget)
        rows.seed(oldRadio, contrast)
        rows.seed(foreignRadio, foreign)
        return Current(container, manager.connectGeneration, requireNotNull(manager.link))
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
        return result
    }

    suspend fun assertCurrent(current: Current) {
        assertSame(current.container, manager.services)
        assertSame(fresh, manager.session)
        assertSame(current.link, manager.link)
        assertEquals(oldRadio, manager.radioId)
        assertEquals(current.generation, manager.connectGeneration)
        assertEquals(ConnectionState.Ready(A), manager.state.value)
        assertEquals(A, manager.currentDeviceId)
        assertEquals(TransportType.BLUETOOTH, manager.currentTransportType)
        assertTrue(manager.wantsConnection)
        assertTrue(first.closedBacklogAdmissions in 0..1)
        if (first.closedBacklogAdmissions == 1) assertTrue(first.forwarded)
        if (!first.forwarded) assertEquals(0, first.closedBacklogAdmissions)
        assertEquals(0, first.getMessageCount)
        assertEquals(0, first.getChannelCallCount)
        assertEquals(0, first.autoFetchStartCount)
        rows.assertPendingEmpty()
        noSends()
    }

    suspend fun preferences() = prefs.snapshot()
    private fun noSends() {
        assertTrue(listOf(first, other, fresh).all { it.sentMessages.isEmpty() })
        assertTrue(links.all { it.sent.isEmpty() })
    }
    private suspend fun await(condition: () -> Boolean) {
        withTimeout(TIMEOUT_MS) { while (!condition()) delay(1) }
    }

    fun close() {
        first.release.complete(Unit)
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
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

    data class Current(val container: ServiceContainer, val generation: Long, val link: MeshLink)
    private fun Job.owns(other: Job): Boolean = this === other || children.any { it.owns(other) }

    companion object {
        const val A = "channel-frame-A"
        const val B = "channel-frame-B"
        const val TIMEOUT_MS = 10_000L
        private val KEY_A = ByteArray(32) { 33 }
        private val KEY_B = ByteArray(32) { 68 }
        val oldTarget = ChannelInfo(1, "Old snapshot", ByteArray(16) { 81 })
        val newTarget = ChannelInfo(1, "New snapshot", ByteArray(16) { 98 })
        val contrast = ChannelInfo(2, "Stable contrast", ByteArray(16) { 117 })
        val foreign = ChannelInfo(1, "Foreign slot", ByteArray(16) { 65 })
    }
}
