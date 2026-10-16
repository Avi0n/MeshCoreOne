package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelSlotReadTest {
    @Test
    fun aConfiguredSlotIsKept() = runTest(UnconfinedTestDispatcher()) {
        val session = FakeChannelSession()
        val recorded = mutableListOf<DebugLogEntryDto>()
        session.slots[SLOT] = channelInfo(SLOT, name = "Ops")
        val reader = ChannelSlotReader(session, logger(recorded))

        val outcome = reader.readSlot(SLOT)

        val configured = assertIs<ChannelSlotReader.SlotOutcome.Configured>(outcome)
        assertEquals("Ops", configured.info.name)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun anUnconfiguredSlotIsClean() = runTest(UnconfinedTestDispatcher()) {
        val session = FakeChannelSession()
        val recorded = mutableListOf<DebugLogEntryDto>()
        session.markUnconfigured(SLOT)
        val reader = ChannelSlotReader(session, logger(recorded))

        val outcome = reader.readSlot(SLOT)

        assertIs<ChannelSlotReader.SlotOutcome.Unconfigured>(outcome)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aTransportFailureIsLoggedAndFailed() = runTest(UnconfinedTestDispatcher()) {
        val session = FakeChannelSession()
        val recorded = mutableListOf<DebugLogEntryDto>()
        session.slotErrors[SLOT] = { ChannelTransportException("dropped") }
        val reader = ChannelSlotReader(session, logger(recorded))

        val outcome = reader.readSlot(SLOT)

        val failed = assertIs<ChannelSlotReader.SlotOutcome.Failed>(outcome)
        assertEquals(ChannelSyncErrorType.TransportError, failed.error.errorType)
        assertEquals(SLOT, failed.error.index)
        val line = recorded.single()
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("Failed to sync channel $SLOT: dropped", line.message)
    }

    @Test
    fun aSilentFailureStillReturnsFailed() = runTest(UnconfinedTestDispatcher()) {
        val session = FakeChannelSession()
        val recorded = mutableListOf<DebugLogEntryDto>()
        session.slotErrors[SLOT] = { ChannelTransportException("dropped") }
        val reader = ChannelSlotReader(session, logger(recorded))

        val outcome = reader.readSlot(SLOT, logFailure = false)

        assertIs<ChannelSlotReader.SlotOutcome.Failed>(outcome)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun anErrorIsLoggedAndFailed() = runTest(UnconfinedTestDispatcher()) {
        val session = FakeChannelSession()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val fault = ChannelSlotReadProbeFault()
        session.slotErrors[SLOT] = { fault }
        val reader = ChannelSlotReader(session, logger(recorded))

        val outcome = reader.readSlot(SLOT)

        val failed = assertIs<ChannelSlotReader.SlotOutcome.Failed>(outcome)
        assertEquals(ChannelSyncErrorType.Unknown, failed.error.errorType)
        assertEquals("probe", failed.error.description)
        assertEquals("Failed to sync channel $SLOT: probe", recorded.single().message)
    }

    @Test
    fun aCancelledReadRethrowsWithoutAWarning() = runTest(UnconfinedTestDispatcher()) {
        val session = FakeChannelSession()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val cancelled = CancellationException("superseded")
        session.slotErrors[SLOT] = { cancelled }
        val reader = ChannelSlotReader(session, logger(recorded))

        val thrown = assertFailsWith<CancellationException> {
            reader.readSlot(SLOT)
        }

        assertSame(cancelled, thrown)
        assertTrue(recorded.isEmpty())
    }

    private fun CoroutineScope.logger(recorded: MutableList<DebugLogEntryDto>) = PersistentLogger(
        scope = this,
        category = "ChannelService",
        record = { recorded += it },
    )
}

private class ChannelSlotReadProbeFault : Error("probe")

private const val SLOT = 4
