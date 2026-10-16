package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.cli.CLICompletionEngine
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.CLIOutputType
import one.meshcore.shared.remotenode.LoginTimeoutConfig
import one.meshcore.shared.remotenode.RemoteNodeError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

/** The 0xFF out-path byte a flood-routed contact carries. */
private const val FLOOD_SENTINEL = 0xFF

/**
 * Login, session list, tab completion, and radio-failure copy.
 */
class ToolsCliSessionTest {
    @Test
    fun keepingAnEmptyToolsCliLineDoesNotRadio() = runTest {
        var contactReads = 0
        var channelReads = 0
        val session = ToolsCliSession(
            deviceName = "Radio",
            messages = testMessages(),
            fetchContacts = {
                contactReads += 1
                emptyList()
            },
            fetchChannels = {
                channelReads += 1
                emptyList()
            },
        )
        assertEquals("", session.currentInput)
        assertEquals("", session.ghostText)
        assertFalse(session.isWaitingForResponse)
        assertTrue(session.outputLines.isNotEmpty())
        assertNull(session.tabSuggestions)
        assertEquals(0, contactReads)
        assertEquals(0, channelReads)

        session.setInput("")

        assertEquals(0, contactReads)
        assertEquals(0, channelReads)
        assertEquals("", session.currentInput)
        assertEquals("", session.ghostText)
        assertFalse(session.isWaitingForResponse)
        assertNull(session.tabSuggestions)
        assertTrue(session.outputLines.isNotEmpty())
    }

    @Test
    fun cancelWhileIdleIsNoOp() = runTest {
        val session = emptySession()
        val before = session.outputLines.size
        session.cancelCurrentCommand()
        assertFalse(session.isWaitingForResponse)
        assertTrue(session.outputLines.size == before)
    }

    @Test
    fun helpAndClearAndUnknown() = runTest {
        val session = emptySession()
        session.executeCommand("help")
        assertTrue(session.outputLines.any { it.text.contains("Available") })
        session.executeCommand("clear")
        assertTrue(session.outputLines.isEmpty())
        session.executeCommand("foobar")
        assertTrue(session.outputLines.last().type == CLIOutputType.ERROR)
        assertTrue(session.outputLines.last().text.contains("Unknown"))
    }

    @Test
    fun nodesListsRepeatersAndRooms() = runTest {
        val contacts = listOf(
            contact("Alice", ContactType.CHAT),
            contact("R1", ContactType.REPEATER),
            contact("RoomA", ContactType.ROOM),
        )
        val session = ToolsCliSession(
            deviceName = "Radio",
            messages = testMessages(),
            fetchContacts = { contacts },
            fetchChannels = { emptyList() },
        )
        session.executeCommand("nodes")
        val body = session.outputLines.filter { it.type == CLIOutputType.RESPONSE }.map { it.text }
        assertTrue(body.any { it.contains("Nodes (2)") })
        assertTrue(body.any { it.contains("R1") && it.contains("Repeater") })
        assertTrue(body.any { it.contains("RoomA") && it.contains("Room") })
        assertTrue(body.none { it.contains("Alice") })
    }

    @Test
    fun loginAndNodesUseAdvertisedNameNotNickname() = runTest {
        val nicknamed = contact("Fire-RPT-1", ContactType.REPEATER).copy(nickname = "Hilltop")
        val session = ToolsCliSession(
            deviceName = "Radio",
            messages = testMessages(),
            fetchContacts = { listOf(nicknamed) },
            fetchChannels = { emptyList() },
            options = ToolsCliSession.Options(remote = FakeRemoteBridge()),
        )
        session.executeCommand("login Hilltop")
        val nickResult = session.outputLines.last()
        assertEquals(CLIOutputType.ERROR, nickResult.type, nickResult.text)
        assertTrue(nickResult.text.contains("Node not found"), nickResult.text)

        session.executeCommand("login Fire-RPT-1")
        assertTrue(session.outputLines.any { it.text.contains("Logged in to") && it.text.contains("Fire-RPT-1") })

        val beforeNodes = session.outputLines.size
        session.executeCommand("nodes")
        val nodeLines = session.outputLines.drop(beforeNodes).map { it.text }
        assertTrue(nodeLines.any { it.contains("Fire-RPT-1") }, nodeLines.toString())
        assertTrue(nodeLines.none { it.contains("Hilltop") }, nodeLines.toString())
    }

    @Test
    fun channelsListsByIndex() = runTest {
        val channels = listOf(
            channel(2, "beta"),
            channel(0, "public"),
            channel(1, ""),
        )
        val session = ToolsCliSession(
            deviceName = "Radio",
            messages = testMessages(),
            fetchContacts = { emptyList() },
            fetchChannels = { channels },
        )
        session.executeCommand("channels")
        val lines = session.outputLines.filter { it.type == CLIOutputType.RESPONSE }.map { it.text }
        assertTrue(lines.any { it.contains("Channels (3)") })
        assertTrue(lines.any { it.contains("[0] public") })
        assertTrue(lines.any { it.contains("[1] (empty)") })
        assertTrue(lines.any { it.contains("[2] beta") })
    }

    @Test
    fun sessionListShowsLocal() = runTest {
        val session = emptySession()
        session.executeCommand("session list")
        val lines = session.outputLines.map { it.text }
        assertTrue(lines.any { it.contains("Active sessions") })
        assertTrue(lines.any { it.contains("Radio") && it.contains("local") })
    }

