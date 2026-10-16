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
 * A failed password clear is logged and the stored credential stays. Cancellation keeps the thrown
 * instance and does not log. An error is logged the same way and does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeCliPasswordClearFaultTest {
    @Test
    fun aCancelledPasswordClearKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness(stopped)

        val thrown = assertFailsWith<CancellationException> {
            harness.cli.handlePasswordChangeIfNeeded(PASSWORD_COMMAND, harness.sessionID, harness.publicKey)
        }

        assertSame(stopped, thrown)
        assertTrue(harness.logs.none { it.message.contains(CLEAR_FAILED) || it.message.contains(CLEAR_OK) })
        assertTrue(harness.passwords.hasPassword(harness.publicKey))
    }

    @Test
    fun aPasswordClearFailureIsLoggedAndKeepsTheCredential() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(PasswordClearFailure())

        harness.cli.handlePasswordChangeIfNeeded(PASSWORD_COMMAND, harness.sessionID, harness.publicKey)

        assertLogged(harness, "radio")
    }

    @Test
    fun aPasswordClearErrorIsLoggedAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(PasswordClearProbeError())

        harness.cli.handlePasswordChangeIfNeeded(PASSWORD_COMMAND, harness.sessionID, harness.publicKey)

        assertLogged(harness, "probe")
    }

    private suspend fun assertLogged(harness: PasswordClearHarness, detail: String) {
        val line = harness.logs.single()
        assertEquals(failedToClearStoredPasswordLine(harness.sessionID, detail), line.message)
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(harness.passwords.hasPassword(harness.publicKey))
    }

    private suspend fun TestScope.harness(failure: Throwable): PasswordClearHarness {
        val logs = mutableListOf<DebugLogEntryDto>()
        val session = testSession()
        val passwords = InMemoryNodePasswordStore()
        passwords.storePassword(session.publicKey, "old-pw")
        val cli = NodeCliCoordinator(
            FakeNodeSession(),
            InMemoryRemoteNodeStore(),
            PasswordClearStore(passwords, failure),
            backgroundScope,
            logger = PersistentLogger(this, category = "RemoteNode", record = { logs += it }),
        )
        return PasswordClearHarness(cli, passwords, logs, session.id, session.publicKey)
    }
}

private class PasswordClearStore(private val backing: InMemoryNodePasswordStore, private val failure: Throwable) :
    NodePasswordStore by backing {
    override suspend fun deletePassword(publicKey: ByteArray) = throw failure
}

private class PasswordClearHarness(
    val cli: NodeCliCoordinator,
    val passwords: InMemoryNodePasswordStore,
    val logs: List<DebugLogEntryDto>,
    val sessionID: Uuid,
    val publicKey: ByteArray,
)

private const val PASSWORD_COMMAND: String = "password newsecret"
private const val CLEAR_FAILED: String = "Failed to clear stored password"
private const val CLEAR_OK: String = "Cleared stored password"

private class PasswordClearFailure : Exception("radio")

private class PasswordClearProbeError : Error("probe")
