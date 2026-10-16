package one.meshcore.shared.remotenode

import one.meshcore.shared.chat.ui.chatMonthDayClockLabel
import one.meshcore.shared.timestampTestFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationReportFormatTest {
    @Test
    fun relativeRecencyRoundsToCoarsestWholeUnit() {
        assertEquals(Recency(RecencyUnit.SECONDS, 30), LocationReportFormat.relativeRecency(30.0))
        assertEquals(Recency(RecencyUnit.MINUTES, 5), LocationReportFormat.relativeRecency(5 * 60.0 + 40))
        assertEquals(Recency(RecencyUnit.HOURS, 2), LocationReportFormat.relativeRecency(2 * 3600.0 + 59 * 60))
        assertEquals(Recency(RecencyUnit.DAYS, 3), LocationReportFormat.relativeRecency(3 * 86_400.0 + 3600))
    }

    @Test
    fun relativeRecencyClampsNegativeToZeroSeconds() {
        assertEquals(Recency(RecencyUnit.SECONDS, 0), LocationReportFormat.relativeRecency(-120.0))
    }

    @Test
    fun coordinatesAreFourDecimalDottedAndSignPreserving() {
        assertEquals("37.7847, -122.4012", LocationReportFormat.coordinates(37.78471, -122.40123))
        assertEquals("0.0000, 0.0000", LocationReportFormat.coordinates(0.0, 0.0))
    }

    @Test
    fun altitudeRoundsToWholeMeters() {
        assertEquals("42 m", LocationReportFormat.altitude(41.6, MeasurementSystem.METRIC))
        assertEquals("-3 m", LocationReportFormat.altitude(-3.2, MeasurementSystem.METRIC))
    }

    @Test
    fun altitudeConvertsToWholeFeetInImperial() {
        assertEquals("136 ft", LocationReportFormat.altitude(41.6, MeasurementSystem.IMPERIAL))
    }

    @Test
    fun altitudeUsesInjectedLengthSymbols() {
        val cyrillic = LocalizedLengthSymbols(
            kilometer = "км",
            meter = "м",
            mile = "мили",
            foot = "фут",
        )
        assertEquals("42 м", LocationReportFormat.altitude(41.6, MeasurementSystem.METRIC, cyrillic))
        assertEquals("136 фут", LocationReportFormat.altitude(41.6, MeasurementSystem.IMPERIAL, cyrillic))
    }

    @Test
    fun absoluteTimeUsesLocalMonthDayAndClock() {
        val epoch = 1_783_928_460L
        val label = LocationReportFormat.absoluteTime(epoch.toDouble(), formatter = timestampTestFormatter)
        assertEquals(chatMonthDayClockLabel(epoch, formatter = timestampTestFormatter), label)
        assertTrue("T" !in label)
        assertTrue("2026-07-13" !in label)
    }

    @Test
    fun locationReportsKeepsOnlyValidFixesNewestFirst() {
        val reports = locationReports(
            listOf(
                locationSnapshot(0.0, null, null),
                locationSnapshot(1.0, 0.0, 0.0),
                locationSnapshot(2.0, 37.0, -122.0),
                locationSnapshot(3.0, 38.0, -121.0),
            ),
        )
        assertEquals(2, reports.size)
        assertEquals(3.0, reports[0].timestamp)
        assertEquals(2.0, reports[1].timestamp)
    }
}