    @Test
    fun remoteLoginAndPassthrough() = runTest {
        val contact = contact("R1", ContactType.REPEATER)
        val sessionId = Uuid.normalized("B0000000-0000-0000-0000-000000000099")
        val commands = mutableListOf<String>()
        val bridge = object : ToolsCliRemoteBridge {
            override suspend fun radioId(): Uuid = radio
            override suspend fun retrievePassword(publicKey: ByteArray): String = "secret"
            override suspend fun deletePassword(publicKey: ByteArray) = Unit
            override suspend fun storePassword(password: String, publicKey: ByteArray) = Unit
            override suspend fun createSession(radioID: Uuid, contact: ContactDto): Uuid = sessionId
            override suspend fun login(
                sessionID: Uuid,
                password: String,
                pathLength: Int,
                onTimeoutKnown: (suspend (Int) -> Unit)?,
            ): Boolean = true
            override suspend fun logout(sessionID: Uuid) = Unit
            override suspend fun sendRawCommand(sessionID: Uuid, command: String, timeout: Duration?): String {
                commands += command
                return "ver 1.0"
            }
        }
        val session = ToolsCliSession(
            deviceName = "Radio",
            messages = testMessages(),
            fetchContacts = { listOf(contact) },
            fetchChannels = { emptyList() },
            options = ToolsCliSession.Options(remote = bridge),
        )
        session.executeCommand("login R1")
        assertTrue(session.outputLines.any { it.text.contains("Logged in to") && it.text.contains("R1") })
    }

    @Test
    fun loginWithoutAStoredPasswordShowsThePasswordPrompt() = runTest {
        val contact = contact("R1", ContactType.REPEATER)
        val bridge = object : ToolsCliRemoteBridge {
            override suspend fun radioId(): Uuid = radio
            override suspend fun retrievePassword(publicKey: ByteArray): String? = null
            override suspend fun deletePassword(publicKey: ByteArray) = Unit
            override suspend fun storePassword(password: String, publicKey: ByteArray) = Unit
            override suspend fun createSession(radioID: Uuid, contact: ContactDto): Uuid =
                Uuid.normalized("B0000000-0000-0000-0000-000000000099")
            override suspend fun login(
                sessionID: Uuid,
                password: String,
                pathLength: Int,
                onTimeoutKnown: (suspend (Int) -> Unit)?,
            ): Boolean = true
            override suspend fun logout(sessionID: Uuid) = Unit
            override suspend fun sendRawCommand(sessionID: Uuid, command: String, timeout: Duration?): String = ""
        }
        val session = ToolsCliSession(
            deviceName = "Radio",
            messages = testMessages(),
            fetchContacts = { listOf(contact) },
            fetchChannels = { emptyList() },
            options = ToolsCliSession.Options(remote = bridge),
        )
        session.executeCommand("login R1")
        assertEquals("Password: ", session.promptText)
    }

    @Test
    fun loginCountdownReplacesThePromptUntilItStops() = runTest {
        val session = ToolsCliSession(
            deviceName = "Radio",
            messages = testMessages(),
            fetchContacts = { emptyList() },
            fetchChannels = { emptyList() },
        )
        session.startCountdown(8)
        assertEquals("Logging in... (8s)", session.promptText)
        session.tickCountdown()
        assertEquals("Logging in... (7s)", session.promptText)
        session.stopCountdown()
        assertEquals("disconnected> ", session.promptText)
    }

    @Test
    fun promptShowsDisconnectedWhenNoLocalSession() = runTest {
        val disconnected = emptySession()
        assertEquals("disconnected> ", disconnected.promptText)
        val local = localSession(FakeLocalBridge())
        assertEquals("Radio> ", local.promptText)
    }

    @Test
    fun loginR1ContinuesAfterPasswordPrompt() = runTest {
        val contact = contact("R1", ContactType.REPEATER)
        val commands = mutableListOf<String>()
        val sessionId = Uuid.normalized("B0000000-0000-0000-0000-000000000099")
        val bridge = object : ToolsCliRemoteBridge {
            override suspend fun radioId(): Uuid = radio
            override suspend fun retrievePassword(publicKey: ByteArray): String = "secret"
            override suspend fun deletePassword(publicKey: ByteArray) = Unit
            override suspend fun storePassword(password: String, publicKey: ByteArray) = Unit
            override suspend fun createSession(radioID: Uuid, contact: ContactDto): Uuid = sessionId
            override suspend fun login(
                sessionID: Uuid,
                password: String,
                pathLength: Int,
                onTimeoutKnown: (suspend (Int) -> Unit)?,
            ): Boolean = true
            override suspend fun logout(sessionID: Uuid) = Unit
            override suspend fun sendRawCommand(sessionID: Uuid, command: String, timeout: Duration?): String {
                commands += command
                return "ver 1.0"
            }
        }
        val session = ToolsCliSession(
            deviceName = "Radio",
            messages = testMessages(),
            fetchContacts = { listOf(contact) },
            fetchChannels = { emptyList() },
            options = ToolsCliSession.Options(remote = bridge),
        )
        session.executeCommand("login R1")
        assertTrue(session.outputLines.any { it.text.contains("Logged in to") && it.text.contains("R1") })
        session.executeCommand("ver")
        assertTrue(commands.contains("ver"))
        assertTrue(session.outputLines.any { it.text.contains("ver 1.0") })
        session.executeCommand("logout")
        assertTrue(session.outputLines.any { it.text.contains("Logged out") })
    }

