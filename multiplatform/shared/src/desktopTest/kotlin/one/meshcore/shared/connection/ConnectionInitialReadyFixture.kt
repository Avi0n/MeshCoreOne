package one.meshcore.shared.connection

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.productionSchemaPolicy
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.remotenode.testUuid
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ConnectionInitialReadyFixture {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val session = CompletedAutoFetchSession()
    val link = FakeMeshLink()
    val queries = CaptureQueryGate()
    private val file = File.createTempFile("initial-ready", ".db").also { it.delete() }
    val database = Room.databaseBuilder<MeshDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queries)
        .productionSchemaPolicy()
        .build()
    val manager = ConnectionManager(
        database = database,
        scope = scope,
        linkFactory = { link },
        sessionFactory = { session },
        nowEpochSeconds = { NOW },
    )
    val callers = mutableListOf<Job>()

    suspend fun seed() {
        val service = requireNotNull(manager.services).remoteNodeService
        service.store.saveSession(
            testSession(
                radioID = requireNotNull(manager.radioId),
                permissionLevel = RoomPermissionLevel.READ_WRITE,
                lastSyncTimestamp = SYNC_TIME,
            ).copy(
                latitude = LATITUDE,
                longitude = LONGITUDE,
                unreadCount = UNREAD,
                notificationLevel = NotificationLevel.MENTIONS_ONLY,
                isPinned = true,
                neighborCount = NEIGHBORS,
                lastConnectedDate = NOW,
                lastBatteryMillivolts = BATTERY,
                lastUptimeSeconds = UPTIME,
                lastNoiseFloor = NOISE_FLOOR,
                lastRxAirtimeSeconds = RX_AIRTIME,
                lastMessageDate = NOW,
            ),
        )
        service.store.saveSession(
            testSession(id = FOREIGN_ID, radioID = FOREIGN_RADIO, publicKey = testPublicKey(FOREIGN_KEY)),
        )
    }

    suspend fun rows() = database.remoteNodeSessionDao().getAll().sortedBy { it.id.value }.map {
        it.copy(publicKey = EMPTY_KEY) to it.publicKey.toList()
    }

    suspend fun devices() = database.deviceDao().listAll().map {
        it.copy(publicKey = EMPTY_KEY) to it.publicKey.toList()
    }

    suspend fun assertNoOutgoing() {
        assertTrue(session.sentMessages.isEmpty())
        assertTrue(link.sent.isEmpty())
        assertTrue(database.messageDao().listAll().isEmpty())
        assertTrue(database.pendingSendDao().listByRadio(requireNotNull(manager.radioId)).isEmpty())
        assertTrue(database.pendingSendDao().listByRadio(FOREIGN_RADIO).isEmpty())
    }

    suspend fun barrier() = coroutineScope { async(dispatcher) { Unit }.await() }

    fun assertActiveOwner(caller: Job, operation: Job) {
        assertTrue(caller.isActive)
        assertTrue(operation.isActive)
        assertTrue(scope.coroutineContext.job.isActive)
        assertTrue(caller.owns(operation))
    }

    suspend fun assertDisconnected() {
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertFalse(manager.wantsConnection)
        assertNull(manager.services)
        assertNull(manager.session)
        assertNull(manager.link)
        assertTrue(session.closed)
        assertTrue(link.disconnected)
    }

    fun close() {
        session.release.complete(Unit)
        queries.release()
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    queries.work?.returned?.await()
                    callers.forEach { it.cancel() }
                    callers.forEach { it.join() }
                    manager.disconnect()
                    assertDisconnected()
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

    class CompletedAutoFetchSession : FakeMeshSession() {
        val completed = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()

        override suspend fun startAutoMessageFetching() {
            super.startAutoMessageFetching()
            completed.complete(currentCoroutineContext().job)
            release.await()
        }
    }

    class CaptureQueryGate : CoroutineDispatcher() {
        val armed = AtomicBoolean(false)
        val entered = CompletableDeferred<QueuedWork>()
        private val held = AtomicReference<QueuedWork?>()
        var work: QueuedWork? = null
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val frames = Thread.currentThread().stackTrace.toList()
            if (
                context[CoroutineName]?.name == DISCONNECT_NAME &&
                frames.any { it.className.endsWith("RemoteNodeSessionDao_Impl") && it.methodName == "getAll" } &&
                frames.any {
                    it.className.startsWith("androidx.room.util.DBUtil") && it.methodName == "performSuspending"
                } &&
                armed.compareAndSet(true, false)
            ) {
                val selected = QueuedWork(context, block, frames)
                check(held.compareAndSet(null, selected))
                work = selected
                entered.complete(selected)
            } else {
                Dispatchers.IO.dispatch(context, block)
            }
        }

        fun release() {
            held.getAndSet(null)?.let { selected ->
                Dispatchers.IO.dispatch(selected.context) {
                    selected.executed.set(true)
                    try {
                        selected.block.run()
                    } finally {
                        selected.returned.complete(Unit)
                    }
                }
            }
        }
    }

    data class QueuedWork(val context: CoroutineContext, val block: Runnable, val frames: List<StackTraceElement>) {
        val executed = AtomicBoolean(false)
        val returned = CompletableDeferred<Unit>()
    }

    companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:87"
        const val DISCONNECT_NAME = "initial-ready-explicit-disconnect"
        const val TIMEOUT_MS = 10_000L
        private val EMPTY_KEY = ByteArray(0)
        private const val NOW = 1_700_000_000.0
        private const val FOREIGN_KEY = 67
        private val FOREIGN_ID = testUuid(2)
        private val FOREIGN_RADIO = testUuid(98)
        private const val SYNC_TIME = 123L
        private const val LATITUDE = 51.5
        private const val LONGITUDE = -0.1
        private const val UNREAD = 7
        private const val NEIGHBORS = 4
        private const val BATTERY = 3800
        private const val UPTIME = 1000L
        private const val NOISE_FLOOR = -120
        private const val RX_AIRTIME = 8L

        private fun Job.owns(other: Job): Boolean = this === other || children.any { it.owns(other) }
    }
}
