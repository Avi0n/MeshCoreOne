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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RemoteNodeSessionEntity
import one.meshcore.shared.persistence.productionSchemaPolicy
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.remotenode.testUuid
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ConnectionActiveLogoutFixture {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val oldSession = CompletedLogoutSession()
    val sessions = mutableListOf<FakeMeshSession>()
    var now = OLD_TIME
    private val queries = WriterObserver()
    private val dbFile = File.createTempFile("active-logout", ".db").also { it.delete() }
    private val db = Room.databaseBuilder<MeshDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queries)
        .productionSchemaPolicy()
        .build()
    val manager = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = {
            (if (sessions.isEmpty()) oldSession else FakeMeshSession()).also { sessions += it }
        },
        nowEpochSeconds = { now },
    )

    suspend fun connectAndSeed(): RemoteNodeSessionDto {
        manager.connect(DEVICE)
        val service = requireNotNull(manager.services).remoteNodeService
        val seed = testSession(
            radioID = requireNotNull(manager.services).radioId,
            isConnected = true,
            permissionLevel = RoomPermissionLevel.READ_WRITE,
            lastSyncTimestamp = SYNC_TIME,
        ).copy(
            latitude = LATITUDE,
            longitude = LONGITUDE,
            unreadCount = UNREAD,
            notificationLevel = NotificationLevel.MENTIONS_ONLY,
            isPinned = true,
            neighborCount = NEIGHBORS,
            lastConnectedDate = OLD_TIME,
            lastBatteryMillivolts = BATTERY,
            lastUptimeSeconds = UPTIME,
            lastNoiseFloor = NOISE_FLOOR,
            lastRxAirtimeSeconds = RX_AIRTIME,
            lastMessageDate = MESSAGE_TIME,
        )
        service.store.saveSession(seed)
        service.store.saveSession(
            testSession(id = FOREIGN_ID, radioID = testUuid(FOREIGN_RADIO), publicKey = testPublicKey(FOREIGN_KEY)),
        )
        return seed
    }

    suspend fun row(id: Uuid): RemoteNodeSessionEntity = requireNotNull(db.remoteNodeSessionDao().getById(id))

    fun assertLogoutOwner(caller: Job, operation: Job) {
        assertTrue(caller.isActive)
        assertTrue(operation.isActive)
        assertTrue(scope.coroutineContext.job.isActive)
        assertTrue(operation === caller || caller.hasDescendant(operation))
    }

    suspend fun joinLogoutWriters() {
        queries.writers.toList().forEach { writer ->
            writer.join()
            assertTrue(writer.isCompleted)
        }
    }

    fun close() {
        oldSession.release.complete(Unit)
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) { manager.disconnect() }
            }
        } finally {
            try {
                scope.cancelAndJoinBlocking()
            } finally {
                try {
                    db.close()
                } finally {
                    dispatcher.close()
                    listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm")).forEach { file ->
                        file.delete()
                        check(!file.exists())
                    }
                }
            }
        }
    }

    class CompletedLogoutSession : FakeMeshSession() {
        val sendCompleted = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()

        override suspend fun sendLogout(destination: ByteArray) {
            super.sendLogout(destination)
            sendCompleted.complete(currentCoroutineContext().job)
            release.await()
        }
    }

    private class WriterObserver : CoroutineDispatcher() {
        val writers = ConcurrentHashMap.newKeySet<Job>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (
                context[CoroutineName]?.name == LOGOUT_NAME &&
                Thread.currentThread().stackTrace.any {
                    it.className.startsWith("androidx.room.util.DBUtil") &&
                        it.methodName == "performInTransactionSuspending"
                }
            ) {
                writers += context.job
            }
            Dispatchers.IO.dispatch(context, block)
        }
    }

    private fun Job.hasDescendant(operation: Job): Boolean = children.any {
        it === operation || it.hasDescendant(operation)
    }

    companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:76"
        const val LOGOUT_NAME = "active-old-logout"
        const val TIMEOUT_MS = 10_000L
        const val OLD_TIME = 1_700_000_000.0
        const val NEW_TIME = OLD_TIME + 60.0
        const val TEST_PASSWORD = "test-only-password"
        val FOREIGN_ID = testUuid(2)
        private const val FOREIGN_RADIO = 98
        private const val FOREIGN_KEY = 67
        private const val SYNC_TIME = 123L
        private const val LATITUDE = 51.5
        private const val LONGITUDE = -0.1
        private const val UNREAD = 7
        private const val NEIGHBORS = 4
        private const val BATTERY = 3800
        private const val UPTIME = 1000L
        private const val NOISE_FLOOR = -120
        private const val RX_AIRTIME = 8L
        private const val MESSAGE_TIME = OLD_TIME - 10.0

        fun assertWholeRow(expected: RemoteNodeSessionEntity, actual: RemoteNodeSessionEntity) {
            assertContentEquals(expected.publicKey, actual.publicKey)
            assertEquals(expected.copy(publicKey = actual.publicKey), actual)
        }
    }
}
