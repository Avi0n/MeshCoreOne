package one.meshcore.shared.connection

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
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
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.remotenode.login
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.remotenode.testUuid
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ConnectionRemoteActiveReauthFixture {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val credentials = CredentialReturnGate()
    val sessions = mutableListOf<FakeMeshSession>()
    var now = OLD_TIME
    private val dbFile = File.createTempFile("active-reauth", ".db").also { it.delete() }
    private val db = Room.databaseBuilder<MeshDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .productionSchemaPolicy()
        .build()
    val manager = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = { FakeMeshSession().also { sessions += it } },
        nowEpochSeconds = { now },
        ports = ConnectionManagerPorts(nodePasswordStore = credentials),
    )

    suspend fun connectAndCapture(): RemoteNodeSessionDto {
        manager.connect(DEVICE)
        val service = requireNotNull(manager.services).remoteNodeService
        val seed = testSession(
            radioID = requireNotNull(manager.services).radioId,
            permissionLevel = RoomPermissionLevel.READ_WRITE,
            lastSyncTimestamp = SYNC_TIME,
        ).copy(
            latitude = LATITUDE,
            longitude = LONGITUDE,
            unreadCount = UNREAD,
            notificationLevel = NotificationLevel.MENTIONS_ONLY,
            isPinned = true,
            neighborCount = NEIGHBORS,
            lastBatteryMillivolts = BATTERY,
            lastUptimeSeconds = UPTIME,
            lastNoiseFloor = NOISE_FLOOR,
            lastRxAirtimeSeconds = RX_AIRTIME,
            lastMessageDate = OLD_TIME,
        )
        service.store.saveSession(seed)
        service.store.saveSession(
            testSession(id = FOREIGN_ID, radioID = testUuid(FOREIGN_RADIO), publicKey = testPublicKey(FOREIGN_KEY)),
        )
        assertEquals(RoomPermissionLevel.ADMIN, service.login(seed.id, TEST_PASSWORD).permissionLevel)
        assertTrue(row(seed.id).isConnected)
        assertEquals(OLD_TIME, row(seed.id).lastConnectedDate)
        assertFalse(credentials.hasPassword(seed.publicKey))
        val captured = manager.captureRemoteSessionsForReauth()
        assertEquals(setOf(seed.id), captured)
        manager.pendingReauthSessionIds = captured
        assertFalse(row(seed.id).isConnected)
        return seed
    }

    suspend fun row(id: Uuid): RemoteNodeSessionEntity = requireNotNull(db.remoteNodeSessionDao().getById(id))

    suspend fun assertNoMessagesOrPending(radioID: Uuid) {
        assertTrue(db.messageDao().listAll().isEmpty())
        assertTrue(db.pendingSendDao().listByRadio(radioID).isEmpty())
        assertTrue(db.pendingSendDao().listByRadio(testUuid(FOREIGN_RADIO)).isEmpty())
    }

    fun assertOwner(caller: Job, operation: Job) {
        assertTrue(caller.isActive)
        assertTrue(operation.isActive)
        assertTrue(scope.coroutineContext.job.isActive)
        assertTrue(operation === caller || caller.hasDescendant(operation))
    }

    fun close() {
        credentials.release.complete(Unit)
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    credentials.owner?.cancelAndJoin()
                    manager.disconnect()
                    assertFalse(manager.wantsConnection)
                }
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

    class CredentialReturnGate(private val backing: NodePasswordStore = InMemoryNodePasswordStore()) :
        NodePasswordStore by backing {
        val lookupCompleted = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        var owner: Job? = null
            private set
        private var key: ByteArray? = null
        private var armed = false

        fun arm(caller: Job, publicKey: ByteArray) {
            owner = caller
            key = publicKey.copyOf()
            armed = true
        }

        override suspend fun retrievePassword(publicKey: ByteArray): String? {
            val result = backing.retrievePassword(publicKey)
            val operation = currentCoroutineContext().job
            val caller = owner
            if (
                armed &&
                key?.contentEquals(publicKey) == true &&
                caller != null &&
                (operation === caller || caller.hasDescendant(operation))
            ) {
                armed = false
                check(result == null)
                lookupCompleted.complete(operation)
                release.await()
            }
            return result
        }
    }

    companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:77"
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

        fun assertWholeRow(expected: RemoteNodeSessionEntity, actual: RemoteNodeSessionEntity) {
            assertContentEquals(expected.publicKey, actual.publicKey)
            assertEquals(expected.copy(publicKey = actual.publicKey), actual)
        }
    }
}

private fun Job.hasDescendant(operation: Job): Boolean = children.any {
    it === operation || it.hasDescendant(operation)
}
