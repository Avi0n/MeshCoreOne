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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A re-auth session read that fails is logged and that session is skipped. Cancellation keeps the
 * thrown instance and does not start re-auth. An error is logged the same way and does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeReauthFetchFaultTest {
    @Test
    fun aCancelledReauthFetchKeepsTheThrownInstance() = runTest {
        val stopped = CancellationException("stopped")
        val dto = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        val harness = harness(dto, dto, dto.id) { throw stopped }
        var thrown: Throwable? = null
        val job = launch {
            thrown = runCatching { harness.service.handleBLEReconnection(setOf(dto.id)) }.exceptionOrNull()
        }
        runCurrent()
        job.join()
        runCurrent()

        assertSame(stopped, thrown)
        assertTrue(harness.logs.none { it.message.contains(REAUTH_SKIP) })
        assertTrue(harness.logs.none { it.message.contains(REAUTH_START) })
        assertTrue(harness.store.fetchSessionById(dto.id)!!.isConnected)
    }

    @Test
    fun aReauthFetchFailureSkipsThatSessionAndContinues() = runTest {
        val harness = twoSessions { throw ReauthFetchFailure() }

        val job = launch { harness.service.handleBLEReconnection(setOf(harness.good.id, harness.doomed.id)) }
        runCurrent()
        harness.radio.emitLoginSuccess(testLoginInfo(harness.good.publicKey, isAdmin = true))
        runCurrent()
        job.join()
        runCurrent()

        assertSkippedAndContinued(harness)
    }

    @Test
    fun aReauthFetchErrorIsLoggedAndDoesNotEscape() = runTest {
        val harness = twoSessions { throw ReauthFetchProbeError() }

        val job = launch { harness.service.handleBLEReconnection(setOf(harness.good.id, harness.doomed.id)) }
        runCurrent()
        harness.radio.emitLoginSuccess(testLoginInfo(harness.good.publicKey, isAdmin = true))
        runCurrent()
        job.join()
        runCurrent()

        assertSkippedAndContinued(harness)
    }

    private suspend fun assertSkippedAndContinued(harness: ReauthFetchHarness) {
        assertTrue(harness.logs.any { it.message == "Session ${harness.doomed.id} $REAUTH_SKIP" })
        val started = harness.logs.single { it.message.contains(REAUTH_START) }
        assertTrue(started.message.contains("1 session"))
        assertTrue(harness.store.fetchSessionById(harness.good.id)!!.isConnected)
    }
}

private suspend fun TestScope.twoSessions(onFetch: () -> Nothing): ReauthFetchHarness {
    val good = testSession(id = testUuid(1), isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
    val doomed = testSession(
        id = testUuid(2),
        publicKey = testPublicKey(0x22),
        isConnected = true,
        permissionLevel = RoomPermissionLevel.READ_WRITE,
    )
    return harness(good, doomed, doomed.id, onFetch)
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.harness(
    good: RemoteNodeSessionDto,
    doomed: RemoteNodeSessionDto,
    failingId: Uuid,
    onFetch: () -> Nothing,
): ReauthFetchHarness {
    val logs = mutableListOf<DebugLogEntryDto>()
    val radio = FakeNodeSession()
    val backing = InMemoryRemoteNodeStore()
    val passwords = InMemoryNodePasswordStore()
    backing.saveSession(good)
    if (doomed.id != good.id) backing.saveSession(doomed)
    passwords.storePassword(good.publicKey, "pw")
    val service = RemoteNodeService(
        radio,
        ReauthFetchStore(backing, failingId, onFetch),
        passwords,
        backgroundScope,
        logger = PersistentLogger(backgroundScope, category = "RemoteNodeService", record = { logs += it }),
    )
    runCurrent()
    return ReauthFetchHarness(service, radio, backing, logs, good, doomed)
}

private class ReauthFetchStore(
    private val backing: InMemoryRemoteNodeStore,
    private val failingId: Uuid,
    private val onFetch: () -> Nothing,
) : RemoteNodeStore by backing {
    override suspend fun fetchSessionById(id: Uuid): RemoteNodeSessionDto? =
        if (id == failingId) onFetch() else backing.fetchSessionById(id)
}

private class ReauthFetchHarness(
    val service: RemoteNodeService,
    val radio: FakeNodeSession,
    val store: InMemoryRemoteNodeStore,
    val logs: List<DebugLogEntryDto>,
    val good: RemoteNodeSessionDto,
    val doomed: RemoteNodeSessionDto,
)

private const val REAUTH_SKIP: String = "not found for re-auth, skipping"
private const val REAUTH_START: String = "BLE reconnection: re-authenticating"

private class ReauthFetchFailure : Exception("radio")

private class ReauthFetchProbeError : Error("probe")
