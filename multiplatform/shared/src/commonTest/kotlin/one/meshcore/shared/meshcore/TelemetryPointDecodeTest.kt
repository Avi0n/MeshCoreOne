package one.meshcore.shared.meshcore

import one.meshcore.shared.remotenode.LPPDataPoint
import one.meshcore.shared.remotenode.LPPSensorType
import one.meshcore.shared.remotenode.LPPValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin side of the LPP freeze. MeshCore's Swift `LPPDecoder` is the product's only Cayenne LPP
 * parser; these expectations come from the frame documented in `BridgeFixtures.telemetryFrame` and the
 * values the Swift decoder produces for it, so a divergence in either direction fails here.
 */
class TelemetryPointDecodeTest {

    private fun fixturePoints(): List<LPPDataPoint> {
        val fixture = readBridgeFixtures("results").getValue("telemetryResponse")
        val result = decodeCommandResult(fixture) as CommandResult.TelemetryResponseResult
        return result.telemetry.points
    }

    @Test
    fun everySensorTypeDecodesFromTheGoldenFrame() {
        val points = fixturePoints()
        assertEquals(LPPSensorType.entries.size, points.size)
        assertEquals(LPPSensorType.entries.toList(), points.map { it.type })
        assertTrue(points.all { it.channel == 1 }, "the golden frame puts every reading on channel 1")
    }

    @Test
    fun everySensorTypeDecodesToItsSwiftValue() {
        val decoded = fixturePoints().associate { it.type to it.value }
        assertEquals(EXPECTED, decoded)
    }

    /**
     * The four-byte LPP time is unsigned. Read as a signed 32-bit integer, `0x8000_0000` becomes
     * -2147483648 and renders as December 1901; the reading must stay 19 January 2038.
     */
    @Test
    fun unixTimePast2038KeepsItsUnsignedValue() {
        val time = fixturePoints().single { it.type == LPPSensorType.UNIX_TIME }.value
        assertEquals(LPPValue.Timestamp(2_147_483_648L), time)
        assertTrue((time as LPPValue.Timestamp).epochSeconds > Int.MAX_VALUE.toLong())
    }

    /** A generic sensor reading at or above 2^31 is likewise unsigned on the wire. */
    @Test
    fun a32BitSensorReadingStaysUnsigned() {
        val reading = fixturePoints().single { it.type == LPPSensorType.GENERIC_SENSOR }.value
        assertEquals(LPPValue.Integer(4_294_967_295L), reading)
    }

    private companion object {
        /** One reading per sensor type, as `BridgeFixtures.telemetryFrame` spells it out. */
        val EXPECTED = mapOf(
            LPPSensorType.DIGITAL_INPUT to LPPValue.Digital(true),
            LPPSensorType.DIGITAL_OUTPUT to LPPValue.Digital(false),
            LPPSensorType.ANALOG_INPUT to LPPValue.FloatValue(-4.0),
            LPPSensorType.ANALOG_OUTPUT to LPPValue.FloatValue(4.0),
            LPPSensorType.GENERIC_SENSOR to LPPValue.Integer(4_294_967_295L),
            LPPSensorType.ILLUMINANCE to LPPValue.Integer(1_000L),
            LPPSensorType.PRESENCE to LPPValue.Digital(true),
            LPPSensorType.TEMPERATURE to LPPValue.FloatValue(-20.0),
            LPPSensorType.HUMIDITY to LPPValue.FloatValue(50.0),
            LPPSensorType.ACCELEROMETER to LPPValue.Vector3(1.0, -1.0, 0.0),
            LPPSensorType.BAROMETER to LPPValue.FloatValue(1_000.0),
            LPPSensorType.VOLTAGE to LPPValue.FloatValue(3.99),
            LPPSensorType.CURRENT to LPPValue.FloatValue(-0.1),
            LPPSensorType.FREQUENCY to LPPValue.Integer(921_600_000L),
            LPPSensorType.PERCENTAGE to LPPValue.Integer(85L),
            LPPSensorType.ALTITUDE to LPPValue.FloatValue(-60.0),
            LPPSensorType.LOAD to LPPValue.FloatValue(-3.907),
            LPPSensorType.CONCENTRATION to LPPValue.Integer(500L),
            LPPSensorType.POWER to LPPValue.Integer(100L),
            LPPSensorType.DISTANCE to LPPValue.FloatValue(100.0),
            LPPSensorType.ENERGY to LPPValue.FloatValue(10.0),
            LPPSensorType.DIRECTION to LPPValue.Integer(180L),
            LPPSensorType.UNIX_TIME to LPPValue.Timestamp(2_147_483_648L),
            LPPSensorType.GYROMETER to LPPValue.Vector3(1.0, -1.0, 0.0),
            LPPSensorType.COLOUR to LPPValue.Rgb(red = 255, green = 128, blue = 0),
            LPPSensorType.GPS to LPPValue.Gps(latitude = 52.3456, longitude = -4.5678, altitude = 123.45),
            LPPSensorType.SWITCH_VALUE to LPPValue.Digital(true),
        )
    }
}
