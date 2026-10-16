package one.meshcore.shared.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.connection.FakeMeshSession
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.InMemoryRemoteNodeStore
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.tools.ui.ToolsCliViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The app mints a fresh bridge on every provider read, so the retained terminal survives navigation only
 * when two bridges over one connection are the same key. Reference equality would drop the scrollback,
 * the command history, and every remote login each time the user leaves and returns to the CLI.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class ToolsCliTerminalIdentityTest {
    @AfterTest
    fun tearDown() = ToolsCliTerminalHost.reset()

    private fun create(scope: CoroutineScope, local: ToolsCliLocalBridge?) = ToolsCliViewModel(
        scope = scope,
        deviceName = "Radio",
        messages = toolsCliTestMessages(),
        fetchContacts = { emptyList() },
        fetchChannels = { emptyList() },
        options = ToolsCliSession.Options(remote = null, local = local),
    )

    @Test
    fun localBridgesOverOneConnectionAreTheSameTerminalIdentity() {
        val session = FakeMeshSession()
        // Exactly what AppContainer's provider does on each read.
        val first = cliBridge(session)
        val second = cliBridge(session)

        assertEquals(
            ToolsCliTerminalKey("Radio", remote = null, local = first),
            ToolsCliTerminalKey("Radio", remote = null, local = second),
            "two reads of the same connection's provider must not look like two terminals",
        )
    }

    @Test
    fun localBridgesOverDifferentConnectionsAreDifferentTerminalIdentities() {
        val first = cliBridge(FakeMeshSession())
        val second = cliBridge(FakeMeshSession())

        assertNotEquals(
            ToolsCliTerminalKey("Radio", remote = null, local = first),
            ToolsCliTerminalKey("Radio", remote = null, local = second),
            "a reconnect rebuilds the session, which iOS answers with reset()",
        )
    }

    @Test
    fun returningToTheCliKeepsTheScrollbackAndInput() = runTest {
        val session = FakeMeshSession()
        val opened = ToolsCliTerminalHost.terminal(
            ToolsCliTerminalKey("Radio", remote = null, local = cliBridge(session)),
        ) { create(it, cliBridge(session)) }
        opened.input.setInput("get name")

        val reopened = ToolsCliTerminalHost.terminal(
            ToolsCliTerminalKey("Radio", remote = null, local = cliBridge(session)),
        ) { create(it, cliBridge(session)) }

        assertSame(opened, reopened, "leaving and returning to the CLI must not wipe the terminal")
        assertEquals("get name", reopened.state.value.currentInput)
        assertTrue(reopened.state.value.outputLines.isNotEmpty())
    }

    @Test
    fun aReconnectStartsAFreshTerminal() = runTest {
        val opened = ToolsCliTerminalHost.terminal(
            ToolsCliTerminalKey(
                "Radio",
                remote = null,
                local = cliBridge(FakeMeshSession()),
            ),
        ) { create(it, null) }

        val afterReconnect = ToolsCliTerminalHost.terminal(
            ToolsCliTerminalKey(
                "Radio",
                remote = null,
                local = cliBridge(FakeMeshSession()),
            ),
        ) { create(it, null) }

        assertNotSame(opened, afterReconnect)
    }

    @Test
    fun remoteBridgesOverOneConnectionAreTheSameTerminalIdentity() = runTest {
        val nodeSession = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val passwords = InMemoryNodePasswordStore()
        val service = RemoteNodeService(nodeSession, store, passwords, backgroundScope)
        val coordinator = NodeCliCoordinator(nodeSession, store, passwords, backgroundScope)
        fun bridge() = ServiceToolsCliRemoteBridge(
            radioIdProvider = { null },
            remoteNodeService = service,
            nodeCliCoordinator = coordinator,
            passwordStore = passwords,
        )

        assertEquals(
            ToolsCliTerminalKey("Radio", remote = bridge(), local = null),
            ToolsCliTerminalKey("Radio", remote = bridge(), local = null),
        )
        assertNotEquals(
            ToolsCliTerminalKey("Radio", remote = bridge(), local = null),
            ToolsCliTerminalKey(
                "Radio",
                remote = ServiceToolsCliRemoteBridge(
                    radioIdProvider = { null },
                    remoteNodeService = RemoteNodeService(nodeSession, store, passwords, backgroundScope),
                    nodeCliCoordinator = coordinator,
                    passwordStore = passwords,
                ),
                local = null,
            ),
            "a rebuilt service graph is a new connection",
        )
    }

    private fun cliBridge(session: FakeMeshSession) = ServiceToolsCliLocalBridge(
        session,
        advertSend = {},
        writes = NoopCliWrites(),
    )
}
