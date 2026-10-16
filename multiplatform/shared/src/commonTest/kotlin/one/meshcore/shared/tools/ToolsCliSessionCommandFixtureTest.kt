package one.meshcore.shared.tools

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.remotenode.CLIOutputType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ver` reaches the terminal through [ToolsCliSession] and the local bridge fake. The fake records
 * the capabilities read and opens no radio. A session with no bridge refuses the same command.
 */
class ToolsCliSessionCommandFixtureTest {
    @Test
    fun verPrintsTheBridgeVersionOnTheTerminal() = runTest {
        val bridge = RecordingLocalBridge()
        val session = session(bridge)

        session.executeCommand("ver")

        assertEquals(1, bridge.capabilityReads)
        assertEquals(VERSION_LINE, responseLines(session).last())
        assertEquals("Radio> ver", commandLines(session).single())
        assertTrue(errorLines(session).isEmpty())
    }

    @Test
    fun verWhileDisconnectedRefusesWithoutReadingTheBridge() = runTest {
        val messages = toolsCliTestMessages()
        val bridge = RecordingLocalBridge()
        val session = session(bridge = null)

        session.executeCommand("ver")

        assertEquals(0, bridge.capabilityReads)
        assertEquals(listOf(messages.notConnected), errorLines(session))
        assertEquals("${messages.disconnected}${messages.promptSuffix} ver", commandLines(session).single())
        assertTrue(responseLines(session).none { it == VERSION_LINE })
        assertEquals("${messages.disconnected}${messages.promptSuffix} ", session.promptText)
    }

    private fun session(bridge: ToolsCliLocalBridge?) = ToolsCliSession(
        deviceName = "Radio",
        messages = toolsCliTestMessages(),
        fetchContacts = { emptyList() },
        fetchChannels = { emptyList() },
        options = ToolsCliSession.Options(local = bridge),
    )

    private fun responseLines(session: ToolsCliSession) = lines(session, CLIOutputType.RESPONSE)

    private fun commandLines(session: ToolsCliSession) = lines(session, CLIOutputType.COMMAND)

    private fun errorLines(session: ToolsCliSession) = lines(session, CLIOutputType.ERROR)

    private fun lines(session: ToolsCliSession, type: CLIOutputType) =
        session.outputLines.filter { it.type == type }.map { it.text }

    private companion object {
        const val VERSION_LINE = "1.11.0 (Build: abc123)"
    }
}

private class RecordingLocalBridge : ToolsCliLocalBridge {
    var capabilityReads: Int = 0

    override suspend fun deviceCapabilities(): DeviceCapabilities {
        capabilityReads += 1
        return CAPABILITIES
    }

    override suspend fun selfInfo(): SelfInfo = unused()

    override suspend fun getTime(): Long = unused()

    override suspend fun syncClock(): Long = unused()

    override suspend fun batteryMillivolts(): Int = unused()

    override suspend fun getCustomVars(): Map<String, String> = unused()

    override suspend fun setCustomVar(key: String, value: String): Boolean = unused()

    override suspend fun sendAdvert(flood: Boolean) = unused()

    override suspend fun reboot() = unused()

    override suspend fun setName(name: String): String = unused()

    override suspend fun setLatitude(latitude: Double) = unused()

    override suspend fun setLongitude(longitude: Double) = unused()

    override suspend fun setTxPower(power: Int) = unused()

    override suspend fun setRadio(frequencyMHz: Double, bandwidthKHz: Double, spreadingFactor: Int, codingRate: Int) {
        unused()
    }

    override suspend fun setFrequency(frequencyMHz: Double) = unused()

    override suspend fun setMultiAcks(value: Int) = unused()

    override suspend fun setPathHashMode(mode: Int) = unused()

    private companion object {
        val CAPABILITIES = DeviceCapabilities(
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
    }
}

private fun unused(): Nothing = error("unused")
