package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
 * A keep-alive tick that fails follows the retry policy. Cancellation keeps the thrown instance and
 * leaves the session connected. An error is disconnected the same way and does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeKeepAliveTickFaultTest {
    @Test
    fun aCancelledKeepAliveTickKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness(stopped)

        val thrown = assertFailsWith<CancellationException> {
            harness.service.performKeepAliveTick(harness.sessionID, harness.publicKey, failures = 0)
        }

        assertSame(stopped, thrown)
        assertTrue(harness.logs.none { it.message.contains(KEEP_ALIVE_FAILED) })
        assertTrue(harness.store.fetchSessionById(harness.sessionID)!!.isConnected)
        assertEquals(1, harness.radio.sendKeepAliveCalls.size)
    }

    @Test
    fun aKeepAliveTickFailureDisconnectsImmediately() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(KeepAliveTickFailure())

        val tick = harness.service.performKeepAliveTick(harness.sessionID, harness.publicKey, failures = 0)

        assertDisconnected(harness, tick, "radio")
    }

    @Test
    fun aKeepAliveTickErrorDisconnectsAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(KeepAliveTickProbeError())

        val tick = harness.service.performKeepAliveTick(harness.sessionID, harness.publicKey, failures = 0)

        assertDisconnected(harness, tick, "probe")
    }

    private suspend fun assertDisconnected(
        harness: KeepAliveTickHarness,
        tick: RemoteNodeService.TickResult,
        detail: String,
    ) {
        assertEquals(RemoteNodeService.TickResult(shouldContinue = false, consecutiveFailures = 0), tick)
        assertTrue(harness.logs.any { it.message == "$KEEP_ALIVE_FAILED ${harness.sessionID}: $detail" })
        assertFalse(harness.store.fetchSessionById(harness.sessionID)!!.isConnected)
        assertEquals(1, harness.radio.sendKeepAliveCalls.size)
    }

    private suspend fun TestScope.harness(failure: Throwable): KeepAliveTickHarness {
        val logs = mutableListOf<DebugLogEntryDto>()
        val radio = FakeNodeSession().apply { keepAliveError = failure }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true)
        store.saveSession(dto)
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = false)
        val service = RemoteNodeService(
            radio,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(this, category = "RemoteNodeService", record = { logs += it }),
        )
        return KeepAliveTickHarness(service, radio, store, logs, dto.id, dto.publicKey)
    }
}

private class KeepAliveTickHarness(
    val service: RemoteNodeService,
    val radio: FakeNodeSession,
    val store: InMemoryRemoteNodeStore,
    val logs: List<DebugLogEntryDto>,
    val sessionID: Uuid,
    val publicKey: ByteArray,
)

private const val KEEP_ALIVE_FAILED: String = "Keep-alive failed for"

private class KeepAliveTickFailure : Exception("radio")

private class KeepAliveTickProbeError : Error("probe")
