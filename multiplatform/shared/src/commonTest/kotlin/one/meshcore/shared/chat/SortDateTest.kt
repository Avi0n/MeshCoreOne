package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mirrors the iOS `SyncCoordinatorSortDateTests`: live sorts by receive time, backlog by the drain anchor. */
class SortDateTest {
    private val now = 1_700_000_000.0
    private val oneMinute = 60.0

    @Test
    fun liveMessageSortsByReceiveTime() {
        assertEquals(now, sortDate(DeliveryContext.Live, receiveTimeEpochSeconds = now))
    }

    @Test
    fun backlogMessageSortsByDrainAnchorNotReceiveTime() {
        val anchor = now - oneMinute
        val result = sortDate(DeliveryContext.InitialSync(anchor), receiveTimeEpochSeconds = now)
        assertEquals(anchor, result)
    }

    @Test
    fun everyMessageInOneDrainSharesTheAnchor() {
        val anchor = now
        val first = sortDate(DeliveryContext.InitialSync(anchor), receiveTimeEpochSeconds = now)
        val second = sortDate(DeliveryContext.InitialSync(anchor), receiveTimeEpochSeconds = now + oneMinute)
        val third = sortDate(DeliveryContext.InitialSync(anchor), receiveTimeEpochSeconds = now + 2 * oneMinute)
        assertEquals(anchor, first)
        assertEquals(anchor, second)
        assertEquals(anchor, third)
    }

    @Test
    fun distinctDrainsSortAsDistinctBlocksInDeliveryOrder() {
        val earlierDrain = now - oneMinute
        val laterDrain = now
        val earlier = sortDate(DeliveryContext.InitialSync(earlierDrain), receiveTimeEpochSeconds = now)
        val later = sortDate(DeliveryContext.InitialSync(laterDrain), receiveTimeEpochSeconds = now)
        assertEquals(earlierDrain, earlier)
        assertEquals(laterDrain, later)
        assertTrue(earlier < later)
    }
}
