package one.meshcore.shared.remotenode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Channel-0 filtering, numeric snapshot extraction, and channel grouping of decoded LPP points. */
class TelemetryConsumerTest {
    private fun point(channel: Int, type: LPPSensorType, value: LPPValue) = LPPDataPoint(channel, type, value)

    @Test
    fun displayablePointsDropChannelZeroKeepChannelOne() {
        val points = listOf(
            point(0, LPPSensorType.VOLTAGE, LPPValue.FloatValue(3.9)),
            point(1, LPPSensorType.VOLTAGE, LPPValue.FloatValue(4.0)),
            point(2, LPPSensorType.TEMPERATURE, LPPValue.FloatValue(21.0)),
        )
        val displayable = points.displayablePoints()
        assertEquals(listOf(1, 2), displayable.map { it.channel })
    }

    @Test
    fun aReadingWithNeitherNumbersNorAFixIsSkipped() {
        val digital = listOf(point(1, LPPSensorType.PRESENCE, LPPValue.Digital(true)))
        val channelZero = listOf(point(0, LPPSensorType.VOLTAGE, LPPValue.FloatValue(3.9)))
        val nullIsland = listOf(point(1, LPPSensorType.GPS, LPPValue.Gps(0.0, 0.0, 10.0)))
        assertNull(emptyList<LPPDataPoint>().telemetryReadingOrNull())
        assertNull(digital.telemetryReadingOrNull())
        assertNull(channelZero.telemetryReadingOrNull())
        assertNull(nullIsland.telemetryReadingOrNull())
    }

    @Test
    fun aGpsFixWithNoSensorsStillProducesAReading() {
        val reading = listOf(point(1, LPPSensorType.GPS, LPPValue.Gps(10.0, 20.0, 100.0))).telemetryReadingOrNull()
        assertEquals(0, reading?.entries?.size)
        assertEquals(10.0, reading?.location?.latitude)
        assertEquals(20.0, reading?.location?.longitude)
    }

    @Test
    fun snapshotEntriesKeepNumericPointsWithWireName() {
        val points = listOf(
            point(1, LPPSensorType.VOLTAGE, LPPValue.FloatValue(4.05)),
            point(1, LPPSensorType.ILLUMINANCE, LPPValue.Integer(320)),
            point(1, LPPSensorType.PRESENCE, LPPValue.Digital(true)),
            point(1, LPPSensorType.GPS, LPPValue.Gps(1.0, 2.0, 3.0)),
            point(0, LPPSensorType.VOLTAGE, LPPValue.FloatValue(9.9)),
        )
        val entries = points.telemetrySnapshotEntries()
        // Digital, GPS, and channel-0 points are dropped; numeric points carry the SI value + wire name.
        assertEquals(2, entries.size)
        assertEquals("Voltage", entries[0].type)
        assertEquals(4.05, entries[0].value)
        assertEquals("Illuminance", entries[1].type)
        assertEquals(320.0, entries[1].value)
    }

    @Test
    fun hasMultipleChannelsIgnoresChannelZero() {
        val single = listOf(
            point(0, LPPSensorType.VOLTAGE, LPPValue.FloatValue(3.9)),
            point(1, LPPSensorType.VOLTAGE, LPPValue.FloatValue(4.0)),
        )
        assertFalse(single.hasMultipleChannels())

        val multi = single + point(2, LPPSensorType.TEMPERATURE, LPPValue.FloatValue(20.0))
        assertTrue(multi.hasMultipleChannels())
    }

    @Test
    fun groupedByChannelSortsAscending() {
        val points = listOf(
            point(2, LPPSensorType.TEMPERATURE, LPPValue.FloatValue(20.0)),
            point(1, LPPSensorType.VOLTAGE, LPPValue.FloatValue(4.0)),
        )
        val grouped = points.groupedByChannel()
        assertEquals(listOf(1, 2), grouped.map { it.first })
    }

    @Test
    fun secondTemperatureOnSameChannelIsMcuByPositionNotValue() {
        val first = point(1, LPPSensorType.TEMPERATURE, LPPValue.FloatValue(20.0))
        val second = point(1, LPPSensorType.TEMPERATURE, LPPValue.FloatValue(20.0))
        val otherChannel = point(2, LPPSensorType.TEMPERATURE, LPPValue.FloatValue(21.0))
        val points = listOf(first, otherChannel, second)
        assertFalse(isMcuTemperatureAt(0, points))
        assertFalse(isMcuTemperatureAt(1, points))
        assertTrue(isMcuTemperatureAt(2, points))
    }
}
