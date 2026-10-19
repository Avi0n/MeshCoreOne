// AndroidOnly: WP-316 Native boundary cases for CLI login/auth, countdown, sessions, remote passthrough and node/channel listings.
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.feature.tools.diagnostics.support.TestScheduler
import com.meshcoreone.android.feature.tools.diagnostics.support.XmlDiagnosticsText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test

class CliToolSessionsTest {
    private val scheduler = TestScheduler()
    private val holder = CliToolStateHolder(scheduler.scope, XmlDiagnosticsText(), scheduler.clock, FAKE_ERRORS)
    private val admin = FakeRepeaterAdmin()
    private val remote = FakeRemoteNode()
    private val directory = FakeDirectory(
        contacts = listOf(contact("Hilltop", type = 2u), contact("Basement", type = 3u, outPathLength = 0x42u), contact("Alice", type = 1u)),
    )

    private val lines get() = holder.state.value.terminal.outputLines
    private val lastText get() = lines.last().text

    private fun connect() {
        holder.configure(
            dependencies(
                repeaterAdminService = { admin }, remoteNodeService = { remote }, dataStore = { directory },
                radioId = { TEST_RADIO },
            ),
            localDeviceName = "Base",
        )
        scheduler.runCurrent()
    }

    private fun run(line: String) {
        holder.executeCommand(line)
        scheduler.runCurrent()
    }

    @Test
    fun `connecting shows the welcome banner once and starts a local session`() {
        connect()
        assertEquals(listOf("MeshCore One CLI", "Connected to Base", "Type 'help' for available commands.", ""), lines.map { it.text })
        assertEquals("Base> ", holder.promptText)
        holder.configure(dependencies(repeaterAdminService = { FakeRepeaterAdmin() }), localDeviceName = "Base")
        assertEquals(4, lines.size)
    }

    @Test
    fun `node completion names come from repeaters and rooms only`() {
        connect()
        assertEquals(listOf("Hilltop", "Basement"), holder.completionEngine.nodeNames)
    }

    @Test
    fun `login with a stored password logs in, stores it and switches to the remote session`() {
        remote.storedPassword = "secret"
        connect()
        run("login hilltop")
        assertEquals("Logged in to @Hilltop", lastText)
        assertEquals(CliOutputType.SUCCESS, lines.last().type)
        assertEquals(listOf("secret"), remote.storedPasswords)
        val session = assertNotNull(holder.state.value.activeSession)
        assertFalse(session.isLocal)
        assertEquals("@Hilltop> ", holder.promptText)
    }

    @Test
    fun `login without a stored password prompts and masks the password echo`() {
        connect()
        run("login Hilltop")
        assertEquals("Password: ", holder.promptText)
        assertNull(holder.tabComplete())
        run("hunter2")
        assertTrue(lines.any { it.text == "Password: ****" && it.type == CliOutputType.COMMAND })
        assertFalse(lines.any { "hunter2" in it.text })
        assertFalse("hunter2" in holder.state.value.terminal.commandHistory)
        assertEquals(listOf("hunter2"), remote.loginCalls.map { it.second })
    }

    @Test
    fun `an empty password cancels the pending login`() {
        connect()
        run("login Hilltop")
        run("")
        assertEquals("Command cancelled", lastText)
        assertNull(holder.state.value.pendingLoginContact)
        assertTrue(remote.loginCalls.isEmpty())
    }

    @Test
    fun `login forget flag deletes the saved password first`() {
        remote.storedPassword = "old"
        connect()
        run("login -f Hilltop")
        assertEquals(1, remote.deletedPasswords.size)
        assertNotNull(holder.state.value.pendingLoginContact)
        run("") // An empty password cancels the prompt; the next line is a command again.
        run("login --forget   Basement")
        assertEquals(2, remote.deletedPasswords.size)
    }

    @Test
    fun `login guards usage, unknown nodes, chat contacts and remote sessions`() {
        connect()
        run("login")
        assertEquals("Usage: login [-f] <node>", lastText)
        run("login Nobody")
        assertEquals("Node not found: Nobody", lastText)
        run("login Alice")
        assertEquals("Node not found: Alice", lastText)
        holder.setActiveSession(CliSession.remote(EntityKey(TEST_RADIO, java.util.UUID.randomUUID()), "R", 0u))
        run("login Hilltop")
        assertEquals("Login only available from local session", lastText)
    }

    @Test
    fun `login failure messages follow the remote fault`() {
        remote.storedPassword = "pw"
        connect()
        remote.loginSuccess = false
        run("login Hilltop")
        assertEquals("Login failed: Authentication failed", lastText)
        remote.loginFailure = FakeRemoteTimeout()
        run("login Hilltop")
        assertEquals("Timeout waiting for response", lastText)
        remote.loginFailure = FakeLoginFailed("bad ack")
        run("login Hilltop")
        assertEquals("Login failed: bad ack", lastText)
        remote.loginFailure = IllegalStateException("radio busy")
        run("login Hilltop")
        assertEquals("Login failed: radio busy", lastText)
    }

