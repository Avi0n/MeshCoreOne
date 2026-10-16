package one.meshcore.shared.chat.ui

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TimestampCalendarTest {
    private val losAngeles = JvmTimestampFormatter(Locale.US, ZoneId.of("America/Los_Angeles"), "Unavailable")

    @Test
    fun yesterdayUsesCalendarDaysAcrossShortAndLongDstDays() {
        val cases = listOf(
            "2026-03-08T08:30:00Z" to "2026-03-09T07:30:00Z",
            "2026-11-01T07:30:00Z" to "2026-11-02T08:30:00Z",
        )
        for ((previous, current) in cases) {
            val epoch = Instant.parse(previous).epochSecond
            val now = Instant.parse(current).epochSecond
            assertEquals(ConversationListTime.Yesterday, conversationListTime(epoch, now, losAngeles))
            assertEquals(ChatDayLabel.Kind.YESTERDAY, chatDayLabel(epoch.toDouble(), losAngeles, now).kind)
        }
    }

    @Test
    fun zoneSnapshotChangesTheTodayBoundary() {
        val epoch = Instant.parse("2026-12-01T00:30:00Z").epochSecond
        val now = Instant.parse("2026-12-01T12:00:00Z").epochSecond
        val utc = JvmTimestampFormatter(Locale.UK, ZoneId.of("UTC"), "Unavailable")
        assertIs<ConversationListTime.Clock>(conversationListTime(epoch, now, utc))
        assertEquals(ConversationListTime.Yesterday, conversationListTime(epoch, now, losAngeles))
    }

    @Test
    fun negativeEpochUsesThePreviousLocalDay() {
        val utc = JvmTimestampFormatter(Locale.UK, ZoneId.of("UTC"), "Unavailable")
        assertEquals(-1L, utc.localEpochDay(-1))
        assertEquals(ConversationListTime.Yesterday, conversationListTime(-1, 0, utc))
        assertEquals(ChatDayLabel.Kind.YESTERDAY, chatDayLabel(-1.0, utc, 0).kind)
    }

    @Test
    fun repeatedCivilClockStillHasTheSameCalendarDay() {
        val earlier = Instant.parse("2026-11-01T08:30:00Z").epochSecond
        val later = Instant.parse("2026-11-01T09:30:00Z").epochSecond
        assertEquals(losAngeles.clock(earlier), losAngeles.clock(later))
        assertEquals(losAngeles.localEpochDay(earlier), losAngeles.localEpochDay(later))
        assertIs<ConversationListTime.Clock>(conversationListTime(earlier, later, losAngeles))
    }
}
