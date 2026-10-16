package one.meshcore.shared.remotenode

import one.meshcore.shared.chat.ui.JvmTimestampFormatter
import one.meshcore.shared.chat.ui.chatClockLabel
import one.meshcore.shared.chat.ui.chatMonthDayClockLabel
import one.meshcore.shared.chat.ui.chatMonthDayLabel
import one.meshcore.shared.remotenode.ui.formatScrubTimestamp
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Location History and chart-scrub stamps use the device zone (iOS `.dateTime.month().day().hour().minute()`),
 * not a UTC ISO slice.
 */
class LocationHistoryLocalTimeTest {
    private val formatter = JvmTimestampFormatter(Locale.US, ZoneId.systemDefault(), "Unavailable")

    @Test
    fun absoluteTimeUsesLocalMonthDayAndClockNotUtcIso() {
        val zone = ZoneId.systemDefault()
        val local = LocalDate.of(2026, 7, 13).atTime(LocalTime.of(7, 41)).atZone(zone)
        val epoch = local.toEpochSecond()
        val label = LocationReportFormat.absoluteTime(epoch.toDouble(), formatter = formatter)
        assertEquals(chatMonthDayClockLabel(epoch, formatter = formatter), label)
        assertTrue(chatMonthDayLabel(epoch, formatter = formatter) in label)
        assertTrue(chatClockLabel(epoch.toDouble(), formatter = formatter) in label)
        assertTrue("2026-07-13" !in label, "must not print UTC ISO date")
        val utcHours = local.withZoneSameInstant(ZoneOffset.UTC).hour
        val utcStamp = utcHours.toString().padStart(2, '0') + ":41"
        if (utcHours != local.hour) {
            assertTrue(utcStamp !in label, "must not print UTC $utcStamp")
        }
    }

    @Test
    fun chartScrubTimestampMatchesLocationHistory() {
        val zone = ZoneId.systemDefault()
        val local = LocalDate.of(2026, 7, 13).atTime(LocalTime.of(15, 0)).atZone(zone)
        val epoch = local.toEpochSecond().toDouble()
        assertEquals(
            LocationReportFormat.absoluteTime(epoch, formatter = formatter),
            formatScrubTimestamp(epoch, formatter = formatter),
        )
    }
}
