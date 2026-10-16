package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed pre-sync channel snapshot is an empty baseline, so a prune does not report occupant changes.
 * Cancellation aborts the sync. A thrown error stays on that empty baseline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelSnapshotFaultTest {
    private val radioID = ChannelTestIds.RADIO

    @Test
    fun aCancelledChannelSnapshotAbortsSync() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val store = FetchChannelsFaultStore(InMemoryChannelStore())
        store.seedChannel(channelDto(ChannelTestIds.fresh(), radioID, index = 1))
        store.fetchError = stopped
        val service = service(store, logs)

        val thrown = assertFailsWith<CancellationException> {
            service.syncChannels(radioID, maxChannels = 2, usePipelinedRead = false)
        }

        store.fetchError = null
        assertSame(stopped, thrown)
        assertEquals(listOf(1), service.getChannels(radioID).map { it.index })
        assertTrue(logs.none { it.message.contains(SNAPSHOT_FAILED) })
    }

    @Test
    fun aFailedChannelSnapshotSkipsOccupantChanges() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val occupants = RecordingSlotOccupantChanged()
        val store = FetchChannelsFaultStore(InMemoryChannelStore())
        store.seedChannel(channelDto(ChannelTestIds.fresh(), radioID, index = 1))
        store.fetchError = ChannelSnapshotFetchFailure()
        val service = service(store, logs, occupants)

        val result = service.syncChannels(radioID, maxChannels = 2, usePipelinedRead = false)

        store.fetchError = null
        assertEquals(1, result.channelsSynced)
        assertEquals(listOf(0), service.getChannels(radioID).map { it.index })
        assertTrue(occupants.received.isEmpty())
        val line = logs.single { it.message.contains(SNAPSHOT_FAILED) }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals(snapshotFailedLine(radioID), line.message)
    }

    @Test
    fun aChannelSnapshotErrorStaysAnEmptyBaseline() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val occupants = RecordingSlotOccupantChanged()
        val store = FetchChannelsFaultStore(InMemoryChannelStore())
        store.seedChannel(channelDto(ChannelTestIds.fresh(), radioID, index = 1))
        store.fetchError = ChannelSnapshotProbeError()
        val service = service(store, logs, occupants)

        val result = service.syncChannels(radioID, maxChannels = 2, usePipelinedRead = false)

        store.fetchError = null
        assertEquals(1, result.channelsSynced)
        assertEquals(listOf(0), service.getChannels(radioID).map { it.index })
        assertTrue(occupants.received.isEmpty())
        assertEquals(snapshotFailedLine(radioID), logs.single { it.message.contains(SNAPSHOT_FAILED) }.message)
    }

    private fun TestScope.service(
        store: FetchChannelsFaultStore,
        logs: MutableList<DebugLogEntryDto>,
        occupants: RecordingSlotOccupantChanged = RecordingSlotOccupantChanged(),
    ): ChannelService {
        val session = FakeChannelSession()
        session.slots[0] = channelInfo(0, name = "Public")
        session.slots[1] = emptySlot(1)
        return testChannelService(
            session = session,
            store = store,
            slotOccupantChanged = occupants,
            logger = PersistentLogger(
                scope = this,
                category = "ChannelSync",
                record = { logs += it },
            ),
        )
    }
}

private const val SNAPSHOT_FAILED = "Pre-sync channel snapshot failed"

private fun snapshotFailedLine(radioID: Uuid): String =
    "Pre-sync channel snapshot failed for radio ${radioID.value}; " +
        "skipping occupant-changed for any slots this prune vacates"

private class ChannelSnapshotFetchFailure : Exception("disk")

private class ChannelSnapshotProbeError : Error("probe")
