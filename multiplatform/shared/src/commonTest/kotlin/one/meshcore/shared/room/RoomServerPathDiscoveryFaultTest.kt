package one.meshcore.shared.room

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.testUuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A path-discovery send that fails is logged and discovery stops. Cancellation keeps the thrown
 * instance and does not log that failure. An error is logged the same way and does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomServerPathDiscoveryFaultTest {
    @Test
    fun aCancelledPathDiscoveryKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness(stopped)

        val thrown = assertFailsWith<CancellationException> { harness.service.syncHistoryIfPossible(SESSION_ID) }

        assertSame(stopped, thrown)
        assertTrue(harness.logs.none { it.message.contains(PATH_DISCOVERY_FAILED) })
    }

    @Test
    fun aPathDiscoveryFailureIsLoggedAndDiscoveryStops() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(RoomPathDiscoveryFailure())

        harness.service.syncHistoryIfPossible(SESSION_ID)

        assertTrue(harness.logs.any { it.message == "$PATH_DISCOVERY_FAILED: radio" })
        assertTrue(harness.logs.none { it.message.contains(HISTORY_SYNC_FAILED) })
    }

    @Test
    fun aPathDiscoveryErrorIsLoggedAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(RoomPathDiscoveryProbeError())

        harness.service.syncHistoryIfPossible(SESSION_ID)

        assertTrue(harness.logs.any { it.message == "$PATH_DISCOVERY_FAILED: probe" })
        assertTrue(harness.logs.none { it.message.contains(HISTORY_SYNC_FAILED) })
    }

    private suspend fun TestScope.harness(discoveryFailure: Throwable): PathDiscoveryHarness {
        val store = InMemoryRoomStore()
        val sessionRow = roomSession(id = SESSION_ID)
        store.seedSession(sessionRow)
        store.setContactRouting(sessionRow.radioID, sessionRow.publicKey, isFloodRouted = true)
        val room = FakeRoomServerSession()
        room.pathDiscoveryError = discoveryFailure
        val passwords = InMemoryNodePasswordStore()
        val logs = mutableListOf<DebugLogEntryDto>()
        val logger = PersistentLogger(this, category = "RoomServerService", record = { logs += it })
        val service = RoomServerService(
            remoteNodeService = RemoteNodeService(FakeNodeSession(), store, passwords, backgroundScope),
            store = store,
            roomSession = room,
            passwordStore = passwords,
            scope = backgroundScope,
            edges = RoomServerEdges(logger = logger),
        )
        return PathDiscoveryHarness(service, logs)
    }
}

private class PathDiscoveryHarness(val service: RoomServerService, val logs: List<DebugLogEntryDto>)

private class RoomPathDiscoveryFailure : Exception("radio")

private class RoomPathDiscoveryProbeError : Error("probe")

private val SESSION_ID: Uuid = testUuid(1)
private const val PATH_DISCOVERY_FAILED = "Path discovery send failed"
private const val HISTORY_SYNC_FAILED = "Failed to sync history for session"
