package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A BLE-loss session read. A store fault is logged and tears nothing down. Cancellation leaves the
 * connected row alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedSessionsFetchTest {
    @Test
    fun aStoreFaultLogsAndLeavesTheSessionConnected() = runTest {
        val failure = IllegalStateException("db locked")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val (service, connected) = serviceThatFailsFetch(failure, recorded)

        val ids = service.handleBLEDisconnection()
        runCurrent()

        assertTrue(ids.isEmpty())
        assertTrue(service.store.fetchSessionById(connected.id)!!.isConnected)
        val logged = recorded.single { it.level == DebugLogLevel.ERROR }
        assertTrue(logged.message.contains("Failed to fetch connected sessions for BLE disconnection: db locked"))
    }

    @Test
    fun aFetchErrorLogsAndLeavesTheSessionConnected() = runTest {
        val fault = ConnectedSessionsProbeError()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val (service, connected) = serviceThatFailsFetch(fault, recorded)

        val ids = service.handleBLEDisconnection()
        runCurrent()

        assertTrue(ids.isEmpty())
        assertTrue(service.store.fetchSessionById(connected.id)!!.isConnected)
        assertTrue(recorded.single().message.contains("probe"))
    }

    @Test
    fun aCancelledFetchPropagatesAndLeavesTheSessionConnected() = runTest {
        val cancelled = CancellationException("stopped")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val (service, connected) = serviceThatFailsFetch(cancelled, recorded)

        val thrown = assertFailsWith<CancellationException> {
            service.handleBLEDisconnection()
        }
        runCurrent()

        assertSame(cancelled, thrown)
        assertTrue(service.store.fetchSessionById(connected.id)!!.isConnected)
        assertEquals(emptyList<DebugLogEntryDto>(), recorded)
    }

    private suspend fun TestScope.serviceThatFailsFetch(
        failure: Throwable,
        recorded: MutableList<DebugLogEntryDto>,
    ): Pair<RemoteNodeService, RemoteNodeSessionDto> {
        val inner = InMemoryRemoteNodeStore()
        val connected = testSession(id = testUuid(1), isConnected = true)
        inner.saveSession(connected)
        val store = object : RemoteNodeStore by inner {
            override suspend fun fetchConnectedSessions(): List<RemoteNodeSessionDto> = throw failure
        }
        val service = RemoteNodeService(
            FakeNodeSession(),
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )
        return service to connected
    }
}

private class ConnectedSessionsProbeError : Error("probe")
