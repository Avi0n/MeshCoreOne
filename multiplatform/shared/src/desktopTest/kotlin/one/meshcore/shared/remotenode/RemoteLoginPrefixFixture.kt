package one.meshcore.shared.remotenode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.RemoteNodeSessionEntity
import one.meshcore.shared.persistence.RoomNodeStore
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.settings.CommandAuditLogger
import one.meshcore.shared.settings.PersistentLogger
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class RemoteLoginPrefixFixture {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val oldScheduler = TestCoroutineScheduler()
    val oldScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(oldScheduler))
    private val file = File.createTempFile("remote-login-prefix-", ".db").also { check(it.delete()) }
    val database = meshDatabase(file.absolutePath)
    val node = FakeNodeSession()
    val initialRead = CompletableDeferred<RemoteNodeSessionDto?>()
    val oldSent = CompletableDeferred<Registration>()
    val newSent = CompletableDeferred<Registration>()
    val eventReceived = CompletableDeferred<Unit>()
    val events = mutableListOf<RemoteNodeEvent>()
    lateinit var service: RemoteNodeService
        private set
    lateinit var monitor: Job
        private set
    lateinit var oldCaller: Deferred<LoginResult>
        private set
    lateinit var newCaller: Deferred<LoginResult>
        private set
    var oldOutcome: Result<LoginResult>? = null
        private set
    var newOutcome: Result<LoginResult>? = null
        private set
    var initialReadOwner: Job? = null
        private set
    private var readOperation: Job? = null
    private var initialReadArmed = false
    private var eventCollector: Job? = null
    private val timers = mutableListOf<Job>()

    suspend fun prepare(): Setup {
        val requested = richSession(REQUESTED_ID, CURRENT_RADIO, testPublicKey(FIRST_KEY))
        val foreign = richSession(FOREIGN_ID, FOREIGN_RADIO, testPublicKey(FOREIGN_KEY))
        val backing = RoomNodeStore(
            CURRENT_RADIO,
            database.remoteNodeSessionDao(),
            database.roomMessageDao(),
            database.contactDao(),
        ) { NEW_TIME }
        listOf(requested, foreign).forEach { backing.saveSession(it) }
        val store = object : RemoteNodeStore by backing {
            override suspend fun fetchSessionById(id: Uuid): RemoteNodeSessionDto? {
                val operation = currentCoroutineContext().job
                if (initialReadArmed && id == requested.id && oldCaller.owns(operation)) {
                    check(operation.isActive)
                    initialReadArmed = false
                    initialReadOwner = operation
                    return withContext(dispatcher) {
                        readOperation = currentCoroutineContext().job
                        backing.fetchSessionById(id).also { initialRead.complete(it) }
                    }
                }
                return backing.fetchSessionById(id)
            }
        }
        val subscribed = CompletableDeferred<Unit>()
        val session = object : NodeSession by node {
            override val events = node.events.onSubscription { subscribed.complete(Unit) }
        }
        service = RemoteNodeService(
            session,
            store,
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
        node.onSendLogin = { index ->
            val sender = currentCoroutineContext().job
            val registration = service.mutex.withLock {
                val prefix = requested.publicKey.take(PUBLIC_KEY_PREFIX_LENGTH)
                Registration(
                    requireNotNull(service.pendingLogins[prefix]),
                    requireNotNull(service.pendingLoginTimeoutJobs[prefix]),
                    sender,
                )
            }
            timers += registration.timer
            when (index) {
                FIRST_SEND -> oldSent.complete(registration)
                SECOND_SEND -> newSent.complete(registration)
                else -> error("Unexpected extra login send")
            }
        }
        return Setup(requested, rows())
    }

    suspend fun startOld(setup: Setup): Registration {
        oldCaller = oldScope.async(start = CoroutineStart.LAZY) {
            runCatching { service.login(setup.requested.id, PASSWORD) }.also { oldOutcome = it }.getOrThrow()
        }
        initialReadArmed = true
        oldCaller.start()
        oldScheduler.runCurrent()
        initialRead.await()
        barrier()
        oldScheduler.runCurrent()
        val registration = oldSent.await()
        barrier()
        return registration
    }

    suspend fun startNew(setup: Setup): Registration {
        newCaller = scope.async {
            runCatching { service.login(setup.requested.id, PASSWORD) }.also { newOutcome = it }.getOrThrow()
        }
        val registration = newSent.await()
        barrier()
        return registration
    }

    suspend fun barrier() = coroutineScope { async(dispatcher) { Unit }.await() }

    suspend fun rows() = database.remoteNodeSessionDao().getAll().sortedBy { it.id.value }

    fun collectorActive() = eventCollector?.isActive == true

    private suspend fun cancelCallers() {
        if (::oldCaller.isInitialized) oldCaller.cancel()
        if (::newCaller.isInitialized) newCaller.cancel()
        oldScope.coroutineContext.job.cancel()
        readOperation?.join()
        barrier()
        oldScheduler.runCurrent()
        if (::oldCaller.isInitialized) oldCaller.join()
        if (::newCaller.isInitialized) newCaller.join()
        oldScope.coroutineContext.job.join()
    }

    fun close() {
        node.onSendLogin = null
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    cancelCallers()
                    if (::service.isInitialized) {
                        service.retireConnectionOperations()
                        service.stopEventMonitoring()
                        service.stopAllKeepAlives()
                        if (::monitor.isInitialized) monitor.join()
                        timers.forEach { it.join() }
                    }
                }
            }
        } finally {
            try {
                runBlocking(dispatcher) {
                    try {
                        withTimeout(TIMEOUT_MS) { cancelCallers() }
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

    data class Registration(val pending: PendingLogin, val timer: Job, val sender: Job)

    companion object {
        const val TIMEOUT_MS = 10_000L
        const val PASSWORD = "synthetic-test-password"
        const val OLD_TIME = 1_700_000_000.0
        const val NEW_TIME = OLD_TIME + 60.0
        const val FIRST_SEND = 1
        const val SECOND_SEND = 2
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
