package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed disconnect write is logged and the session stays connected. Cancellation keeps the thrown
 * instance and does not log. An error is logged the same way and does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodePersistDisconnectedFaultTest {
    @Test
    fun aCancelledDisconnectWriteKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness(stopped)

        val thrown = assertFailsWith<CancellationException> {
            harness.service.persistDisconnected(harness.sessionID)
        }

        assertSame(stopped, thrown)
        assertTrue(harness.logs.none { it.message.contains(PERSIST_FAILED) || it.message.contains(MARK_FAILED) })
        assertTrue(harness.store.fetchSessionById(harness.sessionID)!!.isConnected)
    }

    @Test
    fun aDisconnectWriteFailureLogsAndLeavesTheSessionConnected() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(PersistDisconnectedFailure())

        harness.service.persistDisconnected(harness.sessionID)

        assertLogged(harness, "$PERSIST_FAILED ${harness.sessionID}: radio")
    }

    @Test
    fun aDisconnectWriteErrorIsLoggedAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(PersistDisconnectedProbeError())

        harness.service.persistDisconnected(harness.sessionID, markSessionLine = true)

        assertLogged(harness, "$MARK_FAILED ${harness.sessionID} disconnected: probe")
    }

    private suspend fun assertLogged(harness: PersistDisconnectedHarness, message: String) {
        val line = harness.logs.single()
        assertEquals(message, line.message)
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(harness.store.fetchSessionById(harness.sessionID)!!.isConnected)
    }

    private suspend fun TestScope.harness(failure: Throwable): PersistDisconnectedHarness {
        val logs = mutableListOf<DebugLogEntryDto>()
        val backing = InMemoryRemoteNodeStore()
        val session = testSession(isConnected = true)
        backing.saveSession(session)
        val service = RemoteNodeService(
            FakeNodeSession(),
            PersistDisconnectedStore(backing, failure),
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(this, category = "RemoteNodeService", record = { logs += it }),
        )
        return PersistDisconnectedHarness(service, backing, logs, session.id)
    }
}

private class PersistDisconnectedStore(private val backing: InMemoryRemoteNodeStore, private val failure: Throwable) :
    RemoteNodeStore by backing {
    override suspend fun markSessionDisconnected(id: Uuid) = throw failure
}

private class PersistDisconnectedHarness(
    val service: RemoteNodeService,
    val store: InMemoryRemoteNodeStore,
    val logs: List<DebugLogEntryDto>,
    val sessionID: Uuid,
)

private const val PERSIST_FAILED: String = "Failed to persist disconnected state for session"
private const val MARK_FAILED: String = "Failed to mark session"

private class PersistDisconnectedFailure : Exception("radio")

private class PersistDisconnectedProbeError : Error("probe")