    @Test
    fun `login countdown ticks on the virtual clock and stops when login finishes`() {
        remote.storedPassword = "pw"
        remote.loginTimeoutSeconds = 3
        remote.loginGate = CompletableDeferred()
        connect()
        run("login Hilltop")
        assertEquals("Logging in... (3s)", holder.promptText)
        scheduler.advanceBy(1.seconds)
        assertEquals("Logging in... (2s)", holder.promptText)
        scheduler.advanceBy(1.seconds)
        assertEquals(1, holder.state.value.remainingSeconds)
        remote.loginGate?.complete(Unit)
        scheduler.runCurrent()
        assertNull(holder.state.value.remainingSeconds)
        assertEquals(0, scheduler.clock.pendingSleepers)
        assertEquals("@Hilltop> ", holder.promptText)
    }

    @Test
    fun `cancelling a login stops the countdown`() {
        remote.storedPassword = "pw"
        remote.loginTimeoutSeconds = 5
        remote.loginGate = CompletableDeferred()
        connect()
        run("login Hilltop")
        holder.cancelCurrentCommand()
        scheduler.runCurrent()
        assertNull(holder.state.value.remainingSeconds)
        assertEquals("Command cancelled", lastText)
        assertTrue(holder.state.value.remoteSessions.isEmpty())
    }

    @Test
    fun `remote commands pass through with the default timeout and reboot treats timeout as sent`() {
        remote.storedPassword = "pw"
        connect()
        run("login Hilltop")
        admin.response = "> 22"
        run("get tx")
        assertEquals("> 22", lastText)
        assertEquals(10.seconds, admin.calls.last().third)
        admin.failure = FakeRemoteTimeout()
        run("reboot")
        assertEquals("Reboot command sent", lastText)
        assertEquals(2.seconds, admin.calls.last().third)
        run("ver")
        assertEquals("Request timed out", lastText)
        admin.failure = IllegalStateException("Permission denied")
        run("ver")
        assertEquals("Permission denied", lastText)
    }

    @Test
    fun `remote clear with arguments is sent while bare clear stays local`() {
        remote.storedPassword = "pw"
        connect()
        run("login Hilltop")
        run("clear stats")
        assertEquals("clear stats", admin.calls.last().second)
        run("clear")
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `sessions list, switch by number, name and shortcut, then logout`() {
        remote.storedPassword = "pw"
        connect()
        run("login Hilltop")
        run("session list")
        assertEquals(listOf("Sessions:", "    1. Base (local)", "  * 2. @Hilltop").takeLast(2), lines.takeLast(2).map { it.text })
        run("s1")
        assertEquals("Switched to Base", lastText)
        run("session HILLTOP")
        assertEquals("Switched to @Hilltop", lastText)
        run("session 7")
        assertEquals("Session not found: 7", lastText)
        run("logout")
        assertEquals("Logged out", lastText)
        assertTrue(holder.state.value.activeSession?.isLocal == true)
        assertTrue(holder.state.value.remoteSessions.isEmpty())
        run("logout")
        assertEquals("Not logged in to any repeater", lastText)
    }

    @Test
    fun `nodes lists repeaters before rooms with padded columns and routes`() {
        connect()
        run("nodes")
        assertEquals(
            listOf("Nodes (2):", "  Hilltop              Repeater  Direct", "  Basement             Room      2 hops"),
            lines.takeLast(3).map { it.text },
        )
    }

    @Test
    fun `channels list sorted by slot with empty names marked`() {
        directory.channels = listOf(
            ChannelDTO(radioId = TEST_RADIO, index = 2u, name = "Ops"),
            ChannelDTO(radioId = TEST_RADIO, index = 0u, name = ""),
        )
        connect()
        run("channels")
        assertEquals(listOf("Channels (2):", "  [0] (empty)", "  [2] Ops"), lines.takeLast(3).map { it.text })
        directory.failure = IllegalStateException("db closed")
        run("channels")
        assertEquals("No channels found", lastText)
    }

    @Test
    fun `disconnect clears sessions and reset keeps history`() {
        remote.storedPassword = "pw"
        connect()
        run("login Hilltop")
        holder.configure(dependencies(), localDeviceName = "Base")
        assertNull(holder.state.value.activeSession)
        assertTrue(holder.state.value.remoteSessions.isEmpty())
        holder.reset()
        assertTrue(lines.isEmpty())
        assertEquals(listOf("login Hilltop"), holder.state.value.terminal.commandHistory)
    }

    @Test
    fun `help lists local radio commands only on a local session`() {
        connect()
        run("help")
        assertTrue(lines.any { it.text == "Local radio commands:" })
        val count = lines.size
        holder.setActiveSession(null)
        run("help")
        assertEquals(count + 1 + 9, lines.size)
    }
}
