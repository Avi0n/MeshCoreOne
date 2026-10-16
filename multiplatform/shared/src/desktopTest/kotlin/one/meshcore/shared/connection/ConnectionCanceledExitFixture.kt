package one.meshcore.shared.connection

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionCanceledExitSession.Companion.ORIGIN_NAME
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.productionSchemaPolicy
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.remotenode.testUuid
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

internal class ConnectionCanceledExitFixture {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val first = ConnectionCanceledExitSession(PUBLIC_A)
    val states = mutableListOf<ConnectionState>()
    val callers = mutableListOf<Job>()
    val cancellation = CancellationException(CANCEL_MESSAGE)
    private val links = mutableListOf<FakeMeshLink>()
    private val handles = mutableMapOf<MeshLink, String>()
    private var firstCreated = false
    private val prefs = InMemoryPreferencesStore()
    val last = LastConnectionStore(prefs)
    val intent = ConnectionIntentStore(prefs)
    private val file = File.createTempFile("canceled-exit", ".db").also { it.delete() }
    val database = Room.databaseBuilder<MeshDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .productionSchemaPolicy()
        .build()
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
            if (!firstCreated) {
                firstCreated = true
                first
            } else {
                FakeMeshSession(selfInfo = defaultSelfInfo(if (handles[link] == DEVICE_A) PUBLIC_A else PUBLIC_B))
            }
        },
        nowEpochSeconds = { NOW },
        ports = ConnectionManagerPorts(lastConnectionStore = last, intentStore = intent),
    )

    suspend fun start(): HeldConnect {
        callers += scope.launch(start = CoroutineStart.UNDISPATCHED) { manager.state.collect { states += it } }
        val caller = scope.async(CoroutineName(ORIGIN_NAME)) { manager.connect(DEVICE_A) }.also { callers += it }
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
        seed()
        return held
    }

    suspend fun canceledCleanup(held: HeldConnect): CoroutineContext {
        first.armStop()
        held.caller.cancel(cancellation)
        val context = first.stopCompleted.await()
        assertTrue(held.caller.isCancelled)
        assertFalse(held.caller.isCompleted)
        assertTrue(context.job.isActive)
        assertEquals(ORIGIN_NAME, context[CoroutineName]?.name)
        assertTrue(scope.coroutineContext.job.isActive)
        assertEquals(held.generation + RETIREMENT_INCREMENT, manager.connectGeneration)
        assertSame(held.container, manager.services)
        assertSame(held.session, manager.session)
        assertSame(held.link, manager.link)
        assertTrue(held.container.sendQueue.isRetired)
        assertFalse(held.container.isEventMonitoringActive)
        assertEquals(1, first.autoFetchStopCount)
        return context
    }

    suspend fun replacement(rebuild: Boolean): HeldConnect {
        val caller = scope.async {
            if (rebuild) manager.rebuildConnectedBleAppStack(DEVICE_A) else manager.connect(DEVICE_B)
        }.also { callers += it }
        caller.await()
        barrier()
        val container = requireNotNull(manager.services)
        val session = requireNotNull(manager.session) as FakeMeshSession
        val link = requireNotNull(manager.link) as FakeMeshLink
        return HeldConnect(caller, container, session, link, manager.connectGeneration)
    }

    fun assertActiveOwner(caller: Job, operation: Job) {
        assertTrue(caller.isActive)
        assertTrue(operation.isActive)
        assertTrue(caller.owns(operation))
    }

    suspend fun assertResources(held: HeldConnect, handle: String) {
        assertSame(held.container, manager.services)
        assertSame(held.session, manager.session)
        assertSame(held.link, manager.link)
        assertEquals(held.generation, manager.connectGeneration)
        assertEquals(held.container.radioId, manager.radioId)
        assertEquals(handle, manager.currentDeviceId)
        assertEquals(TransportType.BLUETOOTH, manager.currentTransportType)
        assertNull(manager.wifiHost)
        assertNull(manager.wifiPort)
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

    suspend fun joinedCancellation(held: HeldConnect) {
        var caught: CancellationException? = null
        try {
            held.caller.await()
        } catch (error: CancellationException) {
            caught = error
        }
        assertTrue(caught === cancellation || caught?.cause === cancellation)
        assertTrue(held.caller.isCancelled)
        assertTrue(held.caller.isCompleted)
        barrier()
    }

    suspend fun storedRows() = listOf(
        database.deviceDao().listAll().sortedBy { it.id.value }.map {
            it.copy(publicKey = EMPTY) to it.publicKey.toList()
        },
        database.remoteNodeSessionDao().getAll().sortedBy { it.id.value }.map {
            it.copy(publicKey = EMPTY) to it.publicKey.toList()
        },
        database.contactDao().listAll().sortedBy { it.id.value }.map {
            listOf(
                it.copy(publicKey = EMPTY, outPath = EMPTY, avatarImageData = null),
                it.publicKey.toList(),
                it.outPath.toList(),
                it.avatarImageData?.toList(),
            )
        },
        database.channelDao().listAll().sortedBy { it.id.value }.map { it.copy(secret = EMPTY) to it.secret.toList() },
    )

    suspend fun persistedIntent() = listOf(
        intent.restore(),
        last.deviceId(),
        last.radioId(),
        last.handle(),
        last.deviceName(),
        last.disconnectDiagnostic(),
    )

    suspend fun assertNoOutgoing() {
        assertTrue(first.sentMessages.isEmpty())
        (manager.session as? FakeMeshSession)?.let { assertTrue(it.sentMessages.isEmpty()) }
        assertTrue(links.all { it.sent.isEmpty() })
        assertTrue(database.messageDao().listAll().isEmpty())
        database.deviceDao().listAll().forEach {
            assertTrue(database.pendingSendDao().listByRadio(it.radioID).isEmpty())
        }
        assertTrue(database.pendingSendDao().listByRadio(FOREIGN_RADIO).isEmpty())
    }

    suspend fun barrier() = coroutineScope { async(dispatcher) { Unit }.await() }

    private suspend fun seed() {
        val store = requireNotNull(manager.services).remoteNodeService.store
        store.saveSession(
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
        store.saveSession(testSession(id = FOREIGN_ID, radioID = FOREIGN_RADIO, publicKey = testPublicKey(FOREIGN_KEY)))
    }

    fun close() {
        first.releaseAll()
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
        val caller: Deferred<Unit>,
        val container: ServiceContainer,
        val session: FakeMeshSession,
        val link: FakeMeshLink,
        val generation: Long,
    )

    companion object {
        const val DEVICE_A = "AA:BB:CC:DD:EE:90"
        const val DEVICE_B = "AA:BB:CC:DD:EE:91"
        const val TIMEOUT_MS = 10_000L
        private const val RETIREMENT_INCREMENT = 1L
        private const val CANCEL_MESSAGE = "fixture initial connect canceled"
        private const val KEY_BYTES = 32
        private val PUBLIC_A = ByteArray(KEY_BYTES) { 17 }
        private val PUBLIC_B = ByteArray(KEY_BYTES) { 34 }
        private val EMPTY = ByteArray(0)
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
