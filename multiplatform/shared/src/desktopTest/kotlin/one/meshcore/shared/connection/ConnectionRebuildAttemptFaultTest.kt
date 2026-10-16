package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A cancelled in-place session rebuild is rethrown and leaves the link up. A handshake error on a
 * live Bluetooth link is retried in place, then the attempt ends without dropping that link.
 */
class ConnectionRebuildAttemptFaultTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-rebuild-attempt-fault", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    @Test
    fun aCancelledRebuildAttemptLeavesTheLinkUp() = runBlocking {
        val stopped = CancellationException("stopped")
        val session = CountingQuerySession().apply { failQueryWith = stopped }
        val link = FakeMeshLink()
        val manager = manager(session, link)

        val thrown = assertFailsWith<CancellationException> {
            manager.rebuildSessionInPlace(DEVICE_HANDLE, manager.connectGeneration)
        }

        assertTrue(thrown === stopped || thrown.cause === stopped)
        assertEquals(1, session.queries)
        assertFalse(link.disconnected)
    }

    @Test
    fun aRebuildAttemptErrorRetriesThenKeepsTheLink() = runBlocking {
        val session = CountingQuerySession().apply { failQueryWith = RebuildAttemptProbeError() }
        val link = FakeMeshLink()
        val manager = manager(session, link)

        val rebuilt = manager.rebuildSessionInPlace(DEVICE_HANDLE, manager.connectGeneration)

        assertFalse(rebuilt)
        assertEquals(ReconnectPolicy.Config.DEFAULT_MAX_REBUILD_FAILURES_PRESERVING_LINK, session.queries)
        assertFalse(link.disconnected)
    }

    private fun manager(session: CountingQuerySession, link: FakeMeshLink) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { link },
        sessionFactory = { session },
        nowEpochSeconds = { NOW_SECONDS },
        ports = ConnectionManagerPorts(
            policy = ReconnectPolicy(
                ReconnectPolicy.Config(sessionRebuildRetryDelay = FAST_REBUILD_DELAY),
            ),
        ),
    ).apply {
        this.link = link
        wantsConnection = true
        currentDeviceId = DEVICE_HANDLE
        currentTransportType = TransportType.BLUETOOTH
    }

    private companion object {
        const val DEVICE_HANDLE = "AA:BB:CC:DD:EE:73"
        const val NOW_SECONDS = 1_700_000_000.0
        val FAST_REBUILD_DELAY = 1.milliseconds
    }
}

private class CountingQuerySession : FakeMeshSession() {
    var queries = 0

    override suspend fun queryDevice(): DeviceCapabilities {
        queries += 1
        return super.queryDevice()
    }
}

private class RebuildAttemptProbeError : Error("probe")
