package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.ui.JvmTimestampFormatter
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Chats-list trailing time buckets (iOS `ConversationTimestamp`): clock today, yesterday, month+day.
 */
class ConversationListTimeTest {
    private val formatter = JvmTimestampFormatter(Locale.US, ZoneId.systemDefault(), "Unavailable")
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 8, 15)
    private val nowSeconds = today.atTime(LocalTime.of(18, 0)).atZone(zone).toEpochSecond()

    @Test
    fun todayIsTheClockTime() {
        val morning = today.atTime(LocalTime.of(10, 30)).atZone(zone).toEpochSecond()
        val stamp = conversationListTime(morning, nowSeconds, formatter = formatter)
        assertIs<ConversationListTime.Clock>(stamp)
        assertTrue(stamp.text.isNotBlank())
    }

    @Test
    fun previousLocalDayIsYesterday() {
        val yesterday = today.minusDays(1).atTime(LocalTime.of(10, 0)).atZone(zone).toEpochSecond()
        assertEquals(ConversationListTime.Yesterday, conversationListTime(yesterday, nowSeconds, formatter = formatter))
    }

    @Test
    fun roomClusterTimeUsesLocalClockNotUtcHours() {
        val local = today.atTime(LocalTime.of(15, 0)).atZone(zone)
        val label = chatMonthDayClockLabel(local.toEpochSecond(), formatter = formatter)
        assertTrue(label.contains(chatClockLabel(local.toEpochSecond().toDouble(), formatter = formatter)))
        val utcHours = local.withZoneSameInstant(java.time.ZoneOffset.UTC).hour
        val utcStamp = utcHours.toString().padStart(2, '0') + ":00"
        if (utcHours != local.hour) {
            assertTrue(utcStamp !in label, "must not print UTC $utcStamp")
        }
    }

    @Test
    fun olderDaysAreMonthAndDayWithoutAYear() {
        val older = today.minusDays(20).atTime(LocalTime.of(10, 0)).atZone(zone).toEpochSecond()
        val stamp = conversationListTime(older, nowSeconds, formatter = formatter)
        val monthDay = assertIs<ConversationListTime.MonthDay>(stamp)
        assertTrue(monthDay.text.isNotBlank())
        assertTrue("2026" !in monthDay.text, "month+day must omit the year")
        assertEquals(chatMonthDayLabel(older, formatter = formatter), monthDay.text)
    }

    @Test
    fun timestampTextUsesClockYesterdayAndMonthDay() {
        val morning = today.atTime(LocalTime.of(10, 30)).atZone(zone).toEpochSecond()
        val yesterday = today.minusDays(1).atTime(LocalTime.of(10, 0)).atZone(zone).toEpochSecond()
        val older = today.minusDays(20).atTime(LocalTime.of(10, 0)).atZone(zone).toEpochSecond()
        val yesterdayWord = "Yesterday"
        val todayStamp = conversationListTime(morning, nowSeconds, formatter = formatter)
        val clock = assertIs<ConversationListTime.Clock>(todayStamp)
        assertEquals(clock.text, conversationTimestampText(todayStamp, yesterdayWord))
        assertEquals(
            yesterdayWord,
            conversationTimestampText(
                conversationListTime(yesterday, nowSeconds, formatter = formatter),
                yesterdayWord,
            ),
        )
        val olderStamp = conversationListTime(older, nowSeconds, formatter = formatter)
        val monthDay = assertIs<ConversationListTime.MonthDay>(olderStamp)
        assertEquals(monthDay.text, conversationTimestampText(olderStamp, yesterdayWord))
    }

    @Test
    fun monthDayLabelIsCalendarMonthAndDayNotADayIndex() {
        val local = today.atTime(LocalTime.of(10, 0)).atZone(zone).toEpochSecond()
        val label = chatMonthDayLabel(local, formatter = formatter)
        assertTrue(label.isNotBlank())
        assertTrue("2026" !in label, "month+day must omit the year")
        val dayIndex = local / 86_400L
        assertTrue("d$dayIndex" !in label, "must not print epoch day-index d$dayIndex")
    }
}
