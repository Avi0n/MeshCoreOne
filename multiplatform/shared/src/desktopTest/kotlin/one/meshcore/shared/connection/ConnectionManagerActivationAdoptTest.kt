package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Adopting a system-connected last radio records a connect failure and arms reconnect.
 * Cancellation leaves the adopt line and does not arm. An error is recorded the same way.
 */
class ConnectionManagerActivationAdoptTest {
    private class AdoptProbeError : Error("probe")

    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var prefs: InMemoryPreferencesStore
    private lateinit var lastConnection: LastConnectionStore

    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000011")
    private val deviceId = Uuid.normalized("B0000000-0000-0000-0000-000000000011")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-adopt", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        prefs = InMemoryPreferencesStore()
        lastConnection = LastConnectionStore(prefs)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun aFailedAdoptRecordsTheReconnectFailureAndArmsReconnect() = runTest {
        val scope = managerScope(testScheduler)
        try {
            val link = FakeMeshLink().also { it.failConnectWith = IllegalStateException("gatt down") }
            val manager = manager(scope, link)
            seedLastRadio()

            val started = manager.startAdoptingLastSystemConnectedIfAvailable("activate", HANDLE)

            assertTrue(started)
            assertEquals(1, link.connectCount)
            val diagnostic = assertNotNull(manager.lastDisconnectDiagnostic())
            assertContains(diagnostic, "source=activate.autoReconnectFailed")
            assertContains(diagnostic, "gatt down")
            assertFalse(diagnostic.contains("adoptSystemConnectedPeripheral"))
            assertTrue(manager.isBleReconnectInProgress())
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun aCancelledAdoptKeepsTheAdoptLineAndDoesNotArmReconnect() = runTest {
        val scope = managerScope(testScheduler)
        try {
            val cancelled = CancellationException("superseded")
            val link = FakeMeshLink().also { it.failConnectWith = cancelled }
            val manager = manager(scope, link)
            seedLastRadio()

            val thrown = assertFailsWith<CancellationException> {
                manager.startAdoptingLastSystemConnectedIfAvailable("activate", HANDLE)
            }

            assertSame(cancelled, thrown)
            assertEquals(1, link.connectCount)
            val diagnostic = assertNotNull(manager.lastDisconnectDiagnostic())
            assertContains(diagnostic, "source=activate.adoptSystemConnectedPeripheral")
            assertFalse(diagnostic.contains("autoReconnectFailed"))
            assertFalse(manager.isBleReconnectInProgress())
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun anAdoptErrorIsRecordedTheSameWay() = runTest {
        val scope = managerScope(testScheduler)
        try {
            val link = FakeMeshLink().also { it.failConnectWith = AdoptProbeError() }
            val manager = manager(scope, link)
            seedLastRadio()

            val started = manager.startAdoptingLastSystemConnectedIfAvailable("activate", HANDLE)

            assertTrue(started)
            assertEquals(1, link.connectCount)
            val diagnostic = assertNotNull(manager.lastDisconnectDiagnostic())
            assertContains(diagnostic, "source=activate.autoReconnectFailed")
            assertContains(diagnostic, "probe")
            assertTrue(manager.isBleReconnectInProgress())
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private fun managerScope(scheduler: TestCoroutineScheduler): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

    private fun manager(scope: CoroutineScope, link: FakeMeshLink) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { link },
        sessionFactory = { FakeMeshSession() },
        nowEpochSeconds = { NOW_SECONDS },
        ports = ConnectionManagerPorts(
            connectRetryPolicy = ConnectRetryPolicy(
                maxAttempts = 1,
                unverifiedAttempts = 1,
                baseBackoff = 1.milliseconds,
            ),
            systemConnectedHandles = { setOf(HANDLE) },
            lastConnectionStore = lastConnection,
            newRadioId = { radioId },
        ),
    )

    private suspend fun seedLastRadio() {
        lastConnection.persist(
            deviceId = deviceId,
            radioId = radioId,
            deviceName = "Field radio",
            handle = HANDLE,
        )
    }

    private companion object {
        const val HANDLE = "AA:BB:CC:DD:EE:11"
        const val NOW_SECONDS = 1_700_000_000.0
    }
}
