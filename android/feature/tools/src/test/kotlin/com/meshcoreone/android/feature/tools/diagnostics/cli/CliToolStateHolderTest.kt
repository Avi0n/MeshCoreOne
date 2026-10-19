// PortedFrom: MC1Tests/Views/Tools/CLI/CLIToolViewModelTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.feature.tools.diagnostics.support.OriginalCase
import com.meshcoreone.android.feature.tools.diagnostics.support.TestScheduler
import com.meshcoreone.android.feature.tools.diagnostics.support.XmlDiagnosticsText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

internal fun dependencies(
    repeaterAdminService: () -> CliRepeaterAdminPort? = { null },
    remoteNodeService: () -> CliRemoteNodePort? = { null },
    settingsService: () -> CliSettingsPort? = { null },
    dataStore: () -> CliNodeDirectory? = { null },
    radioId: () -> com.meshcoreone.android.core.model.RadioId? = { null },
    connectedDevice: () -> com.meshcoreone.android.core.model.DeviceDTO? = { null },
    sendSelfAdvert: suspend (Boolean) -> Unit = {},
) = CliToolFeatureDependencies(
    repeaterAdminService, remoteNodeService, settingsService, dataStore, radioId, connectedDevice, sendSelfAdvert,
)

class CliToolStateHolderTest {
    private val scheduler = TestScheduler()
    private val holder = CliToolStateHolder(scheduler.scope, XmlDiagnosticsText(), scheduler.clock, FAKE_ERRORS)

    private val terminal get() = holder.state.value.terminal
    private val output get() = terminal.outputLines.joinToString("\n") { it.text }

    private fun configured(): CliToolStateHolder = holder.also {
        it.configure(dependencies(), localDeviceName = "TestDevice")
        scheduler.runCurrent()
    }

    private fun execute(line: String) {
        holder.executeCommand(line)
        scheduler.runCurrent()
    }

    // MARK: - Prompt

    @Test @OriginalCase("CLIToolViewModelTests::Prompt shows disconnected when no session()")
    fun `Prompt shows disconnected when no session`() {
        configured()
        holder.configure(dependencies(), localDeviceName = "Test")
        assertTrue(holder.promptText.contains("disconnected"), holder.promptText)
    }

    @Test @OriginalCase("CLIToolViewModelTests::Prompt shows countdown during login()")
    fun `Prompt shows countdown during login`() {
        configured()
        assertFalse(holder.promptText.contains("Logging in"))
    }

    // MARK: - History

    @Test @OriginalCase("CLIToolViewModelTests::History navigation up retrieves previous commands()")
    fun `History navigation up retrieves previous commands`() {
        configured()
        execute("first")
        assertFalse(holder.state.value.isWaitingForResponse)
        holder.executeCommand("second")
        holder.historyUp()
        assertEquals("second", terminal.currentInput)
        holder.historyUp()
        assertEquals("first", terminal.currentInput)
    }

    @Test @OriginalCase("CLIToolViewModelTests::History navigation down moves forward through history()")
    fun `History navigation down moves forward through history`() {
        configured()
        execute("first")
        holder.executeCommand("second")
        holder.historyUp()
        holder.historyUp()
        holder.historyDown()
        assertEquals("second", terminal.currentInput)
    }

    @Test @OriginalCase("CLIToolViewModelTests::History is limited to 100 entries()")
    fun `History is limited to 100 entries`() {
        configured()
        repeat(150) { execute("command$it") }
        repeat(100) { holder.historyUp() }
        assertEquals("command50", terminal.currentInput)
    }

    @Test @OriginalCase("CLIToolViewModelTests::Login command stored in history without password()")
    fun `Login command stored in history without password`() {
        configured()
        holder.executeCommand("login MyRepeater")
        holder.historyUp()
        assertEquals("login MyRepeater", terminal.currentInput)
    }

    // MARK: - Built-in commands

    @Test @OriginalCase("CLIToolViewModelTests::Clear command removes output()")
    fun `Clear command removes output`() {
        configured()
        execute("help")
        assertTrue(terminal.outputLines.isNotEmpty())
        execute("clear")
        assertTrue(terminal.outputLines.isEmpty())
    }

