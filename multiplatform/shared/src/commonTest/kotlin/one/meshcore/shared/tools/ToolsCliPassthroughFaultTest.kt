package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.CLIOutputType
import one.meshcore.shared.remotenode.RemoteNodeError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * A remote command prints the command-timeout copy for a radio timeout and the failure message
 * otherwise. Cancellation leaves the transcript. A reboot timeout still reports success.
 */
class ToolsCliPassthroughFaultTest {
    @Test
    fun aCancelledRemoteCommandPropagatesAndLeavesTheTranscript() = runTest {
        val cancelled = CancellationException("stopped")
        val bridge = PassthroughFaultBridge(failure = cancelled)
        val session = session(bridge)
        val before = session.outputLines.size

        val thrown = assertFailsWith<CancellationException> {
            session.passthroughRemote(remoteSession(), "ver")
        }

        assertSame(cancelled, thrown)
        assertEquals(before, session.outputLines.size)
        assertFalse(session.isWaitingForResponse)
        assertEquals(listOf("ver"), bridge.commands)
    }

    @Test
    fun aCommandTimeoutPrintsTheCommandTimeoutCopy() = runTest {
        val session = session(PassthroughFaultBridge(failure = RemoteNodeError.Timeout))

        session.passthroughRemote(remoteSession(), "ver")

        assertEquals(listOf(COMMAND_TIMEOUT_COPY), errorLines(session))
        assertFalse(session.isWaitingForResponse)
    }

    @Test
    fun anotherFailurePrintsItsMessage() = runTest {
        val session = session(PassthroughFaultBridge(failure = Exception("radio busy")))

        session.passthroughRemote(remoteSession(), "ver")

        assertEquals(listOf("radio busy"), errorLines(session))
        assertFalse(session.isWaitingForResponse)
    }

    @Test
    fun aMissingFailureMessagePrintsTheTimeoutCopy() = runTest {
        val session = session(PassthroughFaultBridge(failure = Exception()))

        session.passthroughRemote(remoteSession(), "ver")

        assertEquals(listOf("Timeout"), errorLines(session))
    }

    @Test
    fun aBlankFailureMessageStaysBlank() = runTest {
        val session = session(PassthroughFaultBridge(failure = Exception("")))

        session.passthroughRemote(remoteSession(), "ver")

        assertEquals(listOf(""), errorLines(session))
    }

    @Test
    fun aCommandErrorIsPrintedAndDoesNotEscape() = runTest {
        val session = session(PassthroughFaultBridge(failure = PassthroughProbeError()))

        session.passthroughRemote(remoteSession(), "ver")

        assertEquals(listOf("probe"), errorLines(session))
        assertFalse(session.isWaitingForResponse)
    }

    @Test
    fun aRebootTimeoutStillReportsSuccess() = runTest {
        val bridge = PassthroughFaultBridge(rebootFailure = RemoteNodeError.Timeout)
        val session = session(bridge)

        session.passthroughRemote(remoteSession(), "reboot")

        assertEquals(emptyList(), errorLines(session))
        assertEquals(listOf("Reboot command sent"), successLines(session))
        assertEquals(listOf("reboot"), bridge.commands)
        assertFalse(session.isWaitingForResponse)
    }

    @Test
    fun aCancelledRebootPropagatesAndDoesNotReportSuccess() = runTest {
        val cancelled = CancellationException("stopped")
        val session = session(PassthroughFaultBridge(rebootFailure = cancelled))
        val before = session.outputLines.size

        val thrown = assertFailsWith<CancellationException> {
            session.passthroughRemote(remoteSession(), "Reboot")
        }

        assertSame(cancelled, thrown)
        assertEquals(before, session.outputLines.size)
        assertFalse(session.isWaitingForResponse)
    }

    @Test
    fun aRebootFailurePrintsItsMessageInsteadOfSuccess() = runTest {
        val session = session(PassthroughFaultBridge(rebootFailure = Exception("link down")))

        session.passthroughRemote(remoteSession(), "reboot now")

        assertEquals(listOf("link down"), errorLines(session))
        assertTrue(successLines(session).isEmpty())
        assertFalse(session.isWaitingForResponse)
    }

    private fun session(bridge: ToolsCliRemoteBridge) = ToolsCliSession(
        deviceName = "Radio",
        messages = toolsCliTestMessages().copy(commandTimeout = COMMAND_TIMEOUT_COPY),
        fetchContacts = { emptyList() },
        fetchChannels = { emptyList() },
        options = ToolsCliSession.Options(remote = bridge),
    )

    private fun remoteSession() = ToolsCliSession.RemoteCliSession(
        id = PASSTHROUGH_SESSION_ID,
        name = "R1",
        outPathLength = 0,
    )

    private fun errorLines(session: ToolsCliSession) =
        session.outputLines.filter { it.type == CLIOutputType.ERROR }.map { it.text }

    private fun successLines(session: ToolsCliSession) =
        session.outputLines.filter { it.type == CLIOutputType.SUCCESS }.map { it.text }
}

private class PassthroughProbeError : Error("probe")

private const val COMMAND_TIMEOUT_COPY = "command budget elapsed"

private val PASSTHROUGH_SESSION_ID = Uuid.normalized("B0000000-0000-0000-0000-0000000000AB")

private class PassthroughFaultBridge(
    private val failure: Throwable? = null,
    private val rebootFailure: Throwable? = null,
) : ToolsCliRemoteBridge {
    val commands = mutableListOf<String>()

    override suspend fun radioId(): Uuid = error("unused")

    override suspend fun retrievePassword(publicKey: ByteArray): String = error("unused")

    override suspend fun deletePassword(publicKey: ByteArray) = Unit

    override suspend fun storePassword(password: String, publicKey: ByteArray) = Unit

    override suspend fun createSession(radioID: Uuid, contact: ContactDto): Uuid = error("unused")

    override suspend fun login(
        sessionID: Uuid,
        password: String,
        pathLength: Int,
        onTimeoutKnown: (suspend (Int) -> Unit)?,
    ): Boolean = error("unused")

    override suspend fun logout(sessionID: Uuid) = Unit

    override suspend fun sendRawCommand(sessionID: Uuid, command: String, timeout: Duration?): String {
        commands += command
        val thrown = if (command.lowercase().startsWith("reboot")) rebootFailure else failure
        if (thrown != null) throw thrown
        return "ver 1.0"
    }
}
