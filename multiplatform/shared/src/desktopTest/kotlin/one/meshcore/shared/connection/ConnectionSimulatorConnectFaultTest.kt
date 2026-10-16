package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A cancelled simulator connect is abandoned and rethrown. Any other failure, including a thrown
 * error, tears the attempt down and is rethrown.
 */
class ConnectionSimulatorConnectFaultTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-simulator-fault", ".db").also { it.delete() }
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
    fun aCancelledSimulatorConnectIsAbandonedAndRethrown() = runBlocking {
        val stopped = CancellationException("stopped")
        val manager = manager(stopped)

        val thrown = assertFailsWith<CancellationException> { manager.simulatorConnect() }

        assertSame(stopped, thrown)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertNull(manager.services)
    }

    @Test
    fun aSimulatorConnectErrorTearsDownAndRethrows() = runBlocking {
        val probe = SimulatorConnectProbeError()
        val manager = manager(probe)

        val thrown = assertFailsWith<SimulatorConnectProbeError> { manager.simulatorConnect() }

        assertSame(probe, thrown)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertNull(manager.services)
    }

    private fun manager(failure: Throwable) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = { throw failure },
        nowEpochSeconds = { NOW_SECONDS },
    )

    private companion object {
        const val NOW_SECONDS = 1_700_000_000.0
    }
}

private class SimulatorConnectProbeError : Error("probe")
