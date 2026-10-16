package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.transport.LinkDropReason
import one.meshcore.shared.transport.MeshLinkConnectException
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The disconnect diagnostic a responder sends back after a radio drops. It has to be written at every
 * way out of a connection — the deliberate disconnect and the link loss — and read back across launches.
 */
class ConnectionDisconnectDiagnosticTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: InMemoryPreferencesStore
    private lateinit var lastConnection: LastConnectionStore

    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000088")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-disconnect-diagnostic", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        prefs = InMemoryPreferencesStore()
        lastConnection = LastConnectionStore(prefs)
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    private fun manager(session: FakeMeshSession, link: FakeMeshLink) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { link },
        sessionFactory = { session },
        nowEpochSeconds = { NOW_SECONDS },
        ports = ConnectionManagerPorts(
            newRadioId = { radioId },
            lastConnectionStore = lastConnection,
            intentStore = ConnectionIntentStore(prefs),
        ),
    )

    @Test
    fun nothingIsRecordedBeforeTheFirstDisconnect() {
        runBlocking {
            val manager = manager(FakeMeshSession(), FakeMeshLink())
            manager.connect(DEVICE_HANDLE)

            assertNull(manager.lastDisconnectDiagnostic())
        }
    }

    @Test
    fun aUserDisconnectRecordsItsSourceReasonAndDevice() {
        runBlocking {
            val manager = manager(FakeMeshSession(), FakeMeshLink())
            manager.connect(DEVICE_HANDLE)

            manager.disconnect(DisconnectReason.USER_INITIATED)

            val diagnostic = assertNotNull(manager.lastDisconnectDiagnostic())
            assertContains(diagnostic, NOW_SECONDS.toString())
            assertContains(diagnostic, "source=disconnect")
            assertContains(diagnostic, "reason=${DisconnectReason.USER_INITIATED.name}")
            assertContains(diagnostic, DEVICE_HANDLE)
        }
    }

    @Test
    fun aLostLinkRecordsItsDropReason() {
        runBlocking {
            val link = FakeMeshLink()
            val manager = manager(FakeMeshSession(), link)
            manager.connect(DEVICE_HANDLE)

            link.drop(LinkDropReason.BOND_LOST)
            awaitCondition { manager.lastDisconnectDiagnostic() != null }

            val diagnostic = assertNotNull(manager.lastDisconnectDiagnostic())
            assertContains(diagnostic, "source=linkDrop")
            assertContains(diagnostic, "reason=${LinkDropReason.BOND_LOST.name}")
        }
    }

    @Test
    fun aLaunchAutoReconnectFailureRecordsItsSource() {
        runBlocking {
            val manager = manager(FakeMeshSession(), FakeMeshLink())
            manager.persistActivateReconnectFailure(
                DEVICE_HANDLE,
                MeshLinkConnectException("out of range"),
            )

            val diagnostic = assertNotNull(manager.lastDisconnectDiagnostic())
            assertContains(diagnostic, "source=activate.autoReconnectFailed")
            assertContains(diagnostic, DEVICE_HANDLE)
            assertContains(diagnostic, "out of range")
        }
    }

    @Test
    fun theIntentSummaryTellsAUserDisconnectFromAWantedConnection() {
        runBlocking {
            val manager = manager(FakeMeshSession(), FakeMeshLink())
            manager.connect(DEVICE_HANDLE)
            assertEquals("wantsConnection", manager.connectionIntentSummary())

            manager.disconnect(DisconnectReason.USER_INITIATED)

            assertEquals("userDisconnected", manager.connectionIntentSummary())
        }
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val DEVICE_HANDLE = "AA:BB:CC:DD:EE:88"
        const val NOW_SECONDS = 1_700_000_000.0
    }
}
