package one.meshcore.shared.tools

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.CLIOutputType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolsCliSessionSelectionTest {
    private fun session() = ToolsCliSession("Radio", toolsCliTestMessages(), { emptyList() }, { emptyList() }).apply {
        remoteSessions += listOf(
            ToolsCliSession.RemoteCliSession(Uuid.random(), "Alpha", 0),
            ToolsCliSession.RemoteCliSession(Uuid.random(), "0", 0),
            ToolsCliSession.RemoteCliSession(Uuid.random(), "2147483648", 0),
            ToolsCliSession.RemoteCliSession(Uuid.random(), "9223372036854775808", 0),
        )
        activeRemote = remoteSessions.first()
    }

    @Test
    fun invalidNumericSelectorsNeverMatchNamesOrBecomeCommands() = runTest {
        val session = session()
        val original = session.activeRemote
        for (number in listOf("0", "-1", "-9223372036854775808", "2147483648", "9223372036854775807")) {
            for (command in listOf("session $number", "s$number")) {
                session.executeCommand(command)
                assertEquals(original, session.activeRemote, command)
                assertEquals(CLIOutputType.ERROR, session.outputLines.last().type, command)
                assertEquals("${session.messages.sessionNotFound} $number", session.outputLines.last().text, command)
            }
        }
    }

    @Test
    fun signedAndPaddedLocalNumbersSelectLocal() = runTest {
        val session = session()
        for (number in listOf("1", "+1", "01", "0001")) {
            for (command in listOf("session $number", "s$number")) {
                session.activeRemote = session.remoteSessions.first()
                session.executeCommand(command)
                assertNull(session.activeRemote, command)
                assertEquals("${session.messages.sessionSwitched} Radio", session.outputLines.last().text)
            }
        }
    }

    @Test
    fun unicodeDigitsRemainNamesAndNeverBecomeNumericShortcuts() = runTest {
        val session = session()
        for (name in listOf("٢", "2٢", "+٢")) {
            val remote = ToolsCliSession.RemoteCliSession(Uuid.random(), name, 0)
            session.remoteSessions += remote
            session.executeCommand("session $name")
            assertEquals(remote, session.activeRemote, name)
            assertNull(session.parseSessionShortcut("s$name"))
        }
    }

    @Test
    fun validRemoteNumbersAndNamesKeepSessionOutputConsistent() = runTest {
        val session = session()
        for (selector in listOf("2", "+2", "02", "alpha")) {
            session.executeCommand("session $selector")
            assertEquals(session.remoteSessions.first(), session.activeRemote)
            assertEquals("${session.messages.sessionSwitched} @Alpha", session.outputLines.last().text)
        }
        session.executeCommand("session list")
        assertEquals("  * 2. @Alpha", session.outputLines[session.outputLines.lastIndex - 3].text)
        session.executeCommand("session 9223372036854775808")
        assertEquals(session.remoteSessions.last(), session.activeRemote)
    }
}