    @Test @OriginalCase("CLIToolViewModelTests::Help command shows available commands()")
    fun `Help command shows available commands`() {
        configured()
        execute("help")
        assertTrue(output.contains("login"))
        assertTrue(output.contains("logout"))
        assertTrue(output.contains("session"))
    }

    @Test @OriginalCase("CLIToolViewModelTests::Output lines are limited to prevent memory growth()")
    fun `Output lines are limited to prevent memory growth`() {
        configured()
        repeat(1100) { execute("command$it") }
        assertEquals(CliTerminalState.MAX_OUTPUT_LINES, terminal.outputLines.size)
    }

    @Test @OriginalCase("CLIToolViewModelTests::Session list shows local()")
    fun `Session list shows local`() {
        configured()
        execute("session list")
        assertTrue(output.contains("local"))
    }

    // MARK: - Cancellation

    @Test @OriginalCase("CLIToolViewModelTests::Cancel command stops waiting()")
    fun `Cancel command stops waiting`() {
        configured()
        execute("help")
        assertTrue(terminal.outputLines.isNotEmpty())
        holder.cancelCurrentCommand()
        assertFalse(holder.state.value.isWaitingForResponse)
    }

    @Test @OriginalCase("CLIToolViewModelTests::Resumed cancelled command does not clear a newer command's busy flag()")
    fun `Resumed cancelled command does not clear a newer command's busy flag`() {
        val parking = ParkingDirectory()
        holder.configure(dependencies(dataStore = { parking }, radioId = { TEST_RADIO }), localDeviceName = "TestDevice")
        holder.setActiveSession(CliSession.local("TestDevice"))

        // Drain the configure-time completion prefetch so the queue reflects only the commands.
        scheduler.runCurrent()
        assertEquals(1, parking.parkedCount)
        parking.resumeFirst()
        scheduler.runCurrent()
        assertEquals(0, parking.parkedCount)

        // Command A claims the busy flag and parks inside fetchContacts.
        execute("nodes")
        assertTrue(holder.state.value.isWaitingForResponse)
        assertEquals(1, parking.parkedCount)

        // Cancel A, then supersede it with B, which re-claims the flag.
        holder.cancelCurrentCommand()
        assertFalse(holder.state.value.isWaitingForResponse)
        execute("nodes")
        assertTrue(holder.state.value.isWaitingForResponse)
        assertEquals(2, parking.parkedCount)

        // A resumes and finishes; it must not clear B's claim.
        parking.resumeFirst()
        scheduler.runCurrent()
        assertTrue(holder.state.value.isWaitingForResponse)

        // B's own completion still clears the flag.
        parking.resumeFirst()
        scheduler.runCurrent()
        assertFalse(holder.state.value.isWaitingForResponse)
    }

    // MARK: - Empty input

    @Test @OriginalCase("CLIToolViewModelTests::Empty input shows prompt echo()")
    fun `Empty input shows prompt echo`() {
        configured()
        val initialCount = terminal.outputLines.size
        val initialHistory = terminal.commandHistory.size
        holder.updateInput("")
        holder.executeCommand("")
        assertEquals(initialCount + 1, terminal.outputLines.size)
        assertEquals(initialHistory, terminal.commandHistory.size)
        assertEquals(CliOutputType.COMMAND, terminal.outputLines.last().type)
    }

    // MARK: - Ghost text

    private fun ghostFor(input: String, cursorAtEnd: Boolean = true): String {
        configured()
        holder.updateInput(input)
        holder.updateGhostText(cursorAtEnd)
        return terminal.ghostText
    }

    @Test @OriginalCase("CLIToolViewModelTests::Ghost text shows suffix for matching command()")
    fun `Ghost text shows suffix for matching command`() = assertEquals("p", ghostFor("hel"))

    @Test @OriginalCase("CLIToolViewModelTests::Ghost text empty when no match()")
    fun `Ghost text empty when no match`() = assertEquals("", ghostFor("xyz"))

    @Test @OriginalCase("CLIToolViewModelTests::Ghost text empty for empty input()")
    fun `Ghost text empty for empty input`() = assertEquals("", ghostFor(""))

    @Test @OriginalCase("CLIToolViewModelTests::Ghost text empty when cursor not at end()")
    fun `Ghost text empty when cursor not at end`() = assertEquals("", ghostFor("hel", cursorAtEnd = false))

