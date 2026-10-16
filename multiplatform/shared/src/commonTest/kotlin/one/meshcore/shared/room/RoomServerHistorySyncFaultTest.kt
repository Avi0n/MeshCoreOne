package one.meshcore.shared.room

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RemoteNodeSessionDto
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
 * A history sync that cannot read its session logs the failure and returns. Cancellation keeps the
 * thrown instance and does not log that failure. An error is logged the same way and does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomServerHistorySyncFaultTest {
    @Test
    fun aCancelledSessionReadKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness(stopped)

        val thrown = assertFailsWith<CancellationException> { harness.service.syncHistoryIfPossible(SESSION_ID) }

        assertSame(stopped, thrown)
        assertTrue(harness.logs.none { it.message.contains(HISTORY_SYNC_FAILED) })
    }

    @Test
    fun aSessionReadFailureIsLoggedAndTheSyncReturns() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(RoomHistorySyncFailure())

        harness.service.syncHistoryIfPossible(SESSION_ID)

        assertTrue(harness.logs.any { it.message == "$HISTORY_SYNC_FAILED $SESSION_ID: radio" })
    }

    @Test
    fun aSessionReadErrorIsLoggedAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(RoomHistorySyncProbeError())

        harness.service.syncHistoryIfPossible(SESSION_ID)

        assertTrue(harness.logs.any { it.message == "$HISTORY_SYNC_FAILED $SESSION_ID: probe" })
    }

    private fun TestScope.harness(readFailure: Throwable): HistorySyncHarness {
        val inner = InMemoryRoomStore()
        val store = RoomHistorySyncStore(inner, readFailure)
        val passwords = InMemoryNodePasswordStore()
        val logs = mutableListOf<DebugLogEntryDto>()
        val logger = PersistentLogger(this, category = "RoomServerService", record = { logs += it })
        val service = RoomServerService(
            remoteNodeService = RemoteNodeService(FakeNodeSession(), inner, passwords, backgroundScope),
            store = store,
            roomSession = FakeRoomServerSession(),
            passwordStore = passwords,
            scope = backgroundScope,
            edges = RoomServerEdges(logger = logger),
        )
        return HistorySyncHarness(service, logs)
    }
}

private class HistorySyncHarness(val service: RoomServerService, val logs: List<DebugLogEntryDto>)

private class RoomHistorySyncFailure : Exception("radio")

private class RoomHistorySyncProbeError : Error("probe")

private class RoomHistorySyncStore(private val backing: InMemoryRoomStore, private val readFailure: Throwable) :
    RoomStore by backing {
    override suspend fun fetchSession(id: Uuid): RemoteNodeSessionDto? = throw readFailure
}

private val SESSION_ID: Uuid = testUuid(1)
private const val HISTORY_SYNC_FAILED = "Failed to sync history for session"
