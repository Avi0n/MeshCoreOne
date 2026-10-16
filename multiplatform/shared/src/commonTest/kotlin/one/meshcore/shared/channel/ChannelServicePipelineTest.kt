package one.meshcore.shared.channel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `ChannelServicePipelineTests`: correctness with gaps, drop-reconcile, a hard send
 * failure persisting nothing, a mid-drain stall keeping an unread configured slot, the reconcile circuit
 * breaker, and serial-path parity. The Swift tests drive a real session over a `MockTransport`; here the
 * same choreography is expressed through the [FakeChannelSession] batch/serial seam.
 */
class ChannelServicePipelineTest {
    private val radioID = ChannelTestIds.RADIO

    @Test
    fun pipelinedSyncPersistsNonContiguousConfiguredSlots() = runTest {
        val session = FakeChannelSession()
        val received = (0 until 8).map { if (it in setOf(0, 2, 7)) channelInfo(it) else emptySlot(it) }
        session.batchResult = ChannelBatchRead(received = received, missing = emptyList())
        val service = testChannelService(session = session)

        val result = service.syncChannels(radioID, maxChannels = 8, usePipelinedRead = true)

        assertEquals(3, result.channelsSynced)
        assertEquals(listOf(0, 2, 7), service.getChannels(radioID).map { it.index })
        assertEquals("ch2", service.getChannel(radioID, 2)?.name)
    }

    @Test
    fun droppedWriteIsReconciledWithSerialReadAndPersisted() = runTest {
        val session = FakeChannelSession()
        session.batchResult = ChannelBatchRead(
            received = listOf(channelInfo(0), channelInfo(1), channelInfo(3)),
            missing = listOf(2),
        )
        session.slots[2] = channelInfo(2)
        val service = testChannelService(session = session)

        val result = service.syncChannels(radioID, maxChannels = 4, usePipelinedRead = true)

        assertEquals(4, result.channelsSynced)
        assertEquals(listOf(0, 1, 2, 3), service.getChannels(radioID).map { it.index })
        assertEquals("ch2", service.getChannel(radioID, 2)?.name)
    }

    @Test
    fun hardSendFailureMidPipelineThrowsAndPersistsNothing() = runTest {
        val store = InMemoryChannelStore()
        val session = FakeChannelSession()
        session.batchError = { ChannelTransportException("disconnected mid-send") }
        val service = testChannelService(session = session, store = store)

        assertFailsWith<ChannelTransportException> {
            service.syncChannels(radioID, maxChannels = 4, usePipelinedRead = true)
        }
        assertTrue(store.fetchChannels(radioID).isEmpty())
    }

    @Test
    fun midDrainStallPersistsOnlyReadSlotsAndKeepsUnreadConfiguredSlot() = runTest {
        val store = InMemoryChannelStore()
        store.seedChannel(channelDto(ChannelTestIds.fresh(), radioID, index = 2, name = "keep"))

        val session = FakeChannelSession()
        session.batchResult = ChannelBatchRead(
            received = listOf(channelInfo(0), channelInfo(1), channelInfo(3)),
            missing = listOf(2),
        )
        session.slotErrors[2] = { ChannelTransportException("dropped reconcile read") }
        val service = testChannelService(session = session, store = store)

        val result = service.syncChannels(radioID, maxChannels = 4, usePipelinedRead = true)

        assertEquals(3, result.channelsSynced)
        // No mis-indexed rows; the unread index 2 could not be re-read and is left intact.
        assertEquals(listOf(0, 1, 2, 3), service.getChannels(radioID).map { it.index })
        assertEquals("keep", service.getChannel(radioID, 2)?.name)
    }

    @Test
    fun reconcileCircuitBreakerOpensAndNeverDeletesUnreadSlots() = runTest {
        val store = InMemoryChannelStore()
        for (index in 2..7) {
            store.seedChannel(channelDto(ChannelTestIds.fresh(), radioID, index = index, name = "keep$index"))
        }

        val session = FakeChannelSession()
        session.batchResult = ChannelBatchRead(
            received = listOf(channelInfo(0), channelInfo(1)),
            missing = listOf(2, 3, 4, 5, 6, 7),
        )
        for (index in 2..4) session.slotErrors[index] = { ChannelTransportException("dropped reconcile read") }
        val service = testChannelService(session = session, store = store)

        val result = service.syncChannels(radioID, maxChannels = 8, usePipelinedRead = true)

        assertEquals(2, result.channelsSynced)
        assertTrue(result.circuitBreakerAborted)
        val transportIndices = result.errors
            .filter { it.errorType is ChannelSyncErrorType.TransportError }
            .map { it.index }
            .sorted()
        val breakerIndices = result.errors
            .filter { it.errorType is ChannelSyncErrorType.CircuitBreaker }
            .map { it.index }
            .sorted()
        assertEquals(listOf(2, 3, 4), transportIndices)
        assertEquals(listOf(5, 6, 7), breakerIndices)
        // No unread slot is deleted, whether reconcile-failed or breaker-skipped.
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), service.getChannels(radioID).map { it.index })
        for (index in 2..7) assertEquals("keep$index", service.getChannel(radioID, index)?.name)
    }

    @Test
    fun serialPathStillSyncsViaAcknowledgedReads() = runTest {
        val session = FakeChannelSession()
        session.slots[0] = channelInfo(0)
        session.slots[1] = emptySlot(1)
        val service = testChannelService(session = session)

        val result = service.syncChannels(radioID, maxChannels = 2, usePipelinedRead = false)

        assertEquals(1, result.channelsSynced)
        assertEquals(listOf(0), service.getChannels(radioID).map { it.index })
    }
}
