package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.remotenode.testStatusResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Locale-independent status format helpers, mirroring the iOS display properties. */
class NodeStatusFormatTest {
    @Test
    fun countGroupsThousandsWithCommas() {
        assertEquals("0", NodeStatusFormat.count(0))
        assertEquals("999", NodeStatusFormat.count(999))
        assertEquals("1,000", NodeStatusFormat.count(1_000))
        assertEquals("1,234,567", NodeStatusFormat.count(1_234_567))
        assertEquals("-1,234", NodeStatusFormat.count(-1_234))
    }

    @Test
    fun uptimeComponentsDropSubMinuteRemainders() {
        val parts = NodeStatusFormat.uptimeComponents(90_061) // 1d 1h 1m + 1s
        assertEquals(1, parts.days)
        assertEquals(1, parts.hours)
        assertEquals(1, parts.minutes)
    }

    @Test
    fun driftMagnitudeKeepsTheTwoLargestUnits() {
        val parts = NodeStatusFormat.driftMagnitudeUnits(94_861.0) // 1d 2h 21m 1s
        assertEquals(listOf(DriftUnitCount(1, DriftUnit.DAYS), DriftUnitCount(2, DriftUnit.HOURS)), parts)
    }

    @Test
    fun driftMagnitudeFallsBackToZeroSeconds() {
        assertEquals(
            listOf(DriftUnitCount(0, DriftUnit.SECONDS)),
            NodeStatusFormat.driftMagnitudeUnits(0.0),
        )
    }

    @Test
    fun voltageUsesThreeFractionDigits() {
        assertEquals("3.850", NodeStatusFormat.voltage(3850))
        assertEquals("0.000", NodeStatusFormat.voltage(0))
    }

    @Test
    fun oneDecimalUsesRoundToLongScaling() {
        assertEquals("8.5", NodeStatusFormat.oneDecimal(8.5))
        assertEquals("8.6", NodeStatusFormat.oneDecimal(8.55))
        // kotlin.math.roundToLong half-even on the scaled value: -12.5 → -12 → "-1.2"
        assertEquals("-1.2", NodeStatusFormat.oneDecimal(-1.25))
        assertEquals("1.3", NodeStatusFormat.oneDecimal(1.25))
    }

    @Test
    fun airtimePercentsNullWhenUptimeZero() {
        val status = testStatusResponse(publicKey = testPublicKey(), uptime = 0)
        assertNull(NodeStatusFormat.airtimePercents(status))
    }

    @Test
    fun airtimePercentsScaleAgainstUptime() {
        val status = testStatusResponse(publicKey = testPublicKey(), uptime = 1000)
        // airtime=500, rxAirtime=100 from the fixture → 50% / 10%
        val percents = NodeStatusFormat.airtimePercents(status)!!
        assertEquals(50.0, percents.tx)
        assertEquals(10.0, percents.rx)
    }

    @Test
    fun previousVisitUsesMinutesUnderOneHour() {
        val now = 1_000_000.0
        assertEquals(
            PreviousVisitCaption.MinutesAgo(30),
            NodeStatusFormat.previousVisitCaption(now - 1_800, now),
        )
    }

    @Test
    fun previousVisitUsesHoursFromOneHourUpToOneDay() {
        val now = 1_000_000.0
        assertEquals(
            PreviousVisitCaption.HoursAgo(1),
            NodeStatusFormat.previousVisitCaption(now - 3_600, now),
        )
        assertEquals(
            PreviousVisitCaption.HoursAgo(5),
            NodeStatusFormat.previousVisitCaption(now - 18_000, now),
        )
    }

    @Test
    fun previousVisitUsesCalendarDateAfterOneDay() {
        val now = 1_000_000.0
        val previous = now - 86_400
        assertEquals(
            PreviousVisitCaption.CalendarDate(previous.toLong()),
            NodeStatusFormat.previousVisitCaption(previous, now),
        )
    }

    @Test
    fun previousVisitClampsFutureTimestampToZeroMinutes() {
        val now = 1_000_000.0
        assertEquals(
            PreviousVisitCaption.MinutesAgo(0),
            NodeStatusFormat.previousVisitCaption(now + 60, now),
        )
    }
}
