package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageDirection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `computeDisplayFlags` cases in `ChatViewModelTests` plus the day-divider, direction-gap,
 * and channel sender-name transitions. Keys on send time (`timestamp`), not the `sortDate` sort key.
 */
class MessageGroupingTest {
    private val baseTimestamp = 1_700_000_000L
    private val twoDaysSeconds = 2 * 24 * 60 * 60L

    @Test
    fun firstMessageShowsAllInitialFlags() {
        val flags = computeDisplayFlags(ChatFixtures.message(timestamp = baseTimestamp), previous = null)
        assertTrue(flags.showTimestamp)
        assertFalse(flags.showDirectionGap)
        assertTrue(flags.showSenderName)
        assertTrue(flags.showDayDivider)
    }

    @Test
    fun consecutiveMessagesWithinFiveMinutesHideTimestamp() {
        val m0 = ChatFixtures.message(timestamp = baseTimestamp)
        val m1 = ChatFixtures.message(timestamp = baseTimestamp + 60)
        val m2 = ChatFixtures.message(timestamp = baseTimestamp + 120)
        assertTrue(computeDisplayFlags(m0, previous = null).showTimestamp)
        assertFalse(computeDisplayFlags(m1, previous = m0).showTimestamp)
        assertFalse(computeDisplayFlags(m2, previous = m1).showTimestamp)
    }

    @Test
    fun messageAfterFivePlusMinuteGapShowsTimestamp() {
        val m0 = ChatFixtures.message(timestamp = baseTimestamp)
        val m1 = ChatFixtures.message(timestamp = baseTimestamp + 301)
        assertTrue(computeDisplayFlags(m1, previous = m0).showTimestamp)
    }

    @Test
    fun exactlyFiveMinuteGapHidesTimestamp() {
        val m0 = ChatFixtures.message(timestamp = baseTimestamp)
        val m1 = ChatFixtures.message(timestamp = baseTimestamp + 300)
        assertFalse(computeDisplayFlags(m1, previous = m0).showTimestamp)
    }

    @Test
    fun groupingKeysOnSendTimeNotSharedSortDate() {
        // Two backlog rows share the drain anchor as sortDate but were sent ten minutes apart.
        val anchor = 5_000_000.0
        val earlier = ChatFixtures.message(timestamp = baseTimestamp, sortDate = anchor)
        val later = ChatFixtures.message(timestamp = baseTimestamp + 600, sortDate = anchor)
        assertTrue(computeDisplayFlags(later, previous = earlier).showTimestamp)
    }

    @Test
    fun identicalSendTimeHidesTimestamp() {
        val m0 = ChatFixtures.message(timestamp = baseTimestamp)
        val m1 = ChatFixtures.message(timestamp = baseTimestamp)
        assertFalse(computeDisplayFlags(m1, previous = m0).showTimestamp)
    }

    @Test
    fun directionChangeShowsDirectionGap() {
        val outgoing = ChatFixtures.message(timestamp = baseTimestamp, direction = MessageDirection.OUTGOING)
        val incoming = ChatFixtures.message(timestamp = baseTimestamp + 10, direction = MessageDirection.INCOMING)
        assertTrue(computeDisplayFlags(incoming, previous = outgoing).showDirectionGap)
    }

    @Test
    fun sameDirectionHasNoDirectionGap() {
        val a = ChatFixtures.message(timestamp = baseTimestamp, direction = MessageDirection.OUTGOING)
        val b = ChatFixtures.message(timestamp = baseTimestamp + 10, direction = MessageDirection.OUTGOING)
        assertFalse(computeDisplayFlags(b, previous = a).showDirectionGap)
    }

    @Test
    fun differentCalendarDayShowsDayDivider() {
        val day0 = ChatFixtures.message(timestamp = baseTimestamp)
        val day2 = ChatFixtures.message(timestamp = baseTimestamp + twoDaysSeconds)
        assertTrue(computeDisplayFlags(day2, previous = day0).showDayDivider)
    }

    @Test
    fun sameInstantIsSameCalendarDay() {
        val a = ChatFixtures.message(timestamp = baseTimestamp)
        val b = ChatFixtures.message(timestamp = baseTimestamp)
        assertFalse(computeDisplayFlags(b, previous = a).showDayDivider)
    }

    @Test
    fun channelSenderNameHiddenForSameSenderWithinWindow() {
        val prev = ChatFixtures.channelMessage(timestamp = baseTimestamp, senderName = "Alice")
        val cur = ChatFixtures.channelMessage(timestamp = baseTimestamp + 10, senderName = "Alice")
        assertFalse(computeDisplayFlags(cur, previous = prev).showSenderName)
    }

    @Test
    fun channelSenderNameShownForDifferentSender() {
        val prev = ChatFixtures.channelMessage(timestamp = baseTimestamp, senderName = "Alice")
        val cur = ChatFixtures.channelMessage(timestamp = baseTimestamp + 10, senderName = "Bob")
        assertTrue(computeDisplayFlags(cur, previous = prev).showSenderName)
    }

    @Test
    fun channelSenderNameShownAfterOutgoingMessage() {
        val prev = ChatFixtures.channelMessage(timestamp = baseTimestamp, direction = MessageDirection.OUTGOING)
        val cur = ChatFixtures.channelMessage(timestamp = baseTimestamp + 10, senderName = "Alice")
        assertTrue(computeDisplayFlags(cur, previous = prev).showSenderName)
    }

    @Test
    fun directMessageAlwaysShowsSenderName() {
        val prev = ChatFixtures.message(timestamp = baseTimestamp, direction = MessageDirection.INCOMING)
        val cur = ChatFixtures.message(timestamp = baseTimestamp + 10, direction = MessageDirection.INCOMING)
        assertTrue(computeDisplayFlags(cur, previous = prev).showSenderName)
    }

    @Test
    fun outgoingChannelAfterIncomingHidesSenderName() {
        val prev = ChatFixtures.channelMessage(timestamp = baseTimestamp, senderName = "Alice")
        val cur = ChatFixtures.channelMessage(
            timestamp = baseTimestamp + 60,
            senderName = null,
            direction = MessageDirection.OUTGOING,
        )
        val flags = computeDisplayFlags(cur, previous = prev)
        assertFalse(flags.showSenderName)
        assertTrue(flags.showDirectionGap)
    }

    @Test
    fun consecutiveOutgoingChannelMessagesHideSenderName() {
        val first = ChatFixtures.channelMessage(
            timestamp = baseTimestamp,
            senderName = null,
            direction = MessageDirection.OUTGOING,
        )
        val second = ChatFixtures.channelMessage(
            timestamp = baseTimestamp + 60,
            senderName = null,
            direction = MessageDirection.OUTGOING,
        )
        val third = ChatFixtures.channelMessage(
            timestamp = baseTimestamp + 120,
            senderName = null,
            direction = MessageDirection.OUTGOING,
        )
        assertTrue(computeDisplayFlags(first, previous = null).showSenderName)
        assertFalse(computeDisplayFlags(second, previous = first).showSenderName)
        assertFalse(computeDisplayFlags(third, previous = second).showSenderName)
    }
}
