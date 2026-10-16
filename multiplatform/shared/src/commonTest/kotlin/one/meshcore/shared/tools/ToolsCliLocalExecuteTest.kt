package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.remotenode.CLIOutputType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A local radio command reports the mapped CLI text and clears the wait. Cancellation leaves no
 * error line. An error is mapped the same way and does not escape the command.
 */
class ToolsCliLocalExecuteTest {
    @Test
    fun aRadioFailureShowsTranslatedTextAndClearsTheWait() = runTest {
        val session = session(ClockFailureBridge(IllegalStateException("device error code=6")))

        session.executeCommand("clock sync")

        assertFalse(session.isWaitingForResponse)
        assertEquals(listOf("The radio rejected the request."), errorLines(session))
    }

    @Test
    fun aCancelledLocalCommandPropagatesAndClearsTheWait() = runTest {
        val cancelled = CancellationException("superseded")
        val session = session(ClockFailureBridge(cancelled))

        val thrown = assertFailsWith<CancellationException> {
            session.executeCommand("clock sync")
        }

        assertSame(cancelled, thrown)
        assertFalse(session.isWaitingForResponse)
        assertTrue(errorLines(session).isEmpty())
    }

    @Test
    fun aLocalCommandErrorIsMappedAndClearsTheWait() = runTest {
        val probe = LocalCommandProbeError()
        val session = session(ClockFailureBridge(probe))

        session.executeCommand("clock sync")

        assertFalse(session.isWaitingForResponse)
        assertEquals(listOf("probe"), errorLines(session))
    }

    private fun session(bridge: ToolsCliLocalBridge) = ToolsCliSession(
        deviceName = "Radio",
        messages = toolsCliTestMessages().copy(deviceErrorText = { error ->
            if (error is IllegalStateException) "The radio rejected the request." else null
        }),
        fetchContacts = { emptyList() },
        fetchChannels = { emptyList() },
        options = ToolsCliSession.Options(local = bridge),
    )

    private fun errorLines(session: ToolsCliSession) =
        session.outputLines.filter { it.type == CLIOutputType.ERROR }.map { it.text }
}

private class LocalCommandProbeError : Error("probe")

private class ClockFailureBridge(private val failure: Throwable) : ToolsCliLocalBridge {
    override suspend fun syncClock(): Long = throw failure

    override suspend fun selfInfo(): SelfInfo = error("unused")

    override suspend fun deviceCapabilities(): DeviceCapabilities = error("unused")

    override suspend fun getTime(): Long = error("unused")

    override suspend fun batteryMillivolts(): Int = error("unused")

    override suspend fun getCustomVars(): Map<String, String> = error("unused")

    override suspend fun setCustomVar(key: String, value: String): Boolean = error("unused")

    override suspend fun sendAdvert(flood: Boolean): Unit = error("unused")

    override suspend fun reboot(): Unit = error("unused")

    override suspend fun setName(name: String): String = error("unused")

    override suspend fun setLatitude(latitude: Double): Unit = error("unused")

    override suspend fun setLongitude(longitude: Double): Unit = error("unused")

    override suspend fun setTxPower(power: Int): Unit = error("unused")

    override suspend fun setRadio(
        frequencyMHz: Double,
        bandwidthKHz: Double,
        spreadingFactor: Int,
        codingRate: Int,
    ): Unit = error("unused")

    override suspend fun setFrequency(frequencyMHz: Double): Unit = error("unused")

    override suspend fun setMultiAcks(value: Int): Unit = error("unused")

    override suspend fun setPathHashMode(mode: Int): Unit = error("unused")
}
