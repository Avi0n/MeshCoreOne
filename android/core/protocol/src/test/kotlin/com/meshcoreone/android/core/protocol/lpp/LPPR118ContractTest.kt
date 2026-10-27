// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/LPPPythonReferenceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/RoundTripTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.lpp

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.TelemetryResponse
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LPPR118ContractTest {
    private data class Family(val name: String, val bytes: Bytes, val points: List<LPPDataPoint>)

    private fun scalar(channel: UByte, type: LPPSensorType, value: Double) =
        LPPDataPoint(channel, type, LPPValue.Float(value))

    private fun family(name: String, hex: String, vararg points: LPPDataPoint) =
        Family(name, Bytes.fromHex(hex), points.toList())

    private val families = listOf(
        family("Temperature 25.5 matches Python", "016700ff", scalar(1u, LPPSensorType.TEMPERATURE, 25.5)),
        family("Temperature negative round trip", "0167ff97", scalar(1u, LPPSensorType.TEMPERATURE, -10.5)),
        family("Humidity 65 matches Python", "026882", scalar(2u, LPPSensorType.HUMIDITY, 65.0)),
        family("Analog input 3.3 matches Python", "0302014a", scalar(3u, LPPSensorType.ANALOG_INPUT, 3.3)),
        family(
            "GPS SF matches Python", "048805c395ed51fe0003e8",
            LPPDataPoint(4u, LPPSensorType.GPS, LPPValue.Gps(37.7749, -122.4194, 10.0)),
        ),
        family(
            "GPS decode round trip", "048805c395ed51fe0003e8",
            LPPDataPoint(4u, LPPSensorType.GPS, LPPValue.Gps(37.7749, -122.4194, 10.0)),
        ),
        family("Barometer 1013 matches Python", "05732794", scalar(5u, LPPSensorType.BAROMETER, 1013.2)),
        family(
            "Accelerometer 1g matches Python", "06710000000003e8",
            LPPDataPoint(6u, LPPSensorType.ACCELEROMETER, LPPValue.Vector3(0.0, 0.0, 1.0)),
        ),
        family(
            "Accelerometer decode round trip", "067101f4fe0c03e8",
            LPPDataPoint(6u, LPPSensorType.ACCELEROMETER, LPPValue.Vector3(0.5, -0.5, 1.0)),
        ),
        family(
            "Multi-sensor payload", "016700ff02688203732794",
            scalar(1u, LPPSensorType.TEMPERATURE, 25.5), scalar(2u, LPPSensorType.HUMIDITY, 65.0),
            scalar(3u, LPPSensorType.BAROMETER, 1013.2),
        ),
        family("Voltage encoding", "0174017c", scalar(1u, LPPSensorType.VOLTAGE, 3.8)),
        family("Illuminance encoding", "016503e8", LPPDataPoint(1u, LPPSensorType.ILLUMINANCE, LPPValue.Integer(1000))),
        family(
            "Digital IO encoding", "010001020100",
            LPPDataPoint(1u, LPPSensorType.DIGITAL_INPUT, LPPValue.Digital(true)),
            LPPDataPoint(2u, LPPSensorType.DIGITAL_OUTPUT, LPPValue.Digital(false)),
        ),
        family(
            "Gyrometer encoding", "0186041afdf30000",
            LPPDataPoint(1u, LPPSensorType.GYROMETER, LPPValue.Vector3(10.5, -5.25, 0.0)),
        ),
        family(
            "Load positive decodes as 3-byte signed divided by 1000", "077a003039",
            scalar(7u, LPPSensorType.LOAD, 12.345),
        ),
        family(
            "Load negative round trips through 24-bit sign extension", "077afffa24",
            scalar(7u, LPPSensorType.LOAD, -1.5),
        ),
        family(
            "Load consumes three bytes so the next datum stays aligned", "077a0003e8016700ff",
            scalar(7u, LPPSensorType.LOAD, 1.0), scalar(1u, LPPSensorType.TEMPERATURE, 25.5),
        ),
        family(
            "Generic sensor decodes high bit set as a large positive integer", "016480000000",
            LPPDataPoint(1u, LPPSensorType.GENERIC_SENSOR, LPPValue.Integer(2_147_483_648L)),
        ),
        family(
            "LPP encoder/decoder temperature round trip", "016700e1",
            scalar(1u, LPPSensorType.TEMPERATURE, 22.5),
        ),
        family(
            "LPP encoder/decoder GPS round trip", "038805c395ed51fe001388",
            LPPDataPoint(3u, LPPSensorType.GPS, LPPValue.Gps(37.7749, -122.4194, 50.0)),
        ),
        family(
            "LPP encoder/decoder multi-sensor round trip", "016700fa02687803740172",
            scalar(1u, LPPSensorType.TEMPERATURE, 25.0), scalar(2u, LPPSensorType.HUMIDITY, 60.0),
            scalar(3u, LPPSensorType.VOLTAGE, 3.7),
        ),
        family(
            "LPP encoder/decoder negative temperature round trip", "0167ff65",
            scalar(1u, LPPSensorType.TEMPERATURE, -15.5),
        ),
        family(
            "LPP encoder/decoder accelerometer round trip", "0571fe0c00fa03e8",
            LPPDataPoint(5u, LPPSensorType.ACCELEROMETER, LPPValue.Vector3(-0.5, 0.25, 1.0)),
        ),
    )

    @TestFactory
    fun `All 23 original families have independent complete outputs`(): List<DynamicTest> {
        assertEquals(23, families.size)
        assertEquals(23, families.map { it.name }.toSet().size)
        return families.map { family ->
            DynamicTest.dynamicTest(family.name) {
                val result = assertIs<LPPDecodeResult.Complete>(LPPDecoder.decode(family.bytes))
                assertTrue(result.isComplete)
                assertEquals(family.bytes.size, result.consumedByteCount)
                assertEquals(family.points, result.dataPoints)
                assertEquals(family.points, result.requireComplete())
                assertEquals(family.points, LPPDecoder.decodeStrict(family.bytes))
                val response = TelemetryResponse(Bytes.fromHex("010203040506"), null, family.bytes)
                assertIs<LPPDecodeResult.Complete>(response.decodeResult)
                assertEquals(family.points, response.dataPoints)
            }
        }
    }

    @TestFactory
    fun `Malformed tails never turn any original family into a complete telemetry response`(): List<DynamicTest> =
        families.flatMap { family ->
            val offset = family.bytes.size
            listOf(
                Bytes.of(255) to LPPDecodeDiagnostic.TruncatedHeader(offset, 1),
                (Bytes.of(255, 255) + PythonLPPReferenceBytes.humidity65) to
                    LPPDecodeDiagnostic.UnknownSensorType(offset, 255u, 255u),
                Bytes.of(255, 103, 128) to
                    LPPDecodeDiagnostic.TruncatedValue(offset, 255u, LPPSensorType.TEMPERATURE, 1),
            ).map { (suffix, diagnostic) ->
                DynamicTest.dynamicTest("${family.name} ${diagnostic.javaClass.simpleName}") {
                    val payload = family.bytes + suffix
                    val result = assertIs<LPPDecodeResult.Incomplete>(LPPDecoder.decode(payload))
                    assertFalse(result.isComplete)
                    assertEquals(family.points, result.dataPoints)
                    assertEquals(offset, result.consumedByteCount)
                    assertEquals(suffix, result.remainingData)
                    assertEquals(diagnostic, result.diagnostic)
                    val error = assertFailsWith<LPPDecodingException> { result.requireComplete() }
                    assertSame(result.diagnostic, error.diagnostic)
                    val strictError = assertFailsWith<LPPDecodingException> { LPPDecoder.decodeStrict(payload) }
                    assertEquals(diagnostic, strictError.diagnostic)
                    val response = TelemetryResponse(Bytes.fromHex("010203040506"), Bytes.of(1, 2, 3, 4), payload)
                    val consumer = assertIs<LPPDecodeResult.Incomplete>(response.decodeResult)
                    assertSame(consumer, response.decodeResult)
                    assertEquals(payload, response.rawData)
                    assertEquals(family.points, response.dataPoints)
                    assertEquals(offset, consumer.consumedByteCount)
                    assertEquals(suffix, consumer.remainingData)
                    assertEquals(diagnostic, consumer.diagnostic)
                }
            }
        }

    @TestFactory
    fun `Original floating encoder entry points recover after checked rejection`(): List<DynamicTest> {
        data class Rejection(
            val name: String,
            val type: LPPSensorType,
            val field: String,
            val value: Double,
            val scale: Double,
            val minimum: Long,
            val maximum: Long,
            val append: LPPEncoder.() -> Unit,
        )
        val cases = listOf(
            Rejection("analog input", LPPSensorType.ANALOG_INPUT, "value", 400.0, 100.0, -32768, 32767) {
                addAnalogInput(255u, 400.0)
            },
            Rejection("analog output", LPPSensorType.ANALOG_OUTPUT, "value", -400.0, 100.0, -32768, 32767) {
                addAnalogOutput(255u, -400.0)
            },
            Rejection("temperature", LPPSensorType.TEMPERATURE, "value", 4000.0, 10.0, -32768, 32767) {
                addTemperature(255u, 4000.0)
            },
            Rejection("humidity", LPPSensorType.HUMIDITY, "value", 128.0, 2.0, 0, 255) {
                addHumidity(255u, 128.0)
            },
            Rejection("barometer", LPPSensorType.BAROMETER, "value", -1.0, 10.0, 0, 65535) {
                addBarometer(255u, -1.0)
            },
            Rejection("voltage", LPPSensorType.VOLTAGE, "value", 656.0, 100.0, 0, 65535) {
                addVoltage(255u, 656.0)
            },
            Rejection("accelerometer z", LPPSensorType.ACCELEROMETER, "z", 40.0, 1000.0, -32768, 32767) {
                addAccelerometer(255u, 1.0, -1.0, 40.0)
            },
            Rejection("gyrometer z", LPPSensorType.GYROMETER, "z", -400.0, 100.0, -32768, 32767) {
                addGyrometer(255u, 1.0, -1.0, -400.0)
            },
            Rejection("GPS altitude", LPPSensorType.GPS, "altitude", 83886.08, 100.0, -8388608, 8388607) {
                addGPS(255u, 37.7749, -122.4194, 83886.08)
            },
        )
        return cases.map { case ->
            DynamicTest.dynamicTest(case.name) {
                val encoder = LPPEncoder().apply { addTemperature(1u, 25.5) }
                val before = encoder.encode()
                assertEquals(PythonLPPReferenceBytes.temperature25_5, before)
                val error = assertFailsWith<LPPEncodingException.NumericOutOfRange> { case.append(encoder) }
                assertEquals(case.type, error.type)
                assertEquals(case.field, error.field)
                assertEquals(case.value, error.value)
                assertEquals(case.scale, error.scale)
                assertEquals(case.minimum, error.minimumRaw)
                assertEquals(case.maximum, error.maximumRaw)
                assertEquals(before, encoder.encode())
                assertEquals(before.size, encoder.count)
                encoder.addHumidity(2u, 65.0)
                assertEquals(Bytes.fromHex("016700ff026882"), encoder.encode())
                assertEquals(7, encoder.count)
                assertEquals(PythonLPPReferenceBytes.temperature25_5, before)
                assertEquals(
                    listOf(scalar(1u, LPPSensorType.TEMPERATURE, 25.5), scalar(2u, LPPSensorType.HUMIDITY, 65.0)),
                    LPPDecoder.decodeStrict(encoder.encode()),
                )
                encoder.reset()
                assertEquals(0, encoder.count)
                assertEquals(Bytes.EMPTY, encoder.encode())
            }
        }
    }
}