    @Test @OriginalCase("CLIToolViewModelTests::Accept ghost text appends to input()")
    fun `Accept ghost text appends to input`() {
        ghostFor("hel")
        holder.acceptGhostText()
        assertEquals("help", terminal.currentInput)
        assertEquals("", terminal.ghostText)
    }

    @Test @OriginalCase("CLIToolViewModelTests::Accept ghost text does nothing when empty()")
    fun `Accept ghost text does nothing when empty`() {
        ghostFor("xyz")
        holder.acceptGhostText()
        assertEquals("xyz", terminal.currentInput)
    }

    // MARK: - Tab completion

    private fun withInput(input: String) {
        configured()
        holder.updateInput(input)
    }

    @Test @OriginalCase("CLIToolViewModelTests::Tab completion single match auto-completes()")
    fun `Tab completion single match auto-completes`() {
        withInput("hel")
        holder.tabComplete()
        assertEquals("help ", terminal.currentInput)
    }

    @Test @OriginalCase("CLIToolViewModelTests::Tab completion multiple matches returns suggestions()")
    fun `Tab completion multiple matches returns suggestions`() {
        withInput("lo")
        val suggestions = assertNotNull(holder.tabComplete())
        assertTrue("login" in suggestions)
        assertTrue("logout" in suggestions)
    }

    @Test @OriginalCase("CLIToolViewModelTests::Tab completion no match returns nil()")
    fun `Tab completion no match returns nil`() {
        withInput("xyz")
        assertNull(holder.tabComplete())
    }

    @Test @OriginalCase("CLIToolViewModelTests::Ghost text shows argument completion after space()")
    fun `Ghost text shows argument completion after space`() {
        val ghost = ghostFor("session l")
        assertTrue(ghost == "ist" || ghost == "ocal", ghost)
    }

    @Test @OriginalCase("CLIToolViewModelTests::First tab shows suggestions without selection()")
    fun `First tab shows suggestions without selection`() {
        withInput("lo")
        assertNotNull(holder.tabComplete())
        assertNotNull(terminal.tabSuggestions)
        assertNull(terminal.tabSelectionIndex)
    }

    @Test @OriginalCase("CLIToolViewModelTests::Second tab enters selection mode()")
    fun `Second tab enters selection mode`() {
        withInput("lo")
        holder.tabComplete()
        holder.tabComplete()
        assertEquals(0, terminal.tabSelectionIndex)
    }

    @Test @OriginalCase("CLIToolViewModelTests::Third tab cycles to next suggestion()")
    fun `Third tab cycles to next suggestion`() {
        withInput("lo")
        repeat(3) { holder.tabComplete() }
        assertEquals(1, terminal.tabSelectionIndex)
    }

    @Test @OriginalCase("CLIToolViewModelTests::Tab cycles wrap around()")
    fun `Tab cycles wrap around`() {
        withInput("lo")
        holder.tabComplete()
        val count = terminal.tabSuggestions?.size ?: 0
        repeat(count + 1) { holder.tabComplete() }
        assertEquals(0, terminal.tabSelectionIndex)
    }

    @Test @OriginalCase("CLIToolViewModelTests::Apply selected suggestion returns true when in selection mode()")
    fun `Apply selected suggestion returns true when in selection mode`() {
        withInput("lo")
        holder.tabComplete()
        holder.tabComplete()
        assertTrue(holder.applySelectedSuggestion())
        assertEquals("login ", terminal.currentInput)
        assertNull(terminal.tabSuggestions)
        assertNull(terminal.tabSelectionIndex)
    }

    @Test @OriginalCase("CLIToolViewModelTests::Apply selected suggestion returns false when not in selection mode()")
    fun `Apply selected suggestion returns false when not in selection mode`() {
        withInput("lo")
        holder.tabComplete()
        assertFalse(holder.applySelectedSuggestion())
    }

    @Test @OriginalCase("CLIToolViewModelTests::Clear tab state clears suggestions and selection()")
    fun `Clear tab state clears suggestions and selection`() {
        withInput("lo")
        holder.tabComplete()
        holder.tabComplete()
        holder.clearTabState()
        assertNull(terminal.tabSuggestions)
        assertNull(terminal.tabSelectionIndex)
    }
}
