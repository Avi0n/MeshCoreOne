package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed channel batch persist is a database error and stops the sync before occupant changes or
 * the RX cache update. Cancellation aborts the sync. A thrown error stays on that database result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelBatchPersistFaultTest {
    private val radioID = ChannelTestIds.RADIO

    @Test
    fun aCancelledBatchPersistAbortsTheSync() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val occupants = RecordingSlotOccupantChanged()
        val cache = RecordingChannelListSink()
        val store = BatchSaveFaultStore(InMemoryChannelStore())
        store.seedChannel(storedChannel())
        store.batchError = stopped
        val service = service(store, logs, occupants, cache)

        val thrown = assertFailsWith<CancellationException> {
            service.syncChannels(radioID, maxChannels = 2, usePipelinedRead = false)
        }

        assertSame(stopped, thrown)
        assertEquals(listOf(KEPT_INDEX), service.getChannels(radioID).map { it.index })
        assertTrue(occupants.received.isEmpty())
        assertTrue(cache.updates.isEmpty())
        assertTrue(logs.none { it.message.contains(BATCH_PERSIST_FAILED) })
    }

    @Test
    fun aFailedBatchPersistStopsBeforeOccupantChanges() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val occupants = RecordingSlotOccupantChanged()
        val cache = RecordingChannelListSink()
        val store = BatchSaveFaultStore(InMemoryChannelStore())
        store.seedChannel(storedChannel())
        store.batchError = BatchPersistFailure()
        val service = service(store, logs, occupants, cache)

        val result = service.syncChannels(radioID, maxChannels = 2, usePipelinedRead = false)

        assertEquals(0, result.channelsSynced)
        assertEquals(batchPersistDescription(FAILURE_TEXT), result.errors.single().description)
        assertEquals(PUBLIC_INDEX, result.errors.single().index)
        assertTrue(result.errors.single().errorType is ChannelSyncErrorType.DatabaseError)
        assertEquals(listOf(KEPT_INDEX), service.getChannels(radioID).map { it.index })
        assertTrue(occupants.received.isEmpty())
        assertTrue(cache.updates.isEmpty())
        val line = logs.single { it.message.contains(BATCH_PERSIST_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(batchPersistFailedLine(FAILURE_TEXT), line.message)
    }

    @Test
    fun aBatchPersistErrorStaysADatabaseResult() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val occupants = RecordingSlotOccupantChanged()
        val cache = RecordingChannelListSink()
        val store = BatchSaveFaultStore(InMemoryChannelStore())
        store.seedChannel(storedChannel())
        store.batchError = BatchPersistProbeError()
        val service = service(store, logs, occupants, cache)

        val result = service.syncChannels(radioID, maxChannels = 2, usePipelinedRead = false)

        assertEquals(0, result.channelsSynced)
        assertEquals(batchPersistDescription(PROBE_TEXT), result.errors.single().description)
        assertTrue(result.errors.single().errorType is ChannelSyncErrorType.DatabaseError)
        assertEquals(listOf(KEPT_INDEX), service.getChannels(radioID).map { it.index })
        assertTrue(occupants.received.isEmpty())
        assertTrue(cache.updates.isEmpty())
        assertEquals(
            batchPersistFailedLine(PROBE_TEXT),
            logs.single { it.message.contains(BATCH_PERSIST_FAILED) }.message,
        )
    }

    private fun storedChannel(): ChannelDto =
        channelDto(ChannelTestIds.fresh(), radioID, index = KEPT_INDEX, name = STORED_NAME)

    private fun TestScope.service(
        store: BatchSaveFaultStore,
        logs: MutableList<DebugLogEntryDto>,
        occupants: RecordingSlotOccupantChanged,
        cache: RecordingChannelListSink,
    ): ChannelService {
        val session = FakeChannelSession()
        session.slots[PUBLIC_INDEX] = channelInfo(PUBLIC_INDEX, name = "Public")
        session.slots[KEPT_INDEX] = emptySlot(KEPT_INDEX)
        return testChannelService(
            session = session,
            store = store,
            rxCache = cache,
            slotOccupantChanged = occupants,
            logger = PersistentLogger(
                scope = this,
                category = "ChannelSync",
                record = { logs += it },
            ),
        )
    }
}

private const val PUBLIC_INDEX = 0
private const val KEPT_INDEX = 1
private const val STORED_NAME = "kept"
private const val BATCH_PERSIST_FAILED = "Channel batch persist failed"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun batchPersistFailedLine(detail: String): String = "$BATCH_PERSIST_FAILED: $detail"

private fun batchPersistDescription(detail: String): String = "Batch persist failed: $detail"

private class BatchPersistFailure : Exception("disk")

private class BatchPersistProbeError : Error("probe")
