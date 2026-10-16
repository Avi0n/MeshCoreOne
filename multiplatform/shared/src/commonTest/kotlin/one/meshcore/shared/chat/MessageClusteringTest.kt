package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mirrors the iOS `SameSenderReorderingTests`: cluster window, tie-breaks, and channel/sender rules. */
class MessageClusteringTest {
    private val base = ChatFixtures.BASE_EPOCH_SECONDS

    @Test
    fun emptyArrayReturnsEmpty() {
        assertTrue(reorderSameSenderClusters(emptyList()).isEmpty())
    }

    @Test
    fun singleMessageReturnsUnchanged() {
        val msg = ChatFixtures.dmMessage(timestamp = 100, createdAt = base)
        val result = reorderSameSenderClusters(listOf(msg))
        assertEquals(listOf(msg.id), result.map { it.id })
    }

    @Test
    fun dmMessagesWithinFiveSecondsReorderBySenderTimestamp() {
        val msg1 = ChatFixtures.dmMessage(timestamp = 100, createdAt = base + 2)
        val msg2 = ChatFixtures.dmMessage(timestamp = 200, createdAt = base)
        val result = reorderSameSenderClusters(listOf(msg2, msg1))
        assertEquals(listOf(100L, 200L), result.map { it.timestamp })
    }

    @Test
    fun outgoingDmMessagesWithinFiveSecondsReorder() {
        val msg1 = ChatFixtures.dmMessage(timestamp = 100, createdAt = base + 2, direction = MessageDirection.OUTGOING)
        val msg2 = ChatFixtures.dmMessage(timestamp = 200, createdAt = base, direction = MessageDirection.OUTGOING)
        val result = reorderSameSenderClusters(listOf(msg2, msg1))
        assertEquals(listOf(100L, 200L), result.map { it.timestamp })
    }

    @Test
    fun dmMessagesBeyondFiveSecondsAreNotReordered() {
        val msg1 = ChatFixtures.dmMessage(timestamp = 100, createdAt = base)
        val msg2 = ChatFixtures.dmMessage(timestamp = 200, createdAt = base + 6)
        val result = reorderSameSenderClusters(listOf(msg1, msg2))
        assertEquals(listOf(100L, 200L), result.map { it.timestamp })
    }

    @Test
    fun channelMessagesFromDifferentSendersAreNotClustered() {
        val alice = ChatFixtures.channelMessage(timestamp = 200, createdAt = base, senderName = "Alice")
        val bob = ChatFixtures.channelMessage(timestamp = 100, createdAt = base + 2, senderName = "Bob")
        val result = reorderSameSenderClusters(listOf(alice, bob))
        assertEquals(listOf("Alice", "Bob"), result.map { it.senderNodeName })
    }

    @Test
    fun channelMessagesFromSameSenderWithinWindowReorder() {
        val msg1 = ChatFixtures.channelMessage(timestamp = 100, createdAt = base + 3, senderName = "Alice")
        val msg2 = ChatFixtures.channelMessage(timestamp = 200, createdAt = base, senderName = "Alice")
        val result = reorderSameSenderClusters(listOf(msg2, msg1))
        assertEquals(listOf(100L, 200L), result.map { it.timestamp })
    }

    @Test
    fun mixedDirectionsBreakClusters() {
        val incoming = ChatFixtures.dmMessage(200, createdAt = base, direction = MessageDirection.INCOMING)
        val outgoing = ChatFixtures.dmMessage(100, createdAt = base + 1, direction = MessageDirection.OUTGOING)
        val result = reorderSameSenderClusters(listOf(incoming, outgoing))
        assertEquals(listOf(MessageDirection.INCOMING, MessageDirection.OUTGOING), result.map { it.direction })
    }

    @Test
    fun threeMessagesInClusterAreFullySorted() {
        val msg1 = ChatFixtures.dmMessage(timestamp = 300, createdAt = base)
        val msg2 = ChatFixtures.dmMessage(timestamp = 100, createdAt = base + 2)
        val msg3 = ChatFixtures.dmMessage(timestamp = 200, createdAt = base + 4)
        val result = reorderSameSenderClusters(listOf(msg1, msg2, msg3))
        assertEquals(listOf(100L, 200L, 300L), result.map { it.timestamp })
    }

    @Test
    fun exactlyFiveSecondGapIsIncludedInCluster() {
        val msg1 = ChatFixtures.dmMessage(timestamp = 200, createdAt = base)
        val msg2 = ChatFixtures.dmMessage(timestamp = 100, createdAt = base + 5)
        val result = reorderSameSenderClusters(listOf(msg1, msg2))
        assertEquals(listOf(100L, 200L), result.map { it.timestamp })
    }

    @Test
    fun multipleConsecutiveClustersAreEachReorderedIndependently() {
        val c1a = ChatFixtures.dmMessage(timestamp = 200, createdAt = base)
        val c1b = ChatFixtures.dmMessage(timestamp = 100, createdAt = base + 3)
        val c2a = ChatFixtures.dmMessage(timestamp = 400, createdAt = base + 13)
        val c2b = ChatFixtures.dmMessage(timestamp = 300, createdAt = base + 15)
        val result = reorderSameSenderClusters(listOf(c1a, c1b, c2a, c2b))
        assertEquals(listOf(100L, 200L, 300L, 400L), result.map { it.timestamp })
    }

    @Test
    fun channelMessagesWithNullSenderNamesAreNotClustered() {
        val msg1 = ChatFixtures.channelMessage(timestamp = 200, createdAt = base)
        val msg2 = ChatFixtures.channelMessage(timestamp = 100, createdAt = base + 2)
        val result = reorderSameSenderClusters(listOf(msg1, msg2))
        assertEquals(listOf(200L, 100L), result.map { it.timestamp })
    }

    @Test
    fun identicalTimestampsUseCreatedAtAsTiebreaker() {
        val msg1 = ChatFixtures.dmMessage(timestamp = 100, createdAt = base)
        val msg2 = ChatFixtures.dmMessage(timestamp = 100, createdAt = base + 0.5)
        val result = reorderSameSenderClusters(listOf(msg2, msg1))
        assertEquals(listOf(msg1.id, msg2.id), result.map { it.id })
    }

    @Test
    fun messagesAlreadyInOrderAreUnchanged() {
        val msg1 = ChatFixtures.dmMessage(timestamp = 100, createdAt = base)
        val msg2 = ChatFixtures.dmMessage(timestamp = 200, createdAt = base + 1)
        val msg3 = ChatFixtures.dmMessage(timestamp = 300, createdAt = base + 2)
        val result = reorderSameSenderClusters(listOf(msg1, msg2, msg3))
        assertEquals(listOf(msg1.id, msg2.id, msg3.id), result.map { it.id })
    }

    @Test
    fun farApartSortDatesAreNotClusteredEvenWhenCreatedAtInverted() {
        val early = ChatFixtures.dmMessage(timestamp = 200, createdAt = base + 3600, sortDate = base)
        val late = ChatFixtures.dmMessage(timestamp = 100, createdAt = base, sortDate = base + 60)
        val result = reorderSameSenderClusters(listOf(early, late))
        assertEquals(listOf(early.id, late.id), result.map { it.id })
    }

    @Test
    fun clusteringWindowFollowsSortDateNotCreatedAt() {
        val first = ChatFixtures.dmMessage(timestamp = 200, createdAt = base, sortDate = base)
        val second = ChatFixtures.dmMessage(timestamp = 100, createdAt = base + 100, sortDate = base + 2)
        val result = reorderSameSenderClusters(listOf(first, second))
        assertEquals(listOf(100L, 200L), result.map { it.timestamp })
    }
}
