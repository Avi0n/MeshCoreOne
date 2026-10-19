// PortedFrom: MC1Tests/Views/Chats/ChatOpenAtDividerCompositionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/Components/TiledViewInitialScrollTargetTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/Components/MessageBubblePredicateTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.timeline

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.RadioId
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class TimelineStateTest {
    @Test
    @OriginalCase("ChatTimelineTests::open loads the newest page and marks further history available()")
    fun `rows remain chronological while reverse layout input is reversed`() {
        val messages = listOf(message(1), message(2), message(3))
        val rows = buildTimelineRows(messages, null, ZoneOffset.UTC)

        val chronological = rows.filterIsInstance<TimelineRow.Message>().map { it.message.timestamp.toInt() }
        val reverseLayoutInput = rows.asReversed().filterIsInstance<TimelineRow.Message>().map { it.message.timestamp.toInt() }

        assertEquals(listOf(1, 2, 3), chronological)
        assertEquals(listOf(3, 2, 1), reverseLayoutInput)
    }

    @Test
    @OriginalCase("ChatOpenAtDividerCompositionTests::divider presents once the fresh page and bake are applied()")
    fun `unread divider is directly before its stable message`() {
        val unread = message(2)
        val rows = buildTimelineRows(listOf(message(1), unread, message(3)), unread.id, ZoneOffset.UTC)
        val dividerIndex = rows.indexOf(TimelineRow.UnreadDivider(unread.id))

        assertTrue(dividerIndex >= 0)
        assertEquals(unread.id, (rows[dividerIndex + 1] as TimelineRow.Message).message.id)
    }

    @Test
    @OriginalCase("ChatViewModelTests::Consecutive messages within 5 minutes don't show timestamp()")
    @OriginalCase("DisplayFlagsTests::Exactly 5 minute gap still groups()")
    fun `same sender within five minutes groups and later message starts a new group`() {
        val first = message(1, sender = "Node")
        val second = message(301, sender = "Node")
        val later = message(602, sender = "Node")
        val rows = buildTimelineRows(listOf(first, second, later), null, ZoneOffset.UTC)
            .filterIsInstance<TimelineRow.Message>()

        assertTrue(rows[0].startsGroup)
        assertFalse(rows[0].endsGroup)
        assertFalse(rows[1].startsGroup)
        assertTrue(rows[1].endsGroup)
        assertTrue(rows[2].startsGroup)
        assertTrue(rows[2].endsGroup)
    }

    @Test
    @OriginalCase("DisplayFlagsTests::Different sender shows sender name()")
    @OriginalCase("DisplayFlagsTests::Direction change shows direction gap()")
    fun `direction and channel sender changes split groups`() {
        val incomingA = message(1, sender = "A")
        val incomingB = message(2, sender = "B")
        val outgoing = message(3, sender = null, direction = MessageDirection.OUTGOING)
        val rows = buildTimelineRows(listOf(incomingA, incomingB, outgoing), null, ZoneOffset.UTC)
            .filterIsInstance<TimelineRow.Message>()

        assertTrue(rows.all { it.startsGroup && it.endsGroup })
    }

    @Test
    @OriginalCase("DisplayFlagsTests::Calendar day change shows day divider()")
    @OriginalCase("DisplayFlagsTests::Day change detection ignores a shared local receive day()")
    fun `day boundaries create stable date dividers`() {
        val sharedReceiveDate = Instant.ofEpochSecond(100_000)
        val beforeMidnight = message(86_399).copy(sortDate = sharedReceiveDate)
        val afterMidnight = message(86_400).copy(sortDate = sharedReceiveDate)
        val rows = buildTimelineRows(listOf(beforeMidnight, afterMidnight), null, ZoneOffset.UTC)

        assertEquals(2, rows.filterIsInstance<TimelineRow.DayDivider>().size)
        assertEquals(rows.size, rows.map(TimelineRow::stableKey).distinct().size)
    }

    private companion object {
        val radioId = RadioId(UUID.fromString("10000000-0000-0000-0000-000000000001"))

        fun message(
            timestamp: Int,
            sender: String? = "Node",
            direction: MessageDirection = MessageDirection.INCOMING,
        ) = MessageDTO(
            id = UUID.nameUUIDFromBytes("$timestamp-$sender-$direction".toByteArray()),
            radioId = radioId,
            channelIndex = 1u,
            text = "message $timestamp",
            timestamp = timestamp.toUInt(),
            createdAt = Instant.ofEpochSecond(timestamp.toLong()),
            sortDate = Instant.ofEpochSecond(timestamp.toLong()),
            direction = direction,
            senderNodeName = sender,
        )
    }
}
