package one.meshcore.shared.chat.ui

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class TimestampSettingsRegressionTest {
    @Test
    fun currentLocaleReplacesPreviouslyUsedClockAndDate() {
        val zone = ZoneId.of("UTC")
        val epoch = Instant.parse("2026-08-15T20:13:00Z").epochSecond
        val first = JvmTimestampFormatter(Locale.US, zone, "Unavailable")
        first.clock(epoch)
        first.date(epoch)
        val current = JvmTimestampFormatter(Locale.GERMANY, zone, "Unavailable")
        assertEquals("20:13", current.clock(epoch))
        assertEquals("15.08.2026", current.date(epoch))
    }

    @Test
    fun earlierSummerTimestampDoesNotReuseWinterOffset() {
        val formatter = JvmTimestampFormatter(Locale.US, ZoneId.of("America/Los_Angeles"), "Unavailable")
        formatter.localEpochDay(Instant.parse("2026-12-01T08:00:00Z").epochSecond)
        val summer = Instant.parse("2026-07-01T07:30:00Z").epochSecond
        assertEquals(20_635L, formatter.localEpochDay(summer))
    }

    @Test
    fun changingZoneReplacesPreviouslyUsedCalendarOffset() {
        val epoch = Instant.parse("2026-12-01T00:30:00Z").epochSecond
        val first = JvmTimestampFormatter(Locale.US, ZoneId.of("UTC"), "Unavailable")
        assertEquals(20_788L, first.localEpochDay(epoch))
        val current = JvmTimestampFormatter(Locale.US, ZoneId.of("America/Los_Angeles"), "Unavailable")
        assertEquals(20_787L, current.localEpochDay(epoch))
    }
}
