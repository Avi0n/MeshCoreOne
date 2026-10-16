package one.meshcore.shared.chat.ui

import one.meshcore.shared.remotenode.LocationReportFormat
import one.meshcore.shared.remotenode.ui.localDeviceTime
import one.meshcore.shared.tools.rxLogReceivedLabel
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimestampRangeTest {
    private val formatter = JvmTimestampFormatter(Locale.GERMANY, ZoneId.of("UTC"), "Unavailable")
    private val normal = Instant.parse("2026-08-15T20:13:00Z").epochSecond

    @Test
    fun unsupportedLongsReturnNullAndTheSameFormatterRecovers() {
        val operations = listOf(
            formatter::clock,
            formatter::clockWithSeconds,
            formatter::date,
            formatter::monthDay,
            formatter::monthDayClock,
        )
        for (operation in operations) {
            val expected = operation(normal)
            assertNotEquals(null, expected)
            for (unsupported in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
                assertNull(operation(unsupported))
                assertEquals(expected, operation(normal))
            }
        }
        assertEquals("20:13", formatter.clock(normal))
        assertEquals("20:13:00", formatter.clockWithSeconds(normal))
        assertEquals("15.08.2026", formatter.date(normal))
        assertNull(formatter.localEpochDay(Long.MIN_VALUE))
        assertNull(formatter.localEpochDay(Long.MAX_VALUE))
        assertEquals(20_680L, formatter.localEpochDay(normal))
    }

    @Test
    fun unsupportedCompoundLabelsResolveOneFallback() {
        for (unsupported in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertEquals(formatter.unavailable, formatMessageDetailDateTime(unsupported.toDouble(), formatter))
            assertEquals(formatter.unavailable, localDeviceTime(unsupported, formatter))
            assertEquals(formatter.unavailable, LocationReportFormat.absoluteTime(unsupported.toDouble(), formatter))
        }
        assertEquals("15.08.2026, 20:13", formatMessageDetailDateTime(normal.toDouble(), formatter))
        assertEquals("20:13 - 15.08.2026", localDeviceTime(normal, formatter))
        assertEquals("15.08.2026 20:13:00", rxLogReceivedLabel(normal * 1000L, formatter))
    }

    @Test
    fun unsupportedDaysCannotBecomeTodayOrYesterday() {
        for (unsupported in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertEquals(
                ConversationListTime.MonthDay(formatter.unavailable),
                conversationListTime(unsupported, unsupported, formatter),
            )
            val day = chatDayLabel(unsupported.toDouble(), formatter, unsupported)
            assertEquals(ChatDayLabel.Kind.OTHER, day.kind)
            assertEquals(formatter.unavailable, day.absolute)
        }
    }

    @Test
    fun doubleEpochConversionRejectsSaturationAndNonfiniteValues() {
        for (unsupported in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e19, -1e19)) {
            assertNull(unsupported.toTimestampEpochSecondOrNull())
            assertEquals(formatter.unavailable, chatClockLabel(unsupported, formatter))
            assertEquals(formatter.unavailable, formatMessageDetailDateTime(unsupported, formatter))
            assertEquals(formatter.unavailable, LocationReportFormat.absoluteTime(unsupported, formatter))
            assertEquals(
                ConversationListTime.MonthDay(formatter.unavailable),
                conversationListTime(unsupported, normal, formatter),
            )
            val day = chatDayLabel(unsupported, formatter, normal)
            assertEquals(ChatDayLabel.Kind.OTHER, day.kind)
            assertEquals(formatter.unavailable, day.absolute)
        }
        assertNull(Long.MAX_VALUE.toDouble().toTimestampEpochSecondOrNull())
        assertEquals(Long.MIN_VALUE, Long.MIN_VALUE.toDouble().toTimestampEpochSecondOrNull())
        assertEquals(-1L, (-1.9).toTimestampEpochSecondOrNull())
        assertEquals(0L, (-0.9).toTimestampEpochSecondOrNull())
        assertEquals(1L, 1.9.toTimestampEpochSecondOrNull())
    }

    @Test
    fun desktopRetainsItsWiderCivilDateRange() {
        val huge = 10_000_000_000_000_000L
        val text = formatMessageDetailDateTime(huge.toDouble(), formatter)
        assertTrue(text.contains("316889355"), text)
        assertNotEquals(formatter.unavailable, text)
    }
}
