package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [ChannelSyncRetry], the connect-path channel phase. A slot that timed out during the first
 * pass has no local row, so the retry is what keeps inbound messages on that slot from being suppressed
 * as an unconfigured channel.
 */
class ChannelSyncRetryTest {
    private val radioID = ChannelTestIds.RADIO

    @Test
    fun aSlotThatTimedOutIsReadAgainAndPersisted() = runTest {
        val session = TimeoutOnceChannelSession(failingIndex = 1)
        session.slots[0] = channelInfo(0, name = "public")
        session.slots[1] = channelInfo(1, name = "recovered")
        val service = testChannelService(session = session)

        val result = ChannelSyncRetry.sync(service, radioID, maxChannels = 2, usePipelinedRead = false)

        assertTrue(result.isComplete)
        assertEquals("recovered", service.getChannel(radioID, 1)?.name)
    }

    @Test
    fun aCleanPassNeverRetries() = runTest {
        val session = FakeChannelSession()
        session.slots[0] = channelInfo(0)
        val service = testChannelService(session = session)

        ChannelSyncRetry.sync(service, radioID, maxChannels = 1, usePipelinedRead = false)

        assertEquals(listOf(0), session.getChannelIndices)
    }

    @Test
    fun aNonRetryableFailureSurvivesTheRetryPass() = runTest {
        val session = TimeoutOnceChannelSession(failingIndex = 1)
        session.slots[1] = channelInfo(1, name = "recovered")
        // A device error on another slot is not retryable, so the phase stays incomplete even after the
        // retry recovers everything it was asked for.
        session.slotErrors[0] = { ChannelDeviceException(DEVICE_ERROR_CODE) }
        val service = testChannelService(session = session)

        val result = ChannelSyncRetry.sync(service, radioID, maxChannels = 2, usePipelinedRead = false)

        assertFalse(result.isComplete)
        assertEquals(listOf(ChannelSyncErrorType.DeviceError(DEVICE_ERROR_CODE)), result.errors.map { it.errorType })
        assertEquals("recovered", service.getChannel(radioID, 1)?.name)
    }

    @Test
    fun aSlotStillDownAfterTheRetryStaysRetryable() = runTest {
        val session = TimeoutOnceChannelSession(failingIndex = 0, timeouts = Int.MAX_VALUE)
        val service = testChannelService(session = session)

        val result = ChannelSyncRetry.sync(service, radioID, maxChannels = 1, usePipelinedRead = false)

        assertEquals(listOf(0), result.retryableIndices)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aThrownRetryBecomesASyntheticTimeoutResult() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val result = syncThrowingOnOccupantChange(IllegalStateException("link drop"), recorded)

        assertEquals(listOf(0), result.retryableIndices)
        assertEquals(ChannelSyncErrorType.Timeout, result.errors.single().errorType)
        assertEquals("link drop", result.errors.single().description)
        val line = recorded.single { it.message.contains(CHANNEL_RETRY_FAILED_PREFIX) }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("link drop"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aThrownRetryErrorBecomesASyntheticTimeoutResult() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val fault = ChannelSyncRetryProbeError()
        val result = syncThrowingOnOccupantChange(fault, recorded)

        assertEquals(listOf(0), result.retryableIndices)
        assertEquals(ChannelSyncErrorType.Timeout, result.errors.single().errorType)
        assertEquals("probe", result.errors.single().description)
        val line = recorded.single { it.message.contains(CHANNEL_RETRY_FAILED_PREFIX) }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("probe"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancelledRetryPropagatesWithoutTheFailureDump() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val cancelled = CancellationException("left")
        val thrown = assertFailsWith<CancellationException> {
            syncThrowingOnOccupantChange(cancelled, recorded)
        }

        assertSame(cancelled, thrown)
        assertTrue(recorded.none { it.message.contains(CHANNEL_RETRY_FAILED_PREFIX) })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.syncThrowingOnOccupantChange(
        thrown: Throwable,
        recorded: MutableList<DebugLogEntryDto>,
    ): ChannelSyncResult {
        val store = InMemoryChannelStore()
        store.saveChannel(radioID, channelInfo(0, name = "old", secretByte = 0x11))
        val session = TimeoutOnceChannelSession(failingIndex = 0)
        session.slots[0] = channelInfo(0, name = "recovered", secretByte = 0xAB)
        val logger = PersistentLogger(
            scope = this,
            category = "ChannelSyncRetry",
            record = { recorded += it },
        )
        val service = testChannelService(
            session = session,
            store = store,
            slotOccupantChanged = ChannelSlotOccupantChanged { _, _ -> throw thrown },
            logger = logger,
        )
        return ChannelSyncRetry.sync(
            service,
            radioID,
            maxChannels = 1,
            usePipelinedRead = false,
            logger = logger,
        )
    }

    private companion object {
        const val DEVICE_ERROR_CODE = 5
    }
}

private class ChannelSyncRetryProbeError : Error("probe")

/**
 * A session whose [failingIndex] answers with a response timeout until the initial pass has exhausted
 * [ChannelService]'s per-slot attempts, then succeeds — the shape of a slot that a retry recovers.
 */
private class TimeoutOnceChannelSession(private val failingIndex: Int, timeouts: Int = FETCH_ATTEMPTS_PER_SLOT) :
    ChannelSession {
    val slots = mutableMapOf<Int, ChannelInfo>()
    val slotErrors = mutableMapOf<Int, () -> Throwable>()

    private var remainingFailures = timeouts

    override suspend fun getChannel(index: Int): ChannelInfo {
        slotErrors[index]?.let { throw it() }
        if (index == failingIndex && remainingFailures > 0) {
            remainingFailures -= 1
            throw ChannelTimeoutException()
        }
        return slots[index] ?: throw ChannelDeviceException(ChannelProtocolErrorCode.NOT_FOUND)
    }

    override suspend fun getChannels(indices: List<Int>): ChannelBatchRead =
        ChannelBatchRead(received = emptyList(), missing = indices)

    override suspend fun setChannel(index: Int, name: String, secret: ByteArray) = Unit

    private companion object {
        const val FETCH_ATTEMPTS_PER_SLOT = 3
    }
}
