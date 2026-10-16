package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
 * A logout send that fails is logged and the session still downgrades to guest. Cancellation keeps the
 * thrown instance, skips that log, and leaves the session connected. An error is logged the same way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeLogoutSendFaultTest {
    @Test
    fun aCancelledLogoutSendKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness(stopped)

        val thrown = assertFailsWith<CancellationException> { harness.service.logout(harness.sessionID) }

        assertSame(stopped, thrown)
        assertTrue(harness.logs.none { it.message.contains(LOGOUT_SEND_FAILED) })
        val updated = harness.store.fetchSessionById(harness.sessionID)!!
        assertTrue(updated.isConnected)
        assertEquals(RoomPermissionLevel.ADMIN, updated.permissionLevel)
        assertEquals(1, harness.radio.sendLogoutCalls.size)
    }

    @Test
    fun aLogoutSendFailureIsLoggedAndTheSessionDowngrades() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(LogoutSendFailure())

        harness.service.logout(harness.sessionID)

        assertDowngraded(harness, "radio")
    }

    @Test
    fun aLogoutSendErrorIsLoggedAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(LogoutSendProbeError())

        harness.service.logout(harness.sessionID)

        assertDowngraded(harness, "probe")
    }

    private suspend fun assertDowngraded(harness: LogoutHarness, detail: String) {
        assertTrue(harness.logs.any { it.message == logoutSendFailedLine(detail) })
        val updated = harness.store.fetchSessionById(harness.sessionID)!!
        assertFalse(updated.isConnected)
        assertEquals(RoomPermissionLevel.GUEST, updated.permissionLevel)
        assertEquals(1, harness.radio.sendLogoutCalls.size)
    }

    private suspend fun TestScope.harness(failure: Throwable): LogoutHarness {
        val logs = mutableListOf<DebugLogEntryDto>()
        val radio = FakeNodeSession().apply { sendLogoutError = failure }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveSession(dto)
        val service = RemoteNodeService(
            radio,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(this, category = "RemoteNodeService", record = { logs += it }),
        )
        return LogoutHarness(service, radio, store, logs, dto.id)
    }
}

private class LogoutHarness(
    val service: RemoteNodeService,
    val radio: FakeNodeSession,
    val store: InMemoryRemoteNodeStore,
    val logs: List<DebugLogEntryDto>,
    val sessionID: Uuid,
)

private const val LOGOUT_SEND_FAILED: String = "Logout send failed"

private class LogoutSendFailure : Exception("radio")

private class LogoutSendProbeError : Error("probe")
