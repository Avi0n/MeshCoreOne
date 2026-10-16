package one.meshcore.shared.connection

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.productionSchemaPolicy
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.remotenode.testUuid
import one.meshcore.shared.tools.RxLogDecodeCache
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class ConnectionStartupDecodeFixture {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val cache = RxLogDecodeCache()
    val first = ExportSession(FIRST_PUBLIC_KEY, FIRST_SCALAR)
    val second = ExportSession(SECOND_PUBLIC_KEY, SECOND_SCALAR)
    val links = mutableListOf<FakeMeshLink>()
    val callers = mutableListOf<Job>()
    private var sessionsBuilt = 0
    private val lockOwner = Any()
    private val cacheMutex = RxLogDecodeCache::class.java.getDeclaredField("mutex").let {
        it.isAccessible = true
        it.get(cache) as Mutex
    }
    private var ownsCacheLock = false
    private val file = File.createTempFile("startup-decode", ".db").also { it.delete() }
    val database = Room.databaseBuilder<MeshDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .productionSchemaPolicy()
        .build()
    val manager = ConnectionManager(
        database = database,
        scope = scope,
        linkFactory = { FakeMeshLink().also { links += it } },
        sessionFactory = { if (sessionsBuilt++ == 0) first else second },
        nowEpochSeconds = { NOW },
        ports = ConnectionManagerPorts(rxChannelCache = cache),
    )

    suspend fun start(handle: String, session: ExportSession): HeldConnect {
        val caller = scope.async { manager.connect(handle) }.also { callers += it }
        val operation = session.completed.await()
        assertActiveOwner(caller, operation)
        assertEquals(DELEGATE_BYTES, session.delegatedBytes)
        assertEquals(EXPANDED_BYTES, session.payload.size)
        assertEquals(1, session.exportPrivateKeyCount)
        val container = requireNotNull(manager.services)
        assertSame(session, manager.session)
        assertContentEquals(session.publicKey, container.selfPublicKey)
        return HeldConnect(caller, container, session, links.last(), handle, manager.connectGeneration)
    }

    fun assertActiveOwner(caller: Job, operation: Job) {
        assertTrue(caller.isActive)
        assertTrue(operation.isActive)
        assertTrue(caller.owns(operation))
    }

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

    suspend fun nodes() = database.remoteNodeSessionDao().getAll().sortedBy { it.id.value }.map {
        it.copy(publicKey = EMPTY_KEY) to it.publicKey.toList()
    }

    suspend fun devices() = database.deviceDao().listAll().sortedBy { it.id.value }.map {
        it.copy(publicKey = EMPTY_KEY) to it.publicKey.toList()
    }

    suspend fun storedRows() = listOf(
        nodes(),
        devices(),
        database.contactDao().listAll().sortedBy { it.id.value }.map {
            listOf(
                it.copy(publicKey = EMPTY_KEY, outPath = EMPTY_KEY, avatarImageData = null),
                it.publicKey.toList(),
                it.outPath.toList(),
                it.avatarImageData?.toList(),
            )
        },
        database.channelDao().listAll().sortedBy { it.id.value }.map {
            it.copy(secret = EMPTY_KEY) to it.secret.toList()
        },
    )

    suspend fun assertNoOutgoing() {
        assertTrue(first.sentMessages.isEmpty())
        assertTrue(second.sentMessages.isEmpty())
        assertTrue(links.all { it.sent.isEmpty() })
        assertTrue(database.messageDao().listAll().isEmpty())
        database.deviceDao().listAll().forEach {
            assertTrue(database.pendingSendDao().listByRadio(it.radioID).isEmpty())
        }
        assertTrue(database.pendingSendDao().listByRadio(FOREIGN_RADIO).isEmpty())
    }

    suspend fun finish(held: HeldConnect, obsolete: Boolean = false) {
        held.session.release.complete(Unit)
        try {
            held.caller.await()
        } catch (cancelled: CancellationException) {
            if (!obsolete) throw cancelled
            assertTrue(held.caller.isCancelled)
        }
        barrier()
        assertTrue(held.caller.isCompleted)
    }

    suspend fun assertReady(held: HeldConnect) {
        assertEquals(ConnectionState.Ready(held.handle), manager.state.value)
        assertTrue(manager.wantsConnection)
        assertEquals(held.handle, manager.currentDeviceId)
        assertEquals(held.generation, manager.connectGeneration)
        assertSame(held.container, manager.services)
        assertSame(held.session, manager.session)
        assertSame(held.link, manager.link)
        assertEquals(held.container.radioId, manager.radioId)
        assertFalse(held.container.sendQueue.isRetired)
        assertTrue(held.container.isEventMonitoringActive)
        assertContentEquals(
            held.session.publicKey,
            database.deviceDao().listByRadio(held.container.radioId).single().publicKey,
        )
        assertEquals(1, held.session.exportPrivateKeyCount)
        assertEquals(1, held.session.autoFetchStartCount)
        assertEquals(1, held.session.getTimeCount)
        assertTrue(scope.coroutineContext.job.isActive)
        assertNoOutgoing()
    }

    fun assertRetired(held: HeldConnect) {
        assertTrue(held.container.sendQueue.isRetired)
        assertTrue(held.session.closed)
        assertTrue(held.link.disconnected)
        assertEquals(0, held.session.autoFetchStartCount)
        assertEquals(0, held.session.getTimeCount)
        assertTrue(held.session.getContactsSince.isEmpty())
        assertEquals(0, held.session.getChannelsCallCount)
        assertEquals(0, held.session.getMessageCount)
    }

    fun holdCache() {
        assertTrue(cacheMutex.tryLock(lockOwner))
        ownsCacheLock = true
    }

    fun releaseCache() {
        if (ownsCacheLock) {
            ownsCacheLock = false
            cacheMutex.unlock(lockOwner)
        }
    }

    suspend fun barrier() = coroutineScope { async(dispatcher) { Unit }.await() }

    fun close() {
        first.release.complete(Unit)
        second.release.complete(Unit)
        releaseCache()
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

    class ExportSession(val publicKey: ByteArray, scalar: Int) :
        FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) {
        val completed = CompletableDeferred<Job>()
        val returning = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        val payload = ByteArray(EXPANDED_BYTES) { scalar.toByte() }
        var delegatedBytes = 0
            private set

        override suspend fun exportPrivateKey(): ByteArray {
            delegatedBytes = super.exportPrivateKey().size
            completed.complete(currentCoroutineContext().job)
            release.await()
            returning.complete(currentCoroutineContext().job)
            return payload
        }
    }

    data class HeldConnect(
        val caller: Deferred<Unit>,
        val container: ServiceContainer,
        val session: ExportSession,
        val link: FakeMeshLink,
        val handle: String,
        val generation: Long,
    )

    companion object {
        const val DEVICE_A = "AA:BB:CC:DD:EE:88"
        const val DEVICE_B = "AA:BB:CC:DD:EE:89"
        const val TIMEOUT_MS = 10_000L
        const val SCALAR_BYTES = 32
        private const val DELEGATE_BYTES = 32
        private const val EXPANDED_BYTES = 64
        private const val FIRST_SCALAR = 49
        private const val SECOND_SCALAR = 50
        private val FIRST_PUBLIC_KEY = ByteArray(SCALAR_BYTES) { 17 }
        private val SECOND_PUBLIC_KEY = ByteArray(SCALAR_BYTES) { 34 }
        private val EMPTY_KEY = ByteArray(0)
        private const val NOW = 1_700_000_000.0
        private val FOREIGN_ID = testUuid(2)
        private val FOREIGN_RADIO = testUuid(98)
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

        private fun Job.owns(other: Job): Boolean = this === other || children.any { it.owns(other) }
    }
}
