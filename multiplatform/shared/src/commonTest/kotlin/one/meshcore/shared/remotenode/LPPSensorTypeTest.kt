package one.meshcore.shared.remotenode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The persisted-name reverse lookup, SI units, chart priority, and locale conversion of [LPPSensorType]. */
class LPPSensorTypeTest {
    @Test
    fun fromNameMatchesPersistedWireNames() {
        assertEquals(LPPSensorType.VOLTAGE, LPPSensorType.fromName("Voltage"))
        // The wire name diverges from the case name for several types; the persisted string is what matters.
        assertEquals(LPPSensorType.BAROMETER, LPPSensorType.fromName("Pressure"))
        assertEquals(LPPSensorType.GENERIC_SENSOR, LPPSensorType.fromName("Sensor"))
        assertEquals(LPPSensorType.UNIX_TIME, LPPSensorType.fromName("Time"))
        assertNull(LPPSensorType.fromName("Nonexistent"))
    }

    @Test
    fun unitsAndSortPriority() {
        assertEquals("V", LPPSensorType.VOLTAGE.unit)
        assertEquals("hPa", LPPSensorType.BAROMETER.unit)
        assertEquals("°C", LPPSensorType.TEMPERATURE.unit)
        // Voltage sorts first; everything else ties at priority 1.
        assertEquals(0, LPPSensorType.VOLTAGE.chartSortPriority)
        assertEquals(1, LPPSensorType.TEMPERATURE.chartSortPriority)
    }

    @Test
    fun metricSystemLeavesValuesUnchanged() {
        assertEquals(25.0, LPPSensorType.TEMPERATURE.convertedValue(25.0, MeasurementSystem.METRIC))
        assertEquals(1013.25, LPPSensorType.BAROMETER.convertedValue(1013.25, MeasurementSystem.METRIC))
        assertEquals("°C", LPPSensorType.TEMPERATURE.localizedUnitSymbol(MeasurementSystem.METRIC))
    }

    @Test
    fun imperialConvertsLocaleSensitiveTypes() {
        assertEquals(77.0, LPPSensorType.TEMPERATURE.convertedValue(25.0, MeasurementSystem.IMPERIAL), 0.0001)
        assertEquals(
            29.9214,
            LPPSensorType.BAROMETER.convertedValue(1013.25, MeasurementSystem.IMPERIAL),
            0.001,
        )
        assertEquals(328.0839, LPPSensorType.ALTITUDE.convertedValue(100.0, MeasurementSystem.IMPERIAL), 0.001)
        assertEquals(3280.8399, LPPSensorType.DISTANCE.convertedValue(1000.0, MeasurementSystem.IMPERIAL), 0.001)
        assertEquals("°F", LPPSensorType.TEMPERATURE.localizedUnitSymbol(MeasurementSystem.IMPERIAL))
        assertEquals("inHg", LPPSensorType.BAROMETER.localizedUnitSymbol(MeasurementSystem.IMPERIAL))
        assertEquals("ft", LPPSensorType.ALTITUDE.localizedUnitSymbol(MeasurementSystem.IMPERIAL))
    }

    @Test
    fun imperialLeavesNonLocaleSensitiveTypesUnchanged() {
        assertEquals(3.85, LPPSensorType.VOLTAGE.convertedValue(3.85, MeasurementSystem.IMPERIAL))
        assertEquals("V", LPPSensorType.VOLTAGE.localizedUnitSymbol(MeasurementSystem.IMPERIAL))
    }

    @Test
    fun convertedFractionLengthMatchesSwift() {
        assertEquals(1, LPPSensorType.TEMPERATURE.convertedFractionLength(MeasurementSystem.METRIC))
        assertEquals(3, LPPSensorType.DISTANCE.convertedFractionLength(MeasurementSystem.METRIC))
        assertEquals(0, LPPSensorType.ALTITUDE.convertedFractionLength(MeasurementSystem.IMPERIAL))
        assertEquals(2, LPPSensorType.BAROMETER.convertedFractionLength(MeasurementSystem.IMPERIAL))
    }

    @Test
    fun forLocaleRegionMatchesMapScaleSplit() {
        assertEquals(MeasurementSystem.IMPERIAL, MeasurementSystem.forLocaleRegion("US"))
        assertEquals(MeasurementSystem.IMPERIAL, MeasurementSystem.forLocaleRegion("GB"))
        assertEquals(MeasurementSystem.METRIC, MeasurementSystem.forLocaleRegion("DE"))
        assertEquals(MeasurementSystem.METRIC, MeasurementSystem.forLocaleRegion(""))
    }
}
