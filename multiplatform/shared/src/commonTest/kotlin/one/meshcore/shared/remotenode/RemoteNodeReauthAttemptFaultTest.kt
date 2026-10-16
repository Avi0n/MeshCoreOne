package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A re-auth that fails is logged and the session is marked disconnected. Cancellation keeps the
 * thrown instance and leaves the session connected. An error is logged the same way and does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeReauthAttemptFaultTest {
    @Test
    fun aCancelledReauthKeepsTheThrownInstance() = runTest {
        val stopped = CancellationException("stopped")
        val harness = harness(stopped)

        val thrown = assertFailsWith<CancellationException> {
            harness.service.reauthenticate(harness.session)
        }
        runCurrent()

        assertSame(stopped, thrown)
        assertTrue(harness.logs.none { it.message.contains(REAUTH_FAILED) })
        assertTrue(harness.store.fetchSessionById(harness.session.id)!!.isConnected)
        assertTrue(harness.events.none { it is RemoteNodeEvent.SessionStateChanged && !it.isConnected })
    }

    @Test
    fun aReauthFailureDisconnectsThatSession() = runTest {
        val harness = harness(ReauthAttemptFailure())

        harness.service.reauthenticate(harness.session)

        assertDisconnected(harness, "radio")
    }

    @Test
    fun aReauthErrorIsLoggedAndDoesNotEscape() = runTest {
        val harness = harness(ReauthAttemptProbeError())

        harness.service.reauthenticate(harness.session)

        assertDisconnected(harness, "probe")
    }

    private suspend fun TestScope.assertDisconnected(harness: ReauthAttemptHarness, detail: String) {
        runCurrent()
        assertEquals("$REAUTH_FAILED ${harness.session.id}: $detail", harness.logs.single().message)
        assertFalse(harness.store.fetchSessionById(harness.session.id)!!.isConnected)
        val disconnected = harness.events.filterIsInstance<RemoteNodeEvent.SessionStateChanged>()
        assertEquals(listOf(harness.session.id), disconnected.map { it.sessionID })
        assertTrue(disconnected.all { !it.isConnected })
    }

    private suspend fun TestScope.harness(failure: Throwable): ReauthAttemptHarness {
        val logs = mutableListOf<DebugLogEntryDto>()
        val events = mutableListOf<RemoteNodeEvent>()
        val backing = InMemoryRemoteNodeStore()
        val session = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        backing.saveSession(session)
        val service = RemoteNodeService(
            FakeNodeSession(),
            ReauthAttemptStore(backing, failure),
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(backgroundScope, category = "RemoteNodeService", record = { logs += it }),
        )
        backgroundScope.launch { service.events.collect { events += it } }
        runCurrent()
        return ReauthAttemptHarness(service, backing, logs, events, session)
    }
}

private class ReauthAttemptStore(private val backing: InMemoryRemoteNodeStore, private val failure: Throwable) :
    RemoteNodeStore by backing {
    override suspend fun fetchSessionById(id: Uuid): RemoteNodeSessionDto? = throw failure
}

private class ReauthAttemptHarness(
    val service: RemoteNodeService,
    val store: InMemoryRemoteNodeStore,
    val logs: List<DebugLogEntryDto>,
    val events: List<RemoteNodeEvent>,
    val session: RemoteNodeSessionDto,
)

private const val REAUTH_FAILED: String = "Re-auth failed for session"

private class ReauthAttemptFailure : Exception("radio")

private class ReauthAttemptProbeError : Error("probe")
