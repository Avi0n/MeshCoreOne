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
 * A failed retry snapshot is a silent empty baseline, so a secret change is not reported.
 * Cancellation aborts the retry before the recovered slot is saved. A thrown error stays silent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRetrySnapshotFaultTest {
    private val radioID = ChannelTestIds.RADIO

    @Test
    fun aCancelledRetrySnapshotAbortsBeforePersist() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val occupants = RecordingSlotOccupantChanged()
        val store = FetchChannelsFaultStore(InMemoryChannelStore())
        store.seedChannel(storedChannel())
        store.fetchError = stopped
        val service = service(store, logs, occupants)

        val thrown = assertFailsWith<CancellationException> {
            service.retryFailedChannels(radioID, indices = listOf(1))
        }

        store.fetchError = null
        assertSame(stopped, thrown)
        assertEquals(STORED_NAME, service.getChannel(radioID, 1)?.name)
        assertTrue(occupants.received.isEmpty())
        assertTrue(logs.none { it.level == DebugLogLevel.ERROR || it.level == DebugLogLevel.WARNING })
    }

    @Test
    fun aFailedRetrySnapshotSkipsSecretChanges() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val occupants = RecordingSlotOccupantChanged()
        val store = FetchChannelsFaultStore(InMemoryChannelStore())
        store.seedChannel(storedChannel())
        store.fetchError = RetrySnapshotFetchFailure()
        val service = service(store, logs, occupants)

        val result = service.retryFailedChannels(radioID, indices = listOf(1))

        store.fetchError = null
        assertEquals(1, result.channelsSynced)
        assertEquals(RECOVERED_NAME, service.getChannel(radioID, 1)?.name)
        assertTrue(occupants.received.isEmpty())
        assertTrue(logs.none { it.message.contains(FAILURE_TEXT) })
        assertTrue(logs.none { it.level == DebugLogLevel.ERROR || it.level == DebugLogLevel.WARNING })
    }

    @Test
    fun aRetrySnapshotErrorStaysASilentEmptyBaseline() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val occupants = RecordingSlotOccupantChanged()
        val store = FetchChannelsFaultStore(InMemoryChannelStore())
        store.seedChannel(storedChannel())
        store.fetchError = RetrySnapshotProbeError()
        val service = service(store, logs, occupants)

        val result = service.retryFailedChannels(radioID, indices = listOf(1))

        store.fetchError = null
        assertEquals(1, result.channelsSynced)
        assertEquals(RECOVERED_NAME, service.getChannel(radioID, 1)?.name)
        assertTrue(occupants.received.isEmpty())
        assertTrue(logs.none { it.message.contains(PROBE_TEXT) })
        assertTrue(logs.none { it.level == DebugLogLevel.ERROR || it.level == DebugLogLevel.WARNING })
    }

    private fun storedChannel(): ChannelDto =
        channelDto(ChannelTestIds.fresh(), radioID, index = 1, name = STORED_NAME, secretByte = STORED_SECRET)

    private fun TestScope.service(
        store: FetchChannelsFaultStore,
        logs: MutableList<DebugLogEntryDto>,
        occupants: RecordingSlotOccupantChanged,
    ): ChannelService {
        val session = FakeChannelSession()
        session.slots[1] = channelInfo(1, name = RECOVERED_NAME, secretByte = RECOVERED_SECRET)
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

private const val STORED_NAME = "stored"
private const val RECOVERED_NAME = "recovered"
private const val STORED_SECRET = 0x11
private const val RECOVERED_SECRET = 0x22
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private class RetrySnapshotFetchFailure : Exception("disk")

private class RetrySnapshotProbeError : Error("probe")
