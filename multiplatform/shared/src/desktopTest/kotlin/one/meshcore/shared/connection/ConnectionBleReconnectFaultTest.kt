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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A cancelled BLE reconnect attempt is rethrown and leaves the new link up. Any other failure,
 * including a thrown error, is recorded as a miss and tears that link down.
 */
class ConnectionBleReconnectFaultTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-ble-reconnect-fault", ".db").also { it.delete() }
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
    fun aCancelledReconnectAttemptLeavesTheNewLinkUp() = runBlocking {
        val stopped = CancellationException("stopped")
        val link = FakeMeshLink().apply { failConnectWith = stopped }
        val manager = manager(link)

        val thrown = assertFailsWith<CancellationException> {
            manager.advanceBleReconnect(
                DEVICE_HANDLE,
                iteration = 0,
                connectFailures = 0,
                encryptionTimeoutFailures = 0,
            )
        }

        assertSame(stopped, thrown)
        assertEquals(1, link.connectCount)
        assertFalse(link.disconnected)
        assertSame(link, manager.link)
        assertEquals(ConnectionState.Reconnecting(DEVICE_HANDLE, attempt = 1), manager.state.value)
    }

    @Test
    fun aReconnectErrorIsRecordedAsAMiss() = runBlocking {
        val link = FakeMeshLink().apply { failConnectWith = BleReconnectProbeError() }
        val manager = manager(link)

        val step = manager.advanceBleReconnect(
            DEVICE_HANDLE,
            iteration = 0,
            connectFailures = 0,
            encryptionTimeoutFailures = 0,
        )

        assertFalse(step.finished)
        assertEquals(1, step.iteration)
        assertEquals(1, step.connectFailures)
        assertEquals(0, step.encryptionTimeoutFailures)
        assertEquals(1, link.connectCount)
        assertTrue(link.disconnected)
        assertNull(manager.link)
    }

    private fun manager(link: FakeMeshLink) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { link },
        sessionFactory = { FakeMeshSession() },
        nowEpochSeconds = { NOW_SECONDS },
    )

    private companion object {
        const val DEVICE_HANDLE = "AA:BB:CC:DD:EE:71"
        const val NOW_SECONDS = 1_700_000_000.0
    }
}

private class BleReconnectProbeError : Error("probe")
