package one.meshcore.shared.remotenode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.RemoteNodeSessionEntity
import one.meshcore.shared.persistence.RoomNodeStore
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.settings.CommandAuditLogger
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertTrue

internal class RemoteLoginAttributionFixture {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val file = File.createTempFile("remote-login-attribution-", ".db").also { check(it.delete()) }
    val database = meshDatabase(file.absolutePath)
    val node = FakeNodeSession()
    val audit = mutableListOf<DebugLogEntryDto>()
    val events = mutableListOf<RemoteNodeEvent>()
    val eventReceived = CompletableDeferred<Unit>()
    lateinit var service: RemoteNodeService
        private set
    private var eventCollector: Job? = null

    suspend fun prepare(kind: Case): Setup {
        val firstKey = testPublicKey(FIRST_KEY)
        val secondKey = when (kind) {
            Case.SAME_RADIO_PREFIX -> testPublicKey(SECOND_KEY).also {
                firstKey.copyInto(it, endIndex = PUBLIC_KEY_PREFIX_LENGTH)
            }
            Case.CROSS_RADIO_KEY -> firstKey.copyOf()
            Case.UNIQUE_PREFIX -> testPublicKey(SECOND_KEY)
        }
        val first = richSession(FIRST_ID, CURRENT_RADIO, firstKey, RemoteNodeRole.REPEATER)
        val second = richSession(
            SECOND_ID,
            if (kind == Case.CROSS_RADIO_KEY) OTHER_RADIO else CURRENT_RADIO,
            secondKey,
            RemoteNodeRole.ROOM_SERVER,
        )
        val foreign = richSession(FOREIGN_ID, FOREIGN_RADIO, testPublicKey(FOREIGN_KEY), RemoteNodeRole.REPEATER)
        val seedStore = store(CURRENT_RADIO)
        listOf(first, second, foreign).forEach { seedStore.saveSession(it) }
        val selected = requireNotNull(seedStore.fetchSessionByPrefix(firstKey.copyOf(PUBLIC_KEY_PREFIX_LENGTH)))
        val requested = if (kind == Case.UNIQUE_PREFIX || selected.id == first.id) second else first
        val decoy = if (requested.id == first.id) second else first
        val backing = store(requested.radioID)
        val subscribed = CompletableDeferred<Unit>()
        val session = object : NodeSession by node {
            override val events = node.events.onSubscription { subscribed.complete(Unit) }
        }
        service = RemoteNodeService(
            session,
            backing,
            InMemoryNodePasswordStore(),
            scope,
            options = RemoteNodeServiceOptions(auditLogger = CommandAuditLogger(record = { audit += it })),
            logger = PersistentLogger(scope, category = "RemoteNodeService", record = {}),
        )
        subscribed.await()
        val eventsSubscribed = CompletableDeferred<Unit>()
        eventCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.onSubscription { eventsSubscribed.complete(Unit) }.collect {
                events += it
                eventReceived.complete(Unit)
            }
        }
        eventsSubscribed.await()
        return Setup(kind, requested, decoy, selected.id, rows(), requested.radioID)
    }

    suspend fun rows(): List<RemoteNodeSessionEntity> =
        database.remoteNodeSessionDao().getAll().sortedBy { it.id.value }

    fun collectorActive(): Boolean = eventCollector?.isActive == true

    fun close() {
        node.onSendLogin = null
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    if (::service.isInitialized) {
                        val monitor = service.eventMonitorJob
                        service.retireConnectionOperations()
                        service.stopEventMonitoring()
                        service.stopAllKeepAlives()
                        monitor?.join()
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

    private fun store(radioID: Uuid) = RoomNodeStore(
        radioID,
        database.remoteNodeSessionDao(),
        database.roomMessageDao(),
        database.contactDao(),
    ) { NEW_TIME }

    private fun richSession(id: Uuid, radioID: Uuid, key: ByteArray, role: RemoteNodeRole) = testSession(
        id = id,
        radioID = radioID,
        publicKey = key,
        role = role,
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

    enum class Case { SAME_RADIO_PREFIX, CROSS_RADIO_KEY, UNIQUE_PREFIX }

    data class Setup(
        val kind: Case,
        val requested: RemoteNodeSessionDto,
        val decoy: RemoteNodeSessionDto,
        val selectedID: Uuid,
        val before: List<RemoteNodeSessionEntity>,
        val currentRadioID: Uuid,
    )

    companion object {
        const val TIMEOUT_MS = 10_000L
        const val PASSWORD = "synthetic-test-password"
        const val FAILURE_REASON = "authentication failed"
        const val OLD_TIME = 1_700_000_000.0
        const val NEW_TIME = OLD_TIME + 60.0
        val FIRST_ID = testUuid(1)
        val SECOND_ID = testUuid(2)
        val FOREIGN_ID = testUuid(3)
        private val CURRENT_RADIO = testUuid(90)
        private val OTHER_RADIO = testUuid(91)
        private val FOREIGN_RADIO = testUuid(92)
        private const val FIRST_KEY = 65
        private const val SECOND_KEY = 66
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
    }
}
