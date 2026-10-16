package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An RX-log lookup fault keeps the firmware path length and still saves the message, including when
 * the lookup throws an error. Cancellation leaves that ingest and keeps the thrown instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageIngestRxLogFaultTest {
    private class RxLogProbeError : Error("probe")

    @Test
    fun aLookupFaultKeepsTheReportedPath() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val backing = InMemoryIngestStore()
        val pipeline = rxLogPipeline(backing, recorded) { error("db locked") }

        pipeline.ingest(directMessage(pathLength = 4), DeliveryContext.Live)

        val saved = backing.savedMessages().single()
        assertEquals(4, saved.pathLength)
        assertNull(saved.routeType)
        val line = recorded.single { it.message.contains("Failed to lookup RxLogEntry for direct message") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun aLookupErrorKeepsTheReportedPath() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val backing = InMemoryIngestStore()
        val pipeline = rxLogPipeline(backing, recorded) { throw RxLogProbeError() }

        pipeline.ingest(directMessage(pathLength = 4), DeliveryContext.Live)

        assertEquals(4, backing.savedMessages().single().pathLength)
        val line = recorded.single { it.message.contains("Failed to lookup RxLogEntry for direct message") }
        assertTrue(line.message.contains("probe"))
    }

    @Test
    fun aCancelledLookupPropagates() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val backing = InMemoryIngestStore()
        val stopped = CancellationException("stopped")
        val pipeline = rxLogPipeline(backing, recorded) { throw stopped }

        val thrown = assertFailsWith<CancellationException> {
            pipeline.ingest(directMessage(pathLength = 4), DeliveryContext.Live)
        }

        assertSame(stopped, thrown)
        assertEquals(0, backing.savedMessages().size)
        assertTrue(recorded.none { it.message.contains("Failed to lookup RxLogEntry") })
    }

    private fun TestScope.rxLogPipeline(
        backing: InMemoryIngestStore,
        recorded: MutableList<DebugLogEntryDto>,
        onLookup: () -> Nothing,
    ): MessageIngestPipeline {
        val store = object : IngestStore by backing {
            override suspend fun lookupRxLog(
                radioID: Uuid,
                channelIndex: Int?,
                senderTimestamp: Long,
                senderKeyPrefix: ByteArray?,
                defaultPathLength: Int,
            ): RxLogCorrelation = onLookup()
        }
        return ingestFaultPipeline(store, recorded)
    }

    private fun directMessage(pathLength: Int): IncomingMessage.Direct = IncomingMessage.Direct(
        contactMessage("kept", INGEST_NOW_SECONDS.toLong(), pathLength = pathLength),
        resolvedContact(),
    )
}
