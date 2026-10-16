package one.meshcore.shared.remotenode

import one.meshcore.shared.resources.loadComposeStringsXml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Telemetry rows must never render `LPPSensorType.wireName` — that is the English identifier persisted
 * in snapshot entries. Compose's own resource reads need a composition, so the localization guarantee is
 * asserted through the pure [LPPDisplay.sensorTypeResource] mapping the composable delegates to, plus
 * the English values those keys resolve to.
 */
class LPPDisplayTest {
    @Test
    fun everySensorTypeResolvesToALocalizedResource() {
        val keys = LPPSensorType.entries.associateWith { LPPDisplay.sensorTypeResource(it).key }
        assertEquals(LPPSensorType.entries.size, keys.values.toSet().size, "two sensor types share a key")
        assertTrue(
            keys.values.all { it.startsWith("remotenodes_remote_nodes_status_sensor_") },
            "unexpected key names: ${keys.values.sorted()}",
        )
    }

    /**
     * The translated names must stay value-for-value with the Swift `LPPSensorType.name`, because that
     * string is also the persisted snapshot key: a drift would silently split a sensor's history.
     */
    @Test
    fun englishResourceValuesMatchTheSwiftSensorNames() {
        val english = englishStrings()
        for (type in LPPSensorType.entries) {
            val key = LPPDisplay.sensorTypeResource(type).key
            assertEquals(type.wireName, english[key], "values/strings.xml disagrees on $key")
        }
    }

    @Test
    fun formattedVoltageUsesUnit() {
        // Voltage uses the default 1 fraction digit (SI display).
        val point = LPPDataPoint(1, LPPSensorType.VOLTAGE, LPPValue.FloatValue(3.88))
        assertEquals("3.9 V", LPPDisplay.formattedValue(point))
    }

    @Test
    fun formattedTemperatureUsesLocaleUnits() {
        val point = LPPDataPoint(1, LPPSensorType.TEMPERATURE, LPPValue.FloatValue(25.0))
        assertEquals("25.0 °C", LPPDisplay.formattedValue(point, MeasurementSystem.METRIC))
        assertEquals("77.0 °F", LPPDisplay.formattedValue(point, MeasurementSystem.IMPERIAL))
    }

    @Test
    fun formattedRgbMatchesIosRgbTuple() {
        val point = LPPDataPoint(1, LPPSensorType.COLOUR, LPPValue.Rgb(12, 34, 56))
        assertEquals("RGB(12, 34, 56)", LPPDisplay.formattedValue(point))
    }

    @Test
    fun formattedVector3MatchesIosParentheses() {
        val point = LPPDataPoint(
            1,
            LPPSensorType.ACCELEROMETER,
            LPPValue.Vector3(0.12, -0.4, 1.0),
        )
        assertEquals("(0.12, -0.40, 1.00)", LPPDisplay.formattedValue(point))
    }

    @Test
    fun formattedGpsUsesFiveFractionDigits() {
        val point = LPPDataPoint(
            1,
            LPPSensorType.GPS,
            LPPValue.Gps(37.7749, -122.4194, 12.3),
        )
        assertEquals("37.77490, -122.41940 @ 12.3 m", LPPDisplay.formattedValue(point))
    }

    private fun englishStrings(): Map<String, String> = STRING_REGEX
        .findAll(loadComposeStringsXml("values"))
        .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        val STRING_REGEX = Regex("""<string\s+name="([^"]+)">(.*?)</string>""")
    }
}
