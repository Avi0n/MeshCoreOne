package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A login retransmit that fails is logged and the wait continues. Cancellation keeps the thrown
 * instance and does not log that failure. An error is logged the same way and does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeLoginRetransmitFaultTest {
    @Test
    fun aCancelledLoginRetransmitKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness { call -> if (call == RETRANSMIT_CALL) stopped else null }

        val thrown = assertFailsWith<CancellationException> {
            harness.service.runLoginSendAndTimeout(harness.attempt)
        }

        assertSame(stopped, thrown)
        assertTrue(harness.logs.none { it.message.contains(RETRANSMIT_FAILED) })
        assertEquals(RETRANSMIT_CALL, harness.radio.sendLoginCalls.size)
        assertFalse(harness.pending.isCompleted)
    }

    @Test
    fun aLoginRetransmitFailureIsLoggedAndTheWaitContinues() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness { call -> if (call == RETRANSMIT_CALL) LoginRetransmitFailure() else null }

        harness.service.runLoginSendAndTimeout(harness.attempt)

        assertRetransmitLogged(harness, "radio")
    }

    @Test
    fun aLoginRetransmitErrorIsLoggedAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness { call -> if (call == RETRANSMIT_CALL) LoginRetransmitProbeError() else null }

        harness.service.runLoginSendAndTimeout(harness.attempt)

        assertRetransmitLogged(harness, "probe")
    }

    private suspend fun assertRetransmitLogged(harness: RetransmitHarness, detail: String) {
        val hex = harness.service.prefixHex(harness.prefix)
        assertTrue(harness.logs.any { it.message == "$RETRANSMIT_FAILED $hex: $detail" })
        assertTrue(harness.radio.sendLoginCalls.size > RETRANSMIT_CALL)
        assertSame(RemoteNodeError.Timeout, runCatching { harness.pending.await() }.exceptionOrNull())
    }

    private suspend fun TestScope.harness(failureForCall: (Int) -> Throwable?): RetransmitHarness {
        val logs = mutableListOf<DebugLogEntryDto>()
        val radio = FakeNodeSession().apply {
            sentInfo = testSentInfo(suggestedTimeoutMs = SUGGESTED_TIMEOUT_MS)
            sendLoginErrorForCall = failureForCall
        }
        val dto = testSession()
        val service = RemoteNodeService(
            radio,
            InMemoryRemoteNodeStore(),
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(this, category = "RemoteNodeService", record = { logs += it }),
        )
        val prefix = dto.publicKey.take(PUBLIC_KEY_PREFIX_LENGTH)
        val pending = CompletableDeferred<LoginResult>()
        service.mutex.withLock { service.pendingLogins[prefix] = PendingLogin(dto.id, pending) }
        return RetransmitHarness(service, radio, logs, pending, prefix, loginAttempt(dto, prefix))
    }
}

private fun loginAttempt(dto: RemoteNodeSessionDto, prefix: List<Byte>): RemoteNodeService.LoginAttempt =
    RemoteNodeService.LoginAttempt(
        sessionID = dto.id,
        publicKey = dto.publicKey,
        radioID = dto.radioID,
        prefix = prefix,
        password = "pw",
        pathLength = 0,
        onTimeoutKnown = null,
    )

private class RetransmitHarness(
    val service: RemoteNodeService,
    val radio: FakeNodeSession,
    val logs: List<DebugLogEntryDto>,
    val pending: CompletableDeferred<LoginResult>,
    val prefix: List<Byte>,
    val attempt: RemoteNodeService.LoginAttempt,
)

private const val RETRANSMIT_CALL: Int = 2
private const val SUGGESTED_TIMEOUT_MS: Long = 1000
private const val RETRANSMIT_FAILED: String = "login: retransmit #1 failed for"

private class LoginRetransmitFailure : Exception("radio")

private class LoginRetransmitProbeError : Error("probe")
