package one.meshcore.shared.channel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The radio-drop dump must carry ChannelService sync/retry lines. iOS writes these through
 * `PersistentLogger(category: "ChannelService")`; these tests pin the same strings on Kotlin.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelServicePersistentLoggerTest {
    @Test
    fun `circuit breaker abort records the iOS dump line`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChannelSession()
        for (index in 0 until 6) session.slotErrors[index] = { ChannelSendTimeoutException() }
        val service = testChannelService(session = session, logger = logger(this, recorded))

        service.syncChannels(ChannelTestIds.RADIO, maxChannels = 6, usePipelinedRead = false)

        val line = recorded.single { it.message.contains("Circuit breaker open") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("ChannelService", line.category)
        assertTrue(line.message.contains("3 consecutive timeouts"))
        assertTrue(line.message.contains("aborting sync"))
    }

    @Test
    fun `batch persist failure records Channel batch persist failed`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChannelSession()
        session.slots[0] = channelInfo(0)
        val service = testChannelService(
            session = session,
            store = ThrowingPersistStore(),
            logger = logger(this, recorded),
        )

        service.syncChannels(ChannelTestIds.RADIO, maxChannels = 1, usePipelinedRead = false)

        val line = recorded.single { it.message.contains("Channel batch persist failed") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("batch failed"))
    }

    @Test
    fun `index mismatch records requested and received slots`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChannelSession()
        session.slots[0] = channelInfo(1)
        val service = testChannelService(session = session, logger = logger(this, recorded))

        assertFailsWith<ChannelServiceError.InvalidChannelIndex> { service.fetchChannel(0) }

        val line = recorded.single { it.message.contains("Channel index mismatch") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("requested 0"))
        assertTrue(line.message.contains("received 1"))
    }

    @Test
    fun `a clean sync records post-sync channel diagnostics`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChannelSession()
        session.slots[0] = channelInfo(0)
        val service = testChannelService(session = session, logger = logger(this, recorded))

        service.syncChannels(ChannelTestIds.RADIO, maxChannels = 1, usePipelinedRead = false)

        val line = recorded.single { it.message.contains("Post-sync channel diagnostics") }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertTrue(line.message.contains("total="))
        assertTrue(line.message.contains("emptyNameWithSecret="))
    }

    @Test
    fun `rx-log cache fault records Failed to refresh RxLogService channel cache`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<DebugLogEntryDto>()
            val session = FakeChannelSession()
            session.slots[0] = channelInfo(0)
            val service = testChannelService(
                session = session,
                rxCache = ChannelListSink { error("cache locked") },
                logger = logger(this, recorded),
            )

            service.syncChannels(ChannelTestIds.RADIO, maxChannels = 1, usePipelinedRead = false)

            val line = recorded.single { it.message.contains("Failed to refresh RxLogService channel cache") }
            assertEquals(DebugLogLevel.ERROR, line.level)
            assertTrue(line.message.contains("cache locked"))
        }

    @Test
    fun `a write-path cache fetch fault does not fail the radio write`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChannelSession()
        val store = object : ChannelStore by InMemoryChannelStore() {
            override suspend fun fetchChannels(radioID: Uuid): List<ChannelDto> = error("db locked")
        }
        val service = testChannelService(
            session = session,
            store = store,
            logger = logger(this, recorded),
        )

        service.setChannelWithSecret(
            ChannelTestIds.RADIO,
            index = 1,
            name = "Ops",
            secret = secret16(1),
        )

        assertEquals(1, session.setChannelCalls.size)
        val line = recorded.single { it.message.contains("Failed to refresh RxLogService channel cache") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun `retry records the iOS start succeed and fail dump lines`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChannelSession()
        session.slots[1] = channelInfo(1, name = "recovered")
        session.slotErrors[2] = { ChannelTimeoutException() }
        val service = testChannelService(session = session, logger = logger(this, recorded))

        service.retryFailedChannels(ChannelTestIds.RADIO, indices = listOf(1, 2))

        assertTrue(recorded.any { it.message == "Retrying 2 failed channels: [1, 2]" })
        assertTrue(recorded.any { it.message == "Retry succeeded for channel 1" })
        assertTrue(recorded.any { it.message.startsWith("Retry failed for channel 2:") })
        assertTrue(recorded.none { it.message.contains("Failed to sync channel 2") })
    }

    @Test
    fun reconcileDumpLinesMatchIos() {
        assertEquals(
            "Reconcile circuit breaker open: 3 consecutive timeouts, stopping reconcile",
            reconcileCircuitBreakerOpenLine(3),
        )
        assertEquals(
            "Reconcile failed for channel 2: dropped reconcile read",
            reconcileFailedForChannelLine(2, "dropped reconcile read"),
        )
    }

    @Test
    fun `pipelined reconcile records the iOS breaker and fail dump lines`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChannelSession()
        session.batchResult = ChannelBatchRead(
            received = listOf(channelInfo(0), channelInfo(1)),
            missing = listOf(2, 3, 4, 5),
        )
        for (index in 2..4) session.slotErrors[index] = { ChannelTransportException("dropped reconcile read") }
        val service = testChannelService(session = session, logger = logger(this, recorded))

        service.syncChannels(ChannelTestIds.RADIO, maxChannels = 6, usePipelinedRead = true)

        assertEquals(
            reconcileCircuitBreakerOpenLine(3),
            recorded.single {
                it.message.contains("Reconcile circuit breaker")
            }.message,
        )
        assertEquals(
            DebugLogLevel.ERROR,
            recorded.single {
                it.message.contains("Reconcile circuit breaker")
            }.level,
        )
        val failLines = recorded.filter { it.message.startsWith("Reconcile failed for channel") }
        assertEquals(3, failLines.size)
        assertTrue(failLines.all { it.level == DebugLogLevel.WARNING })
        assertTrue(recorded.none { it.message.contains("Failed to sync channel") })
        assertTrue(recorded.none { it.message.contains("aborting sync") })
    }

    @Test
    fun `per-slot fail records Failed to sync channel`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChannelSession()
        session.slotErrors[0] = { ChannelTimeoutException() }
        session.markUnconfigured(1)
        val service = testChannelService(session = session, logger = logger(this, recorded))

        service.syncChannels(ChannelTestIds.RADIO, maxChannels = 2, usePipelinedRead = false)

        val line = recorded.single { it.message.contains("Failed to sync channel 0") }
        assertEquals(DebugLogLevel.WARNING, line.level)
    }

    private fun logger(scope: kotlinx.coroutines.CoroutineScope, recorded: MutableList<DebugLogEntryDto>) =
        PersistentLogger(
            scope = scope,
            category = "ChannelService",
            record = { recorded += it },
        )
}

/** A store whose batch save always throws, to exercise the persist-fail dump line. */
private class ThrowingPersistStore(private val delegate: InMemoryChannelStore = InMemoryChannelStore()) :
    ChannelStore by delegate {
    override suspend fun batchSaveChannels(
        radioID: Uuid,
        configured: List<ChannelInfo>,
        unconfiguredIndices: List<Int>,
        pruneBeyond: Int?,
    ): List<ChannelDto> = error("batch failed")
}