    @Test
    fun helpIsLocalSessionAware() = runTest {
        val session = emptySession()
        session.executeCommand("help")
        val lines = session.outputLines.map { it.text }
        assertTrue(lines.any { it.contains("Local radio commands:") }, "local help shows the local block")
        assertTrue(lines.none { it.contains("Repeater commands:") }, "local help omits the repeater block")
    }

    @Test
    fun helpOnRemoteSessionShowsRepeaterBlock() = runTest {
        val session = loggedInRemoteSession(FakeRemoteBridge())
        session.executeCommand("help")
        val lines = session.outputLines.map { it.text }
        assertTrue(lines.any { it.contains("Repeater commands:") }, "remote help shows the repeater block")
        assertTrue(lines.none { it.contains("Local radio commands:") }, "remote help omits the local block")
    }

    @Test
    fun rebootOnRemoteSessionReportsRebootSentOnTimeout() = runTest {
        val bridge = FakeRemoteBridge(timeoutCommandPrefix = "reboot")
        val session = loggedInRemoteSession(bridge)
        session.executeCommand("reboot")
        val last = session.outputLines.last()
        assertEquals(CLIOutputType.SUCCESS, last.type)
        assertTrue(last.text.contains("Reboot command sent"))
        assertTrue(bridge.commands.contains("reboot"), "reboot was still sent to the repeater")
    }

    @Test
    fun sessionShortcutSwitchesByIndex() = runTest {
        val session = loggedInRemoteSession(FakeRemoteBridge())
        session.executeCommand("s1")
        assertTrue(session.isLocalSession, "s1 returns to the local session")
        session.executeCommand("s2")
        assertFalse(session.isLocalSession, "s2 switches back to the remote session")
    }

    @Test
    fun refreshCustomVarKeysFeedsGetTabCompletion() = runTest {
        val bridge = FakeLocalBridge()
        bridge.customVars["gpsMode"] = "1"
        val session = localSession(bridge)
        session.refreshCustomVarKeys()
        session.setInput("get gp")
        session.tabComplete()
        assertEquals("get gpsMode ", session.currentInput)
    }

    @Test
    fun refreshCustomVarKeysSwallowsFirmwareErrors() = runTest {
        val session = localSession(FakeLocalBridge(failGetCustomVarsWith = Exception("old firmware")))
        session.refreshCustomVarKeys()
        session.setInput("get gp")
        session.tabComplete()
        assertEquals("get gp", session.currentInput)
    }

