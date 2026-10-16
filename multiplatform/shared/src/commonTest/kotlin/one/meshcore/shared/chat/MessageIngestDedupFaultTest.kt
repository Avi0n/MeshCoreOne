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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A dedup-check fault is logged and the inbound message is still saved, including when the check
 * throws an error. Cancellation leaves that ingest and keeps the thrown instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageIngestDedupFaultTest {
    private class DedupProbeError : Error("probe")

    @Test
    fun aDedupFaultStillSavesTheMessage() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val backing = InMemoryIngestStore()
        val pipeline = dedupPipeline(backing, recorded) { error("db locked") }

        pipeline.ingest(directMessage(), DeliveryContext.Live)

        assertEquals(1, backing.savedMessages().size)
        val line = recorded.single { it.message.contains("Dedup check failed, proceeding with save") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun aDedupErrorStillSavesTheMessage() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val backing = InMemoryIngestStore()
        val pipeline = dedupPipeline(backing, recorded) { throw DedupProbeError() }

        pipeline.ingest(directMessage(), DeliveryContext.Live)

        assertEquals(1, backing.savedMessages().size)
        val line = recorded.single { it.message.contains("Dedup check failed, proceeding with save") }
        assertTrue(line.message.contains("probe"))
    }

    @Test
    fun aCancelledDedupCheckPropagates() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val backing = InMemoryIngestStore()
        val stopped = CancellationException("stopped")
        val pipeline = dedupPipeline(backing, recorded) { throw stopped }

        val thrown = assertFailsWith<CancellationException> {
            pipeline.ingest(directMessage(), DeliveryContext.Live)
        }

        assertSame(stopped, thrown)
        assertEquals(0, backing.savedMessages().size)
        assertTrue(recorded.none { it.message.contains("Dedup check failed") })
    }

    private fun TestScope.dedupPipeline(
        backing: InMemoryIngestStore,
        recorded: MutableList<DebugLogEntryDto>,
        onCheck: () -> Nothing,
    ): MessageIngestPipeline {
        val store = object : IngestStore by backing {
            override suspend fun isDuplicateMessage(deduplicationKey: String, radioID: Uuid): Boolean = onCheck()
        }
        return ingestFaultPipeline(store, recorded)
    }

    private fun directMessage(): IncomingMessage.Direct = IncomingMessage.Direct(
        contactMessage("kept", INGEST_NOW_SECONDS.toLong()),
        resolvedContact(),
    )
}
