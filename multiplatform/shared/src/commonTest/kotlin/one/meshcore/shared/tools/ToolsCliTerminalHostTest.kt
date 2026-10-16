package one.meshcore.shared.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.tools.ui.ToolsCliViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The CLI terminal outlives the screen: reopening the tool must show the same scrollback, history, and
 * remote sessions, and only a reconnect or radio switch may start a fresh one.
 */
class ToolsCliTerminalHostTest {
    private val local = object : ToolsCliLocalBridge {
        override suspend fun selfInfo() = error("unused")
        override suspend fun deviceCapabilities() = error("unused")
        override suspend fun getTime(): Long = 0
        override suspend fun syncClock(): Long = 0
        override suspend fun batteryMillivolts(): Int = 0
        override suspend fun setName(name: String): String = name
        override suspend fun setLatitude(latitude: Double) = Unit
        override suspend fun setLongitude(longitude: Double) = Unit
        override suspend fun setTxPower(power: Int) = Unit
        override suspend fun setRadio(
            frequencyMHz: Double,
            bandwidthKHz: Double,
            spreadingFactor: Int,
            codingRate: Int,
        ) = Unit
        override suspend fun setFrequency(frequencyMHz: Double) = Unit
        override suspend fun setMultiAcks(value: Int) = Unit
        override suspend fun setPathHashMode(mode: Int) = Unit
        override suspend fun getCustomVars(): Map<String, String> = emptyMap()
        override suspend fun setCustomVar(key: String, value: String): Boolean = true
        override suspend fun sendAdvert(flood: Boolean) = Unit
        override suspend fun reboot() = Unit
    }

    @AfterTest
    fun tearDown() = ToolsCliTerminalHost.reset()

    private fun create(scope: CoroutineScope, name: String = "Radio") = ToolsCliViewModel(
        scope = scope,
        deviceName = name,
        messages = toolsCliTestMessages(),
        fetchContacts = { emptyList() },
        fetchChannels = { emptyList() },
        options = ToolsCliSession.Options(remote = null, local = local),
    )

