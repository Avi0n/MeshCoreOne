package one.meshcore.shared.remotenode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.RemoteNodeSessionDao
import one.meshcore.shared.persistence.RemoteNodeSessionEntity
import one.meshcore.shared.persistence.RoomNodeStore
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.settings.CommandAuditLogger
import one.meshcore.shared.settings.PersistentLogger
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertTrue

internal class RemoteLoginSettlementFixture {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val file = File.createTempFile("remote-login-settlement-", ".db").also { check(it.delete()) }
    val database = meshDatabase(file.absolutePath)
    val node = FakeNodeSession()
    val entered = CompletableDeferred<ReadGate>()
    val release = CompletableDeferred<Unit>()
    val eventReceived = CompletableDeferred<Unit>()
    val events = mutableListOf<RemoteNodeEvent>()
    lateinit var service: RemoteNodeService
        private set
    lateinit var monitor: Job
        private set
    lateinit var pending: PendingLogin
        private set
    lateinit var timeout: Job
        private set
    lateinit var caller: Deferred<LoginResult>
        private set
    var outcome: Result<LoginResult>? = null
        private set
    private var eventCollector: Job? = null
    private var armed = false

    suspend fun prepare(): Setup {
        val requested = richSession(REQUESTED_ID, CURRENT_RADIO, testPublicKey(FIRST_KEY))
        val foreign = richSession(FOREIGN_ID, FOREIGN_RADIO, testPublicKey(FOREIGN_KEY))
        val backingDao = database.remoteNodeSessionDao()
        val seedStore = store(CURRENT_RADIO, backingDao)
        listOf(requested, foreign).forEach { seedStore.saveSession(it) }
        val gatedDao = object : RemoteNodeSessionDao by backingDao {
            override suspend fun getById(id: Uuid): RemoteNodeSessionEntity? {
                val row = backingDao.getById(id)
                val operation = currentCoroutineContext().job
                if (armed && id == requested.id && monitor.isActive && monitor.owns(operation)) {
                    check(operation.isActive)
                    armed = false
                    entered.complete(ReadGate(operation, requireNotNull(row)))
                    release.await()
                }
                return row
            }
        }
        val subscribed = CompletableDeferred<Unit>()
        val session = object : NodeSession by node {
            override val events = node.events.onSubscription { subscribed.complete(Unit) }
        }
        service = RemoteNodeService(
            session,
            store(CURRENT_RADIO, gatedDao),
            InMemoryNodePasswordStore(),
            scope,
            options = RemoteNodeServiceOptions(auditLogger = CommandAuditLogger(record = {})),
            logger = PersistentLogger(scope, category = "RemoteNodeService", record = {}),
        )
        subscribed.await()
        monitor = requireNotNull(service.eventMonitorJob)
        val eventsSubscribed = CompletableDeferred<Unit>()
        eventCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.onSubscription { eventsSubscribed.complete(Unit) }.collect {
                events += it
                eventReceived.complete(Unit)
            }
        }
        eventsSubscribed.await()
        node.onSendLogin = {
            service.mutex.withLock {
                val prefix = requested.publicKey.take(PUBLIC_KEY_PREFIX_LENGTH)
                pending = requireNotNull(service.pendingLogins[prefix])
                timeout = requireNotNull(service.pendingLoginTimeoutJobs[prefix])
            }
            node.emitLoginSuccess(testLoginInfo(requested.publicKey))
        }
        armed = true
        return Setup(requested, rows())
    }

    fun start(setup: Setup) {
        caller = scope.async {
            runCatching { service.login(setup.requested.id, PASSWORD) }.also { outcome = it }.getOrThrow()
        }
    }

    suspend fun rows() = database.remoteNodeSessionDao().getAll().sortedBy { it.id.value }

    fun collectorActive() = eventCollector?.isActive == true

    fun close() {
        release.complete(Unit)
        node.onSendLogin = null
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    if (::caller.isInitialized) caller.cancelAndJoin()
                    if (::service.isInitialized) {
                        service.retireConnectionOperations()
                        service.stopEventMonitoring()
                        service.stopAllKeepAlives()
                        if (::monitor.isInitialized) monitor.join()
                        if (::timeout.isInitialized) timeout.join()
                    }
                }
            }
        } finally {
            try {
                runBlocking(dispatcher) {
                    withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() }
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

    private fun store(radioID: Uuid, dao: RemoteNodeSessionDao) = RoomNodeStore(
        radioID,
        dao,
        database.roomMessageDao(),
        database.contactDao(),
    ) { NEW_TIME }

    private fun richSession(id: Uuid, radioID: Uuid, key: ByteArray) = testSession(
        id = id,
        radioID = radioID,
        publicKey = key,
        permissionLevel = RoomPermissionLevel.READ_WRITE,
        lastSyncTimestamp = SYNC_TIME,
    ).copy(
        name = "Saved ${id.value}",
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
        lastMessageDate = OLD_TIME,
    )

    data class Setup(val requested: RemoteNodeSessionDto, val before: List<RemoteNodeSessionEntity>)

    data class ReadGate(val operation: Job, val row: RemoteNodeSessionEntity)

    companion object {
        const val TIMEOUT_MS = 10_000L
        const val DRAIN_TURNS = 4
        const val PASSWORD = "synthetic-test-password"
        const val OLD_TIME = 1_700_000_000.0
        const val NEW_TIME = OLD_TIME + 60.0
        private val REQUESTED_ID = testUuid(1)
        private val FOREIGN_ID = testUuid(2)
        private val CURRENT_RADIO = testUuid(90)
        private val FOREIGN_RADIO = testUuid(91)
        private const val FIRST_KEY = 65
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
        private val EMPTY_KEY = ByteArray(0)

        fun wholeRows(rows: List<RemoteNodeSessionEntity>) = rows.map {
            it.copy(publicKey = EMPTY_KEY) to it.publicKey.toList()
        }

        fun Job.owns(other: Job): Boolean = this === other || children.any { it.owns(other) }
    }
}
