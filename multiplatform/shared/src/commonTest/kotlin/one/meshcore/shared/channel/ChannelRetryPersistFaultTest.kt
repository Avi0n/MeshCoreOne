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
 * A failed retry persist is a database error and does not report a secret change or refresh the RX cache.
 * Cancellation aborts the retry. A thrown error stays on that database result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRetryPersistFaultTest {
    private val radioID = ChannelTestIds.RADIO

    @Test
    fun aCancelledRetryPersistAbortsBeforeSave() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val occupants = RecordingSlotOccupantChanged()
        val cache = RecordingChannelListSink()
        val store = BatchSaveFaultStore(InMemoryChannelStore())
        store.seedChannel(storedChannel())
        store.batchError = stopped
        val service = service(store, logs, occupants, cache)

        val thrown = assertFailsWith<CancellationException> {
            service.retryFailedChannels(radioID, indices = listOf(SLOT))
        }

        assertSame(stopped, thrown)
        assertEquals(STORED_NAME, service.getChannel(radioID, SLOT)?.name)
        assertTrue(occupants.received.isEmpty())
        assertTrue(cache.updates.isEmpty())
        assertTrue(logs.none { it.message.contains(RETRY_PERSIST_FAILED) })
    }

    @Test
    fun aFailedRetryPersistSkipsSecretChanges() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val occupants = RecordingSlotOccupantChanged()
        val cache = RecordingChannelListSink()
        val store = BatchSaveFaultStore(InMemoryChannelStore())
        store.seedChannel(storedChannel())
        store.batchError = RetryPersistFailure()
        val service = service(store, logs, occupants, cache)

        val result = service.retryFailedChannels(radioID, indices = listOf(SLOT))

        assertEquals(0, result.channelsSynced)
        assertEquals(retryPersistDescription(FAILURE_TEXT), result.errors.single().description)
        assertEquals(SLOT, result.errors.single().index)
        assertTrue(result.errors.single().errorType is ChannelSyncErrorType.DatabaseError)
        assertEquals(STORED_NAME, service.getChannel(radioID, SLOT)?.name)
        assertTrue(occupants.received.isEmpty())
        assertTrue(cache.updates.isEmpty())
        val line = logs.single { it.message.contains(RETRY_PERSIST_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(retryPersistFailedLine(FAILURE_TEXT), line.message)
    }

    @Test
    fun aRetryPersistErrorStaysADatabaseResult() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val occupants = RecordingSlotOccupantChanged()
        val cache = RecordingChannelListSink()
        val store = BatchSaveFaultStore(InMemoryChannelStore())
        store.seedChannel(storedChannel())
        store.batchError = RetryPersistProbeError()
        val service = service(store, logs, occupants, cache)

        val result = service.retryFailedChannels(radioID, indices = listOf(SLOT))

        assertEquals(0, result.channelsSynced)
        assertEquals(retryPersistDescription(PROBE_TEXT), result.errors.single().description)
        assertTrue(result.errors.single().errorType is ChannelSyncErrorType.DatabaseError)
        assertEquals(STORED_NAME, service.getChannel(radioID, SLOT)?.name)
        assertTrue(occupants.received.isEmpty())
        assertTrue(cache.updates.isEmpty())
        assertEquals(
            retryPersistFailedLine(PROBE_TEXT),
            logs.single { it.message.contains(RETRY_PERSIST_FAILED) }.message,
        )
    }

    private fun storedChannel(): ChannelDto = channelDto(
        ChannelTestIds.fresh(),
        radioID,
        index = SLOT,
        name = STORED_NAME,
        secretByte = STORED_SECRET,
    )

    private fun TestScope.service(
        store: BatchSaveFaultStore,
        logs: MutableList<DebugLogEntryDto>,
        occupants: RecordingSlotOccupantChanged,
        cache: RecordingChannelListSink,
    ): ChannelService {
        val session = FakeChannelSession()
        session.slots[SLOT] = channelInfo(SLOT, name = RECOVERED_NAME, secretByte = RECOVERED_SECRET)
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

private const val SLOT = 1
private const val STORED_NAME = "stored"
private const val RECOVERED_NAME = "recovered"
private const val STORED_SECRET = 0x11
private const val RECOVERED_SECRET = 0x22
private const val RETRY_PERSIST_FAILED = "Retry batch persist failed"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun retryPersistFailedLine(detail: String): String = "$RETRY_PERSIST_FAILED: $detail"

private fun retryPersistDescription(detail: String): String = "Retry persist failed: $detail"

private class RetryPersistFailure : Exception("disk")

private class RetryPersistProbeError : Error("probe")