    @Test
    fun reopeningTheToolKeepsTheSameTerminal() = runTest {
        val key = ToolsCliTerminalKey("Radio", remote = null, local = local)
        val first = ToolsCliTerminalHost.terminal(key) { create(it) }
        first.input.setInput("get name")
        // Leaving the tool and coming back rebuilds the composable, not the terminal.
        val second = ToolsCliTerminalHost.terminal(ToolsCliTerminalKey("Radio", null, local)) { create(it) }

        assertSame(first, second, "navigating away and back must not wipe the session")
        assertEquals("get name", second.state.value.currentInput)
        assertTrue(second.state.value.outputLines.isNotEmpty(), "the welcome banner and scrollback survive")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refreshingLocalizedCopyKeepsTerminalStateAndUpdatesFutureOutput() = runTest {
        val vm = create(this)
        vm.input.setInput("draft")
        val originalLines = vm.state.value.outputLines
        val localized = toolsCliTestMessages().copy(
            promptSuffix = "#",
            helpCommonLines = listOf("Localized commands:"),
            helpLocalLines = listOf("localized local help"),
        )

        vm.refreshLocalizedCopy(deviceName = "Funkgerät", messages = localized)
        runCurrent()

        assertEquals("draft", vm.state.value.currentInput)
        assertEquals(originalLines, vm.state.value.outputLines)
        assertEquals("Funkgerät# ", vm.state.value.promptText)

        vm.input.setInput("help")
        vm.submit()
        runCurrent()
        assertEquals("localized local help", vm.state.value.outputLines.last().text)
    }

    @Test
    fun reconnectAndRadioSwitchStartAFreshTerminal() = runTest {
        val first = ToolsCliTerminalHost.terminal(ToolsCliTerminalKey("Radio", null, local)) { create(it) }
        // A reconnect rebuilds the per-connection bridges, which iOS answers with reset().
        val afterReconnect = ToolsCliTerminalHost.terminal(ToolsCliTerminalKey("Radio", null, local = null)) {
            create(it)
        }
        assertNotSame(first, afterReconnect)

        val afterSwitch = ToolsCliTerminalHost.terminal(ToolsCliTerminalKey("Other", null, local = null)) {
            create(it, name = "Other")
        }
        assertNotSame(afterReconnect, afterSwitch, "a radio switch starts a fresh terminal")
    }

    @Test
    fun resetDropsTheRetainedTerminal() = runTest {
        val key = ToolsCliTerminalKey("Radio", null, local)
        val first = ToolsCliTerminalHost.terminal(key) { create(it) }
        ToolsCliTerminalHost.reset()

        assertNotSame(first, ToolsCliTerminalHost.terminal(key) { create(it) })
    }

    @Test
    fun replacingTheTerminalCancelsItsScope() {
        var oldScope: CoroutineScope? = null
        var newScope: CoroutineScope? = null

        ToolsCliTerminalHost.terminal(ToolsCliTerminalKey("Radio", null, local)) {
            oldScope = it
            create(it)
        }
        ToolsCliTerminalHost.terminal(ToolsCliTerminalKey("Other", null, local)) {
            newScope = it
            create(it, name = "Other")
        }

        val oldJob = assertNotNull(oldScope?.coroutineContext?.get(Job))
        val newJob = assertNotNull(newScope?.coroutineContext?.get(Job))
        assertFalse(oldJob.isActive)
        assertTrue(newJob.isActive)
    }

    @Test
    fun resetCancelsTheTerminalScope() {
        var retainedScope: CoroutineScope? = null
        ToolsCliTerminalHost.terminal(ToolsCliTerminalKey("Radio", null, local)) {
            retainedScope = it
            create(it)
        }

        ToolsCliTerminalHost.reset()

        val retainedJob = assertNotNull(retainedScope?.coroutineContext?.get(Job))
        assertFalse(retainedJob.isActive)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun recallingHelpDoesNotRadio() = runTest {
        var radioTouches = 0
        fun unreachable(): Nothing {
            radioTouches += 1
            error("radio")
        }
        val bridge = object : ToolsCliLocalBridge {
            override suspend fun selfInfo() = unreachable()
            override suspend fun deviceCapabilities() = unreachable()
            override suspend fun getTime() = unreachable()
            override suspend fun syncClock() = unreachable()
            override suspend fun batteryMillivolts() = unreachable()
            override suspend fun setName(name: String) = unreachable()
            override suspend fun setLatitude(latitude: Double) = unreachable()
            override suspend fun setLongitude(longitude: Double) = unreachable()
            override suspend fun setTxPower(power: Int) = unreachable()
            override suspend fun setRadio(
                frequencyMHz: Double,
                bandwidthKHz: Double,
                spreadingFactor: Int,
                codingRate: Int,
            ) = unreachable()
            override suspend fun setFrequency(frequencyMHz: Double) = unreachable()
            override suspend fun setMultiAcks(value: Int) = unreachable()
            override suspend fun setPathHashMode(mode: Int) = unreachable()
            override suspend fun getCustomVars() = unreachable()
            override suspend fun setCustomVar(key: String, value: String) = unreachable()
            override suspend fun sendAdvert(flood: Boolean) = unreachable()
            override suspend fun reboot() = unreachable()
        }
        val vm = ToolsCliViewModel(
            scope = this,
            deviceName = "Radio",
            messages = toolsCliTestMessages(),
            fetchContacts = { emptyList() },
            fetchChannels = { emptyList() },
            options = ToolsCliSession.Options(remote = null, local = bridge),
        )
        vm.input.setInput("help")
        vm.submit()
        runCurrent()

        assertEquals(0, radioTouches)
        assertEquals("", vm.state.value.currentInput)
        assertTrue(vm.state.value.outputLines.any { it.text == "Available commands:" })
        assertFalse(vm.state.value.isWaitingForResponse)

        vm.input.historyUp()

        assertEquals("help", vm.state.value.currentInput)
        assertEquals(0, radioTouches)
        assertFalse(vm.state.value.isWaitingForResponse)
    }
}
