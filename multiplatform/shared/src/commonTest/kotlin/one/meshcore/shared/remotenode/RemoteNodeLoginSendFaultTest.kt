package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RemoteNodeSessionDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * A login send that fails completes the pending login with the mapped session error and skips the wait.
 * Cancellation keeps the thrown instance and leaves that login unfinished.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeLoginSendFaultTest {
    @Test
    fun aCancelledLoginSendKeepsTheThrownInstance() = runTest {
        val stopped = CancellationException("stopped")
        val dto = testSession()
        val radio = FakeNodeSession().apply { sendLoginError = stopped }
        val service = RemoteNodeService(radio, InMemoryRemoteNodeStore(), InMemoryNodePasswordStore(), backgroundScope)
        val prefix = dto.publicKey.take(PUBLIC_KEY_PREFIX_LENGTH)
        val pending = CompletableDeferred<LoginResult>()
        service.mutex.withLock { service.pendingLogins[prefix] = PendingLogin(dto.id, pending) }
        var timeoutKnown = false

        val thrown = assertFailsWith<CancellationException> {
            service.runLoginSendAndTimeout(loginAttempt(dto, prefix) { timeoutKnown = true })
        }

        assertSame(stopped, thrown)
        assertFalse(pending.isCompleted)
        assertSame(pending, service.mutex.withLock { service.pendingLogins[prefix]?.deferred })
        assertFalse(timeoutKnown)
        assertEquals(1, radio.sendLoginCalls.size)
    }

    @Test
    fun aLoginSendFailureCompletesThePendingLogin() = runTest {
        val outcome = loginFailure(LoginSendFailure())

        assertEquals(RemoteNodeError.SessionError(MeshError.ConnectionLost("radio")), outcome.error)
        assertFalse(outcome.timeoutKnown)
        assertEquals(1, outcome.sendCount)
        assertEquals(0, outcome.pendingCount)
    }

    @Test
    fun aLoginSendErrorIsMappedAndDoesNotEscape() = runTest {
        val outcome = loginFailure(LoginSendProbeError())

        assertEquals(RemoteNodeError.SessionError(MeshError.ConnectionLost("probe")), outcome.error)
        assertFalse(outcome.timeoutKnown)
        assertEquals(1, outcome.sendCount)
        assertEquals(0, outcome.pendingCount)
    }

    private suspend fun TestScope.loginFailure(failure: Throwable): LoginSendOutcome {
        val radio = FakeNodeSession().apply { sendLoginError = failure }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(radio, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()
        var timeoutKnown = false
        var thrown: Throwable? = null
        val job = launch {
            thrown = runCatching {
                service.login(dto.id, password = "pw", onTimeoutKnown = { timeoutKnown = true })
            }.exceptionOrNull()
        }
        runCurrent()
        job.join()
        return LoginSendOutcome(
            error = thrown,
            timeoutKnown = timeoutKnown,
            sendCount = radio.sendLoginCalls.size,
            pendingCount = service.pendingLoginCount(),
        )
    }
}

private fun loginAttempt(
    dto: RemoteNodeSessionDto,
    prefix: List<Byte>,
    onTimeoutKnown: suspend (Int) -> Unit,
): RemoteNodeService.LoginAttempt = RemoteNodeService.LoginAttempt(
    sessionID = dto.id,
    publicKey = dto.publicKey,
    radioID = dto.radioID,
    prefix = prefix,
    password = "pw",
    pathLength = 0,
    onTimeoutKnown = onTimeoutKnown,
)

private data class LoginSendOutcome(
    val error: Throwable?,
    val timeoutKnown: Boolean,
    val sendCount: Int,
    val pendingCount: Int,
)

private class LoginSendFailure : Exception("radio")

private class LoginSendProbeError : Error("probe")
