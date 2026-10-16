package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.CLIOutputType
import one.meshcore.shared.remotenode.RemoteNodeError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * A stored remote login prints the bridge failure and keeps no session. Cancellation leaves the
 * transcript. An error is printed the same way and does not escape.
 */
class ToolsCliLoginFaultTest {
    @Test
    fun aCancelledStoredLoginPropagatesAndLeavesTheTranscript() = runTest {
        val cancelled = CancellationException("stopped")
        val bridge = LoginFaultBridge(cancelled)
        val session = session(bridge)
        val before = session.outputLines.size

        val thrown = assertFailsWith<CancellationException> {
            session.completeLogin(repeater(), "secret")
        }

        assertSame(cancelled, thrown)
        assertEquals(before, session.outputLines.size)
        assertFalse(session.isWaitingForResponse)
        assertNull(session.remainingSeconds)
        assertTrue(session.remoteSessions.isEmpty())
        assertNull(session.activeRemote)
        assertEquals(0, bridge.storedPasswords)
    }

    @Test
    fun aLoginTimeoutPrintsTheFailureAndKeepsNoSession() = runTest {
        val bridge = LoginFaultBridge(RemoteNodeError.Timeout)
        val session = session(bridge)

        session.completeLogin(repeater(), "secret")

        assertEquals(listOf("Login failed: Request timed out"), errorLines(session))
        assertFalse(session.isWaitingForResponse)
        assertNull(session.remainingSeconds)
        assertTrue(session.remoteSessions.isEmpty())
        assertNull(session.activeRemote)
        assertEquals(0, bridge.storedPasswords)
    }

    @Test
    fun aBlankLoginFailurePrintsTheTimeoutCopy() = runTest {
        val session = session(LoginFaultBridge(Exception()))

        session.completeLogin(repeater(), "secret")

        assertEquals(listOf("Login failed: Timeout"), errorLines(session))
        assertFalse(session.isWaitingForResponse)
        assertNull(session.remainingSeconds)
    }

    @Test
    fun aLoginErrorIsPrintedAndDoesNotEscape() = runTest {
        val session = session(LoginFaultBridge(StoredLoginProbeError()))

        session.completeLogin(repeater(), "secret")

        assertEquals(listOf("Login failed: probe"), errorLines(session))
        assertFalse(session.isWaitingForResponse)
        assertNull(session.remainingSeconds)
        assertTrue(session.remoteSessions.isEmpty())
    }

    private fun session(bridge: ToolsCliRemoteBridge) = ToolsCliSession(
        deviceName = "Radio",
        messages = toolsCliTestMessages(),
        fetchContacts = { emptyList() },
        fetchChannels = { emptyList() },
        options = ToolsCliSession.Options(remote = bridge),
    )

    private fun errorLines(session: ToolsCliSession) =
        session.outputLines.filter { it.type == CLIOutputType.ERROR }.map { it.text }
}

private class StoredLoginProbeError : Error("probe")

private const val LOGIN_BUDGET_SECONDS = 40

private class LoginFaultBridge(private val failure: Throwable) : ToolsCliRemoteBridge {
    var storedPasswords = 0

    override suspend fun radioId(): Uuid = RADIO_ID

    override suspend fun retrievePassword(publicKey: ByteArray): String = "secret"

    override suspend fun deletePassword(publicKey: ByteArray) = Unit

    override suspend fun storePassword(password: String, publicKey: ByteArray) {
        storedPasswords += 1
    }

    override suspend fun createSession(radioID: Uuid, contact: ContactDto): Uuid = SESSION_ID

    override suspend fun login(
        sessionID: Uuid,
        password: String,
        pathLength: Int,
        onTimeoutKnown: (suspend (Int) -> Unit)?,
    ): Boolean {
        onTimeoutKnown?.invoke(LOGIN_BUDGET_SECONDS)
        throw failure
    }

    override suspend fun logout(sessionID: Uuid) = Unit

    override suspend fun sendRawCommand(sessionID: Uuid, command: String, timeout: Duration?): String = error("unused")
}

private val RADIO_ID = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
private val SESSION_ID = Uuid.normalized("B0000000-0000-0000-0000-000000000099")

private fun repeater(): ContactDto = ContactDto(
    id = Uuid.normalized("C0000000-0000-0000-0000-000000000002"),
    radioID = RADIO_ID,
    publicKey = ByteArray(PUBLIC_KEY_LENGTH) { ContactType.REPEATER.raw.toByte() },
    name = "R1",
    typeRawValue = ContactType.REPEATER.raw,
    flags = 0,
    outPathLength = 0,
    outPath = ByteArray(0),
    lastAdvertTimestamp = 0,
    latitude = 0.0,
    longitude = 0.0,
    lastModified = 0,
    isBlocked = false,
    isMuted = false,
    isFavorite = false,
    unreadCount = 0,
)

private const val PUBLIC_KEY_LENGTH = 32
