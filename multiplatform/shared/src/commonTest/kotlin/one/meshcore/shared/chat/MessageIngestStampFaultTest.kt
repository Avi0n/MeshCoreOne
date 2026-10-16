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
 * A last-heard stamp fault is logged and the inbound direct message is still saved.
 * Cancellation leaves that ingest and keeps the thrown instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageIngestStampFaultTest {
    private class StampProbeError : Error("probe")

    @Test
    fun aStampFaultStillSavesTheMessage() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val backing = InMemoryIngestStore()
        val pipeline = stampPipeline(backing, recorded) { error("db locked") }

        pipeline.ingest(directMessage(), DeliveryContext.Live)

        assertEquals(1, backing.savedMessages().size)
        val line = recorded.single { it.message.contains("lastHeard stamp failed") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun aStampErrorStillSavesTheMessage() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val backing = InMemoryIngestStore()
        val pipeline = stampPipeline(backing, recorded) { throw StampProbeError() }

        pipeline.ingest(directMessage(), DeliveryContext.Live)

        assertEquals(1, backing.savedMessages().size)
        val line = recorded.single { it.message.contains("lastHeard stamp failed") }
        assertTrue(line.message.contains("probe"))
    }

    @Test
    fun aCancelledStampPropagates() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val backing = InMemoryIngestStore()
        val stopped = CancellationException("stopped")
        val pipeline = stampPipeline(backing, recorded) { throw stopped }

        val thrown = assertFailsWith<CancellationException> {
            pipeline.ingest(directMessage(), DeliveryContext.Live)
        }

        assertSame(stopped, thrown)
        assertEquals(0, backing.savedMessages().size)
        assertTrue(recorded.none { it.message.contains("lastHeard stamp failed") })
    }

    private fun TestScope.stampPipeline(
        backing: InMemoryIngestStore,
        recorded: MutableList<DebugLogEntryDto>,
        onStamp: () -> Nothing,
    ): MessageIngestPipeline {
        val store = object : IngestStore by backing {
            override suspend fun touchContactHeard(contactID: Uuid, atEpochSeconds: Long) = onStamp()
        }
        return ingestFaultPipeline(store, recorded)
    }

    private fun directMessage(): IncomingMessage.Direct = IncomingMessage.Direct(
        contactMessage("kept", INGEST_NOW_SECONDS.toLong()),
        resolvedContact(),
    )
}