    @Test
    fun aCancelledCustomVarRefreshPropagates() = runTest {
        val cancelled = CancellationException("stop")
        val session = localSession(FakeLocalBridge(failGetCustomVarsWith = cancelled))
        val thrown = assertFailsWith<CancellationException> {
            session.refreshCustomVarKeys()
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun anErrorCustomVarRefreshLeavesKeysEmpty() = runTest {
        val session = localSession(FakeLocalBridge(failGetCustomVarsWith = CustomVarRefreshProbeError()))
        session.refreshCustomVarKeys()
        session.setInput("get gp")
        session.tabComplete()
        assertEquals("get gp", session.currentInput)
    }

    @Test
    fun refreshCompletionCatalogReloadsNodeNames() = runTest {
        val contacts = mutableListOf(contact("R1", ContactType.REPEATER))
        val session = ToolsCliSession(
            deviceName = "Radio",
            messages = testMessages(),
            fetchContacts = { contacts.toList() },
            fetchChannels = { emptyList() },
        )
        session.refreshCompletionCatalog()
        session.setInput("login R")
        session.tabComplete()
        assertEquals("login R1 ", session.currentInput)

        contacts += contact("R2", ContactType.REPEATER)
        session.refreshCompletionCatalog()
        session.setInput("login R")
        session.tabComplete()
        assertEquals(listOf("R1", "R2"), session.tabSuggestions)
        assertTrue(session.applySuggestionAt(1))
        assertEquals("login R2 ", session.currentInput)
        assertEquals(null, session.tabSuggestions)
    }

    @Test
    fun refreshNodeNamesFeedsLoginTabCompletion() = runTest {
        val contacts = listOf(
            contact("Repeater1", ContactType.REPEATER),
            contact("RoomA", ContactType.ROOM),
            contact("Alice", ContactType.CHAT),
        )
        val session = ToolsCliSession(
            deviceName = "Radio",
            messages = testMessages(),
            fetchContacts = { contacts },
            fetchChannels = { emptyList() },
        )
        session.refreshNodeNames()
        session.setInput("login R")
        session.tabComplete()
        assertEquals(listOf("Repeater1", "RoomA"), session.tabSuggestions)
    }

    @Test
    fun aFloodLoginBudgetsTheFloodSentinelNotAZeroHopPath() = runTest {
        val bridge = FakeRemoteBridge()
        loggedInRemoteSession(bridge)
        assertEquals(FLOOD_SENTINEL, bridge.loginPathLength, "the raw out-path byte has to reach the timeout policy")
        assertEquals(
            LoginTimeoutConfig.MAXIMUM_TIMEOUT,
            LoginTimeoutConfig.timeout(bridge.loginPathLength ?: 0),
            "a flood-routed repeater gets the worst-case budget, not a 5 s zero-hop one",
        )
    }

    @Test
    fun anEmptyPasswordCancelsTheLoginInsteadOfArmingThePrompt() = runTest {
        val bridge = FakeRemoteBridge(storedPassword = null)
        val session = ToolsCliSession(
            deviceName = "Radio",
            messages = testMessages(),
            fetchContacts = { listOf(contact("R1", ContactType.REPEATER)) },
            fetchChannels = { emptyList() },
            options = ToolsCliSession.Options(remote = bridge),
        )
        session.executeCommand("login R1")
        assertTrue(session.outputLines.any { it.text.contains("Password required") }, "the prompt is armed")
        session.executeCommand("")
        assertEquals(CLIOutputType.ERROR, session.outputLines.last().type, "an empty line cancels the login")

        session.executeCommand("help")
        assertNull(bridge.loginPassword, "the next command typed must not be sent over the mesh as the password")
        assertTrue(session.outputLines.any { it.text.contains("Available") }, "it runs as an ordinary command")
    }

    @Test
    fun ghostTextCompletesTheLastTokenNotTheWholeLine() = runTest {
        val session = emptySession()
        session.setInput("session li")
        assertEquals("st", session.ghostText, "the suggestion extends the argument, not the whole input")
        session.setInput("hel")
        assertEquals("p", session.ghostText)
    }

    @Test
    fun tabListsSeveralMatchesWithoutRewritingTheInput() = runTest {
        val session = emptySession()
        session.setInput("s")
        session.tabComplete()
        assertEquals("s", session.currentInput, "listing candidates must not overwrite what was typed")
        assertTrue((session.tabSuggestions?.size ?: 0) > 1)
        assertEquals(null, session.tabSelectionIndex, "the first Tab lists; the next one starts highlighting")
        assertFalse(session.applySelectedSuggestion(), "the next Enter submits rather than being swallowed")
        session.tabComplete()
        assertEquals(0, session.tabSelectionIndex)
    }

    @Test
    fun tabOnASingleMatchAppliesItWithATrailingSpace() = runTest {
        val session = emptySession()
        session.setInput("hel")
        session.tabComplete()
        assertEquals("help ", session.currentInput, "the trailing space lets the next Tab complete an argument")
        assertEquals(null, session.tabSuggestions)
    }

    @Test
    fun ghostTextAndTabSuppressedDuringPendingConfirmation() = runTest {
        val session = localSession(FakeLocalBridge())
        session.executeCommand("reboot")
        assertTrue(session.promptText.contains("confirm reboot?"), "a y/n confirmation is pending")
        session.setInput("ve")
        assertEquals("", session.ghostText, "no completion ghost text while a confirmation is pending")
        session.tabComplete()
        assertTrue(session.tabSuggestions == null, "tab completion is inert while a confirmation is pending")
    }

    @Test
    fun loginWithoutBridgeShowsHint() = runTest {
        val session = emptySession()
        session.executeCommand("login R1")
        assertTrue(session.outputLines.any { it.text.contains("Use Nodes CLI for remote") })
    }

    // MARK: - Local radio commands (RN-9)

    @Test
    fun aRadioFailureShowsTranslatedTextRatherThanTheExceptionMessage() = runTest {
        val bridge = FakeLocalBridge(failSyncClockWith = IllegalStateException("device error code=6"))
        val session = ToolsCliSession(
            deviceName = "Radio",
            messages = testMessages().copy(deviceErrorText = { "The radio rejected the request." }),
            fetchContacts = { emptyList() },
            fetchChannels = { emptyList() },
            options = ToolsCliSession.Options(local = bridge),
        )

        session.executeCommand("clock sync")

        val errors = session.outputLines.filter { it.type == CLIOutputType.ERROR }.map { it.text }
        assertTrue(errors.any { it == "The radio rejected the request." }, "shows the translated text")
        assertTrue(errors.none { it.contains("code=6") }, "never shows the developer message")
    }
}

/**
 * Local radio commands and the lines that must not touch the radio.
 */
class ToolsCliLocalCommandTest {
    @Test
    fun settingTheCliDeviceNameDoesNotRadio() = runTest {
        val bridge = FakeLocalBridge()
        val session = localSession(bridge)
        assertEquals("Radio> ", session.promptText)
        assertTrue(bridge.calls.isEmpty())
        val linesBefore = session.outputLines

        session.setDeviceName("Field")

        assertEquals("Field> ", session.promptText)
        assertEquals(linesBefore, session.outputLines)
        assertTrue(bridge.calls.isEmpty())
        assertTrue(session.isLocalSession)
    }

    @Test
    fun startingACliCountdownDoesNotRadio() = runTest {
        val bridge = FakeLocalBridge()
        val session = localSession(bridge)
        assertNull(session.remainingSeconds)
        assertEquals("Radio> ", session.promptText)
        assertTrue(bridge.calls.isEmpty())
        val linesBefore = session.outputLines

        session.startCountdown(5)

        assertEquals(5, session.remainingSeconds)
        assertEquals("Logging in... (5s)", session.promptText)
        assertEquals(linesBefore, session.outputLines)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun typingACliCommandLineDoesNotRadio() = runTest {
        val bridge = FakeLocalBridge()
        var contactReads = 0
        var channelReads = 0
        val session = ToolsCliSession(
            deviceName = "Radio",
            messages = testMessages(),
            fetchContacts = {
                contactReads += 1
                emptyList()
            },
            fetchChannels = {
                channelReads += 1
                emptyList()
            },
            options = ToolsCliSession.Options(local = bridge),
        )
        assertEquals("", session.currentInput)
        assertEquals("", session.ghostText)
        assertNull(session.tabSuggestions)
        assertNull(session.remainingSeconds)
        assertEquals("Radio> ", session.promptText)
        assertTrue(bridge.calls.isEmpty())
        assertEquals(0, contactReads)
        assertEquals(0, channelReads)
        val linesBefore = session.outputLines

        session.setInput("zz")

        assertEquals("zz", session.currentInput)
        assertEquals("", session.ghostText)
        assertNull(session.tabSuggestions)
        assertNull(session.remainingSeconds)
        assertEquals("Radio> ", session.promptText)
        assertEquals(linesBefore, session.outputLines)
        assertTrue(bridge.calls.isEmpty())
        assertEquals(0, contactReads)
        assertEquals(0, channelReads)
        assertTrue(session.isLocalSession)
    }

    @Test
    fun recallingAnEmptyCliHistoryDoesNotRadio() = runTest {
        val bridge = FakeLocalBridge()
        val session = localSession(bridge)
        assertEquals("", session.currentInput)
        assertTrue(session.commandHistory.isEmpty())
        assertNull(session.historyIndex)
        assertEquals("", session.ghostText)
        assertNull(session.tabSuggestions)
        assertNull(session.remainingSeconds)
        assertEquals("Radio> ", session.promptText)
        assertTrue(bridge.calls.isEmpty())
        val linesBefore = session.outputLines

        session.historyUp()

        assertEquals("", session.currentInput)
        assertTrue(session.commandHistory.isEmpty())
        assertNull(session.historyIndex)
        assertEquals("", session.ghostText)
        assertNull(session.tabSuggestions)
        assertNull(session.remainingSeconds)
        assertEquals("Radio> ", session.promptText)
        assertEquals(linesBefore, session.outputLines)
        assertTrue(bridge.calls.isEmpty())
        assertTrue(session.isLocalSession)
    }

    @Test
    fun clearingCliTabSuggestionsDoesNotRadio() = runTest {
        val bridge = FakeLocalBridge()
        val session = localSession(bridge)
        assertEquals("", session.currentInput)
        assertTrue(session.commandHistory.isEmpty())
        assertNull(session.historyIndex)
        assertEquals("", session.ghostText)
        assertNull(session.tabSuggestions)
        assertNull(session.tabSelectionIndex)
        assertNull(session.remainingSeconds)
        assertEquals("Radio> ", session.promptText)
        assertTrue(bridge.calls.isEmpty())
        val linesBefore = session.outputLines

        session.clearTabSuggestions()

        assertEquals("", session.currentInput)
        assertTrue(session.commandHistory.isEmpty())
        assertNull(session.historyIndex)
        assertEquals("", session.ghostText)
        assertNull(session.tabSuggestions)
        assertNull(session.tabSelectionIndex)
        assertNull(session.remainingSeconds)
        assertEquals("Radio> ", session.promptText)
        assertEquals(linesBefore, session.outputLines)
        assertTrue(bridge.calls.isEmpty())
        assertTrue(session.isLocalSession)
    }

    @Test
    fun acceptingEmptyCliGhostTextDoesNotRadio() = runTest {
        val bridge = FakeLocalBridge()
        val session = localSession(bridge)
        assertEquals("", session.currentInput)
        assertEquals("", session.ghostText)
        assertNull(session.tabSuggestions)
        assertNull(session.tabSelectionIndex)
        assertNull(session.historyIndex)
        assertNull(session.remainingSeconds)
        assertEquals("Radio> ", session.promptText)
        assertTrue(bridge.calls.isEmpty())
        val linesBefore = session.outputLines

        session.acceptGhostText()

        assertEquals("", session.currentInput)
        assertEquals("", session.ghostText)
        assertNull(session.tabSuggestions)
        assertNull(session.tabSelectionIndex)
        assertNull(session.historyIndex)
        assertNull(session.remainingSeconds)
        assertEquals("Radio> ", session.promptText)
        assertEquals(linesBefore, session.outputLines)
        assertTrue(bridge.calls.isEmpty())
        assertTrue(session.isLocalSession)
    }

    @Test
    fun steppingDownAnEmptyCliHistoryDoesNotRadio() = runTest {
        val bridge = FakeLocalBridge()
        val session = localSession(bridge)
        assertEquals("", session.currentInput)
        assertTrue(session.commandHistory.isEmpty())
        assertNull(session.historyIndex)
        assertEquals("", session.ghostText)
        assertNull(session.tabSuggestions)
        assertNull(session.remainingSeconds)
        assertEquals("Radio> ", session.promptText)
        assertTrue(bridge.calls.isEmpty())
        val linesBefore = session.outputLines

        session.historyDown()

        assertEquals("", session.currentInput)
        assertTrue(session.commandHistory.isEmpty())
        assertNull(session.historyIndex)
        assertEquals("", session.ghostText)
        assertNull(session.tabSuggestions)
        assertNull(session.remainingSeconds)
        assertEquals("Radio> ", session.promptText)
        assertEquals(linesBefore, session.outputLines)
        assertTrue(bridge.calls.isEmpty())
        assertTrue(session.isLocalSession)
    }

    @Test
    fun tabCompletingAnEmptyCliLineDoesNotRadio() = runTest {
        val bridge = FakeLocalBridge()
        var contactReads = 0
        var channelReads = 0
        val session = ToolsCliSession(
            deviceName = "Radio",
            messages = testMessages(),
            fetchContacts = {
                contactReads += 1
                emptyList()
            },
            fetchChannels = {
                channelReads += 1
                emptyList()
            },
            options = ToolsCliSession.Options(local = bridge),
        )
        assertEquals("", session.currentInput)
        assertTrue(session.commandHistory.isEmpty())
        assertNull(session.historyIndex)
        assertEquals("", session.ghostText)
        assertNull(session.tabSuggestions)
        assertNull(session.tabSelectionIndex)
        assertNull(session.remainingSeconds)
        assertEquals("Radio> ", session.promptText)
        assertTrue(bridge.calls.isEmpty())
        assertEquals(0, contactReads)
        assertEquals(0, channelReads)
        val linesBefore = session.outputLines
        val expected = CLICompletionEngine().completions("", isLocal = true)
        assertTrue(expected.size > 1)

        session.tabComplete()

        assertEquals("", session.currentInput)
        assertTrue(session.commandHistory.isEmpty())
        assertNull(session.historyIndex)
        assertEquals("", session.ghostText)
        assertEquals(expected, session.tabSuggestions)
        assertNull(session.tabSelectionIndex)
        assertNull(session.remainingSeconds)
        assertEquals("Radio> ", session.promptText)
        assertEquals(linesBefore, session.outputLines)
        assertTrue(bridge.calls.isEmpty())
        assertEquals(0, contactReads)
        assertEquals(0, channelReads)
        assertTrue(session.isLocalSession)
    }

    @Test
    fun stoppingAnIdleCliCountdownDoesNotRadio() = runTest {
        val bridge = FakeLocalBridge()
        val session = localSession(bridge)
        assertNull(session.remainingSeconds)
        assertEquals("", session.currentInput)
        assertTrue(session.commandHistory.isEmpty())
        assertNull(session.historyIndex)
        assertEquals("", session.ghostText)
        assertNull(session.tabSuggestions)
        assertEquals("Radio> ", session.promptText)
        assertTrue(bridge.calls.isEmpty())
        val linesBefore = session.outputLines

        session.stopCountdown()

        assertNull(session.remainingSeconds)
        assertEquals("", session.currentInput)
        assertTrue(session.commandHistory.isEmpty())
        assertNull(session.historyIndex)
        assertEquals("", session.ghostText)
        assertNull(session.tabSuggestions)
        assertEquals("Radio> ", session.promptText)
        assertEquals(linesBefore, session.outputLines)
        assertTrue(bridge.calls.isEmpty())
        assertTrue(session.isLocalSession)
    }

    @Test
    fun localVerAndBoardReportCapabilities() = runTest {
        val session = localSession(FakeLocalBridge())
        session.executeCommand("ver")
        assertEquals("1.11.0 (Build: abc123)", lastResponse(session))
        session.executeCommand("board")
        assertEquals("MyBoard", lastResponse(session))
    }

    @Test
    fun localGetReadsTypedFields() = runTest {
        val session = localSession(FakeLocalBridge())
        session.executeCommand("get name")
        assertEquals("> Radio Node", lastResponse(session))
        session.executeCommand("get tx")
        assertEquals("> 22", lastResponse(session))
        session.executeCommand("get bat")
        assertEquals("> 4100 mV", lastResponse(session))
    }

    @Test
    fun localSetNameWritesAndUpdatesPrompt() = runTest {
        val bridge = FakeLocalBridge()
        val session = localSession(bridge)
        session.executeCommand("set name Field Node")
        assertEquals("OK", lastResponse(session))
        assertTrue(bridge.calls.contains("setName:Field Node"))
        assertTrue(session.promptText.startsWith("Field Node"))
    }

    @Test
    fun localAdvertUsesFloodFlag() = runTest {
        val bridge = FakeLocalBridge()
        val session = localSession(bridge)
        session.executeCommand("advert")
        assertEquals("OK - zero-hop advert sent", lastResponse(session))
        session.executeCommand("floodadv")
        assertEquals("OK - flood advert sent", lastResponse(session))
        assertEquals(listOf("advert:false", "advert:true"), bridge.calls)
    }

    @Test
    fun localRebootRequiresConfirmationThenRuns() = runTest {
        val bridge = FakeLocalBridge()
        val session = localSession(bridge)
        session.executeCommand("reboot")
        // No reboot yet; the prompt now asks to confirm.
        assertFalse(bridge.calls.contains("reboot"))
        assertTrue(session.promptText.contains("confirm reboot?"))
        session.executeCommand("yes")
        assertEquals("OK - rebooting", lastResponse(session))
        assertTrue(bridge.calls.contains("reboot"))
    }

    @Test
    fun localRebootDeclinedCancels() = runTest {
        val bridge = FakeLocalBridge()
        val session = localSession(bridge)
        session.executeCommand("reboot")
        session.executeCommand("no")
        assertFalse(bridge.calls.contains("reboot"))
        assertEquals(CLIOutputType.ERROR, session.outputLines.last().type)
        assertTrue(session.outputLines.last().text.contains("cancelled"))
    }

    @Test
    fun localCustomVarDumpAndReadAndWrite() = runTest {
        val bridge =
            FakeLocalBridge().apply {
                customVars["gps"] = "1"
                customVars["label"] = "hi"
            }
        val session = localSession(bridge)
        session.executeCommand("get custom")
        assertEquals("2 vars\ngps=1\nlabel=hi", lastResponse(session))
        session.executeCommand("get gps")
        assertEquals("> 1", lastResponse(session))
        session.executeCommand("get missing")
        assertEquals("Unknown var missing", lastResponse(session))
        session.executeCommand("set color blue")
        assertEquals("Var color set to blue", lastResponse(session))
        assertEquals("blue", bridge.customVars["color"])
    }

    @Test
    fun localCustomVarRejectionReportsUnknown() = runTest {
        val bridge = FakeLocalBridge().apply { rejectCustomVar = true }
        val session = localSession(bridge)
        session.executeCommand("set color blue")
        assertEquals("can't find custom var", lastResponse(session))
    }

    @Test
    fun localParseErrorsRenderUsageAndValidation() = runTest {
        val session = localSession(FakeLocalBridge())
        session.executeCommand("get")
        assertEquals("Usage: get <key>", session.outputLines.last().text)
        session.executeCommand("set tx 500")
        assertEquals("Invalid value", session.outputLines.last().text)
        session.executeCommand("set a:b 1")
        assertEquals("Keys can't contain ':' or ','; values can't contain ','", session.outputLines.last().text)
    }

    @Test
    fun localClockReadsRadioTime() = runTest {
        val session = localSession(FakeLocalBridge(time = 0))
        session.executeCommand("clock")
        assertEquals("00:00 - 1/1/1970 UTC", lastResponse(session))
    }

    @Test
    fun localClockSyncWritesPhoneTimeAndEchoesIt() = runTest {
        val bridge = FakeLocalBridge()
        val session = localSession(bridge)
        session.executeCommand("clock sync")
        assertEquals("OK - clock set: 00:00 - 1/1/1970 UTC", lastResponse(session))
        assertTrue(bridge.calls.contains("syncClock"))
    }
}

private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

private fun localSession(bridge: FakeLocalBridge): ToolsCliSession = ToolsCliSession(
    deviceName = "Radio",
    messages = testMessages(),
    fetchContacts = { emptyList() },
    fetchChannels = { emptyList() },
    options = ToolsCliSession.Options(local = bridge),
)

private fun lastResponse(session: ToolsCliSession) = session.outputLines.last { it.type == CLIOutputType.RESPONSE }.text

private class FakeLocalBridge(
    private val info: SelfInfo = testSelfInfo(),
    private val capabilities: DeviceCapabilities = testCapabilities(),
    private val time: Long = 0,
    private val battery: Int = 4100,
    private val failSyncClockWith: Throwable? = null,
    private val failGetCustomVarsWith: Throwable? = null,
) : ToolsCliLocalBridge {
    val calls = mutableListOf<String>()
    val customVars = mutableMapOf<String, String>()
    var rejectCustomVar = false
    private var currentName = info.name

    override suspend fun selfInfo(): SelfInfo = info.copy(name = currentName)
    override suspend fun deviceCapabilities(): DeviceCapabilities = capabilities
    override suspend fun getTime(): Long = time
    override suspend fun syncClock(): Long {
        calls += "syncClock"
        failSyncClockWith?.let { throw it }
        return time
    }
    override suspend fun batteryMillivolts(): Int = battery
    override suspend fun setName(name: String): String {
        calls += "setName:$name"
        currentName = name
        return name
    }
    override suspend fun setLatitude(latitude: Double) {
        calls += "setLat:$latitude"
    }

    override suspend fun setLongitude(longitude: Double) {
        calls += "setLon:$longitude"
    }

    override suspend fun setTxPower(power: Int) {
        calls += "setTx:$power"
    }
    override suspend fun setRadio(frequencyMHz: Double, bandwidthKHz: Double, spreadingFactor: Int, codingRate: Int) {
        calls += "setRadio:$frequencyMHz,$bandwidthKHz,$spreadingFactor,$codingRate"
    }
    override suspend fun setFrequency(frequencyMHz: Double) {
        calls += "setFreq:$frequencyMHz"
    }
    override suspend fun setMultiAcks(value: Int) {
        calls += "setMultiAcks:$value"
    }
    override suspend fun setPathHashMode(mode: Int) {
        calls += "setPathHashMode:$mode"
    }
    override suspend fun getCustomVars(): Map<String, String> {
        failGetCustomVarsWith?.let { throw it }
        return customVars.toMap()
    }
    override suspend fun setCustomVar(key: String, value: String): Boolean {
        if (rejectCustomVar) return false
        customVars[key] = value
        return true
    }
    override suspend fun sendAdvert(flood: Boolean) {
        calls += "advert:$flood"
    }
    override suspend fun reboot() {
        calls += "reboot"
    }
}

private fun emptySession(): ToolsCliSession = ToolsCliSession(
    deviceName = "Radio",
    messages = testMessages(),
    fetchContacts = { emptyList() },
    fetchChannels = { emptyList() },
)

/** A local session already logged in to a single repeater, for remote-session behavior tests. */
private suspend fun loggedInRemoteSession(bridge: FakeRemoteBridge): ToolsCliSession {
    val session = ToolsCliSession(
        deviceName = "Radio",
        messages = testMessages(),
        fetchContacts = { listOf(contact("R1", ContactType.REPEATER)) },
        fetchChannels = { emptyList() },
        options = ToolsCliSession.Options(remote = bridge),
    )
    session.executeCommand("login R1")
    return session
}

private class FakeRemoteBridge(
    private val timeoutCommandPrefix: String? = null,
    private val storedPassword: String? = "secret",
) : ToolsCliRemoteBridge {
    val commands = mutableListOf<String>()
    var loginPathLength: Int? = null
    var loginPassword: String? = null
    private val sessionId = Uuid.normalized("B0000000-0000-0000-0000-0000000000AA")

    override suspend fun radioId(): Uuid = radio
    override suspend fun retrievePassword(publicKey: ByteArray): String? = storedPassword
    override suspend fun deletePassword(publicKey: ByteArray) = Unit
    override suspend fun storePassword(password: String, publicKey: ByteArray) = Unit
    override suspend fun createSession(radioID: Uuid, contact: ContactDto): Uuid = sessionId
    override suspend fun login(
        sessionID: Uuid,
        password: String,
        pathLength: Int,
        onTimeoutKnown: (suspend (Int) -> Unit)?,
    ): Boolean {
        loginPathLength = pathLength
        loginPassword = password
        return true
    }
    override suspend fun logout(sessionID: Uuid) = Unit
    override suspend fun sendRawCommand(sessionID: Uuid, command: String, timeout: Duration?): String {
        commands += command
        if (timeoutCommandPrefix != null && command.startsWith(timeoutCommandPrefix)) {
            throw RemoteNodeError.Timeout
        }
        return "> $command ok"
    }
}

private fun testMessages() = toolsCliTestMessages()

private fun contact(name: String, type: ContactType): ContactDto = ContactDto(
    id = Uuid.normalized("C0000000-0000-0000-0000-00000000000${type.raw}"),
    radioID = radio,
    publicKey = ByteArray(32) { type.raw.toByte() },
    name = name,
    typeRawValue = type.raw,
    flags = 0,
    outPathLength = FLOOD_SENTINEL,
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

private fun channel(index: Int, name: String): ChannelDto = ChannelDto(
    id = Uuid.normalized("D0000000-0000-0000-0000-00000000000$index"),
    radioID = radio,
    index = index,
    name = name,
    secret = ByteArray(16),
    isEnabled = true,
    unreadCount = 0,
)

private fun testSelfInfo(): SelfInfo = SelfInfo(
    advertisementType = 1,
    txPower = 22,
    maxTxPower = 30,
    publicKey = ByteArray(32) { 0xAB.toByte() },
    latitude = 47.49,
    longitude = -120.33,
    multiAcks = 1,
    advertisementLocationPolicy = 0,
    telemetryModeEnvironment = 0,
    telemetryModeLocation = 0,
    telemetryModeBase = 0,
    manualAddContacts = false,
    radioFrequency = 869.525,
    radioBandwidth = 250.0,
    radioSpreadingFactor = 11,
    radioCodingRate = 5,
    name = "Radio Node",
)

private fun testCapabilities(): DeviceCapabilities = DeviceCapabilities(
    firmwareVersion = 7,
    maxContacts = 100,
    maxChannels = 8,
    blePin = 0,
    firmwareBuild = "abc123",
    model = "MyBoard",
    version = "1.11.0",
    clientRepeat = false,
    pathHashMode = 1,
)

/** The CLI copy fixture shared by the session and terminal-host tests. */
internal fun toolsCliTestMessages() = ToolsCliMessages(
    welcomeLine1 = "MeshCore One CLI",
    welcomeConnected = { "Connected to $it" },
    welcomeHint = "Type help",
    promptSuffix = ">",
    unknownCommand = "Unknown command:",
    notConnected = "Not Connected",
    disconnected = "disconnected",
    nodesHeader = { "Nodes ($it):" },
    channelsHeader = { "Channels ($it):" },
    noNodes = "No nodes found",
    noChannels = "No channels found",
    channelEmpty = "empty",
    kindRepeater = "Repeater",
    kindRoom = "Room",
    routeFlood = "Flood",
    routeDirect = "Direct",
    routeHops = { "$it hops" },
    helpCommonLines = listOf("Available commands:", "  login", "  help"),
    helpLocalLines = listOf("  nodes", "Local radio commands:"),
    helpRepeaterLines = listOf("Repeater commands:", "  ver, clock, reboot"),
    sessionListHeader = "Active sessions:",
    sessionLocal = "local",
    sessionSwitched = "Switched to",
    remoteLoginHint = "Use Nodes CLI for remote",
    loginUsage = "Usage: login [-f] <node>",
    nodeNotFound = "Node not found:",
    passwordRequired = "Password required",
    passwordPrompt = "Password:",
    loggingIn = { "Logging in... (${it}s)" },
    loginSuccess = "Logged in to",
    loginFailed = "Login failed:",
    loginFailedAuth = "Authentication failed",
    logoutSuccess = "Logged out",
    notLoggedIn = "Not logged in",
    sessionNotFound = "Session not found:",
    timeout = "Timeout",
    commandTimeout = "Request timed out",
    rebootSent = "Reboot command sent",
    cancelled = "Command cancelled",
    usageGet = "Usage: get <key>",
    usageSet = "Usage: set <key> <value>",
    usageSetRadio = "Usage: set radio <freq>,<bw>,<sf>,<cr>",
    invalidValue = "Invalid value",
    invalidCustomVarToken = "Keys can't contain ':' or ','; values can't contain ','",
    confirmPrompt = { "confirm $it? (yes/no):" },
    // The fake resolves nothing, so tests still see the exception text unless a case opts in.
    deviceErrorText = { null },
)

private class CustomVarRefreshProbeError : Error("probe")
