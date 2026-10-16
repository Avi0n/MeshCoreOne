package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeLoginFaultTest {
    @Test
    fun aPersistErrorCompletesTheSuccessfulLogin() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val dto = testSession()
        val fault = LoginPersistProbeFault()
        val service = loginService(dto, recorded) { throw fault }

        var result: LoginResult? = null
        val job = launch { result = service.login(dto.id, password = "pw") }
        runCurrent()
        service.handleLoginResult(successResult(dto), dto.publicKey.copyOf(PUBLIC_KEY_PREFIX_LENGTH))
        runCurrent()
        job.join()

        assertEquals(true, result?.success)
        assertTrue(result?.isAdmin == true)
        val line = recorded.single { it.message.contains("handleLoginResult: failed to update session state") }
        assertTrue(line.message.contains("probe"))
    }

    @Test
    fun aCancelledPersistRethrowsAndCancelsTheWaitingLogin() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val dto = testSession()
        val cancelled = CancellationException("superseded")
        val service = loginService(dto, recorded) {
            throw cancelled
        }

        var result: LoginResult? = null
        var callerCancellation: CancellationException? = null
        val job = launch {
            try {
                result = service.login(dto.id, password = "pw")
            } catch (error: CancellationException) {
                callerCancellation = error
                throw error
            }
        }
        runCurrent()
        val pending = service.mutex.withLock {
            requireNotNull(service.pendingLogins[dto.publicKey.take(PUBLIC_KEY_PREFIX_LENGTH)])
        }
        val thrown = assertFailsWith<CancellationException> {
            service.handleLoginResult(successResult(dto), dto.publicKey.copyOf(PUBLIC_KEY_PREFIX_LENGTH))
        }
        runCurrent()

        assertSame(cancelled, thrown)
        assertSame(cancelled, pending.deferred.getCompletionExceptionOrNull())
        assertEquals(cancelled::class, requireNotNull(callerCancellation)::class)
        assertSame(cancelled, callerCancellation?.cause ?: callerCancellation)
        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertEquals(null, result)
        assertEquals(dto, service.store.fetchSessionById(dto.id))
        assertEquals(0, service.pendingLoginCount())
        service.mutex.withLock { assertTrue(service.pendingLoginTimeoutJobs.isEmpty()) }
        assertTrue(recorded.none { it.message.contains("failed to update session state") })
        job.join()
    }

    private suspend fun TestScope.loginService(
        dto: RemoteNodeSessionDto,
        recorded: MutableList<DebugLogEntryDto>,
        onUpdate: suspend () -> Unit,
    ): RemoteNodeService {
        val inner = InMemoryRemoteNodeStore()
        inner.saveSession(dto)
        val store = object : RemoteNodeStore by inner {
            override suspend fun updateSessionConnection(
                id: Uuid,
                isConnected: Boolean,
                permissionLevel: RoomPermissionLevel,
            ) {
                onUpdate()
            }
        }
        return RemoteNodeService(
            FakeNodeSession(),
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(backgroundScope, category = "RemoteNodeService", record = { recorded += it }),
        )
    }
}

private fun successResult(dto: RemoteNodeSessionDto) = LoginResult(
    success = true,
    isAdmin = true,
    aclPermissions = null,
    publicKeyPrefix = dto.publicKey.copyOf(PUBLIC_KEY_PREFIX_LENGTH),
)

private class LoginPersistProbeFault : Error("probe")
