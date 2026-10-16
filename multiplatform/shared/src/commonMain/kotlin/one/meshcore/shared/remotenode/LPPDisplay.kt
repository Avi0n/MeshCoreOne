package one.meshcore.shared.remotenode

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.intl.Locale
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_unit_foot
import one.meshcore.shared.resources.android_unit_kilometer
import one.meshcore.shared.resources.android_unit_meter
import one.meshcore.shared.resources.android_unit_mile
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_accelerometer
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_altitude
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_analog_input
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_analog_output
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_barometer
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_colour
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_concentration
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_current
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_digital_input
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_digital_output
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_direction
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_distance
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_energy
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_frequency
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_generic_sensor
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_gps
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_gyrometer
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_humidity
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_illuminance
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_load
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_percentage
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_power
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_presence
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_switch_value
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_temperature
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_unix_time
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_voltage
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Display helpers for live telemetry rows. Locale conversion uses [LPPSensorType] when the host
 * supplies a non-metric [MeasurementSystem] (or [currentMeasurementSystem] at a Compose site).
 */
object LPPDisplay {
    /** The locale's metric/imperial split, matching iOS `Locale.current.measurementSystem`. */
    @Composable
    fun currentMeasurementSystem(): MeasurementSystem = MeasurementSystem.forLocaleRegion(Locale.current.region)

    /** Localized km/m/mi/ft words matching iOS `Measurement` abbreviated length. */
    @Composable
    fun currentLengthSymbols(): LocalizedLengthSymbols = LocalizedLengthSymbols(
        kilometer = stringResource(Res.string.android_unit_kilometer),
        meter = stringResource(Res.string.android_unit_meter),
        mile = stringResource(Res.string.android_unit_mile),
        foot = stringResource(Res.string.android_unit_foot),
    )
    private const val DIGIT_ONE = 1
    private const val DIGIT_TWO = 2
    private const val DIGIT_FIVE = 5
    private const val RADIX_TEN = 10L

    /** The localized sensor-type label for a point. */
    @Composable
    fun typeLabel(point: LPPDataPoint): String = stringResource(sensorTypeResource(point.type))

    /**
     * The translated name for a sensor type. `LPPSensorType.wireName` is the English identifier
     * persisted in snapshot entries and must never reach a screen; every reader sees this instead.
     */
    fun sensorTypeResource(type: LPPSensorType): StringResource = when (type) {
        LPPSensorType.DIGITAL_INPUT,
        LPPSensorType.DIGITAL_OUTPUT,
        LPPSensorType.ANALOG_INPUT,
        LPPSensorType.ANALOG_OUTPUT,
        LPPSensorType.GENERIC_SENSOR,
        LPPSensorType.ILLUMINANCE,
        LPPSensorType.PRESENCE,
        LPPSensorType.TEMPERATURE,
        LPPSensorType.HUMIDITY,
        -> sensorResourceThroughHumidity(type)
        LPPSensorType.ACCELEROMETER,
        LPPSensorType.BAROMETER,
        LPPSensorType.VOLTAGE,
        LPPSensorType.CURRENT,
        LPPSensorType.FREQUENCY,
        LPPSensorType.PERCENTAGE,
        LPPSensorType.ALTITUDE,
        LPPSensorType.LOAD,
        LPPSensorType.CONCENTRATION,
        -> sensorResourceThroughConcentration(type)
        LPPSensorType.POWER -> Res.string.remotenodes_remote_nodes_status_sensor_power
        LPPSensorType.DISTANCE -> Res.string.remotenodes_remote_nodes_status_sensor_distance
        LPPSensorType.ENERGY -> Res.string.remotenodes_remote_nodes_status_sensor_energy
        LPPSensorType.DIRECTION -> Res.string.remotenodes_remote_nodes_status_sensor_direction
        LPPSensorType.UNIX_TIME -> Res.string.remotenodes_remote_nodes_status_sensor_unix_time
        LPPSensorType.GYROMETER -> Res.string.remotenodes_remote_nodes_status_sensor_gyrometer
        LPPSensorType.COLOUR -> Res.string.remotenodes_remote_nodes_status_sensor_colour
        LPPSensorType.GPS -> Res.string.remotenodes_remote_nodes_status_sensor_gps
        LPPSensorType.SWITCH_VALUE -> Res.string.remotenodes_remote_nodes_status_sensor_switch_value
    }

    private fun sensorResourceThroughHumidity(type: LPPSensorType): StringResource = when (type) {
        LPPSensorType.DIGITAL_INPUT -> Res.string.remotenodes_remote_nodes_status_sensor_digital_input
        LPPSensorType.DIGITAL_OUTPUT -> Res.string.remotenodes_remote_nodes_status_sensor_digital_output
        LPPSensorType.ANALOG_INPUT -> Res.string.remotenodes_remote_nodes_status_sensor_analog_input
        LPPSensorType.ANALOG_OUTPUT -> Res.string.remotenodes_remote_nodes_status_sensor_analog_output
        LPPSensorType.GENERIC_SENSOR -> Res.string.remotenodes_remote_nodes_status_sensor_generic_sensor
        LPPSensorType.ILLUMINANCE -> Res.string.remotenodes_remote_nodes_status_sensor_illuminance
        LPPSensorType.PRESENCE -> Res.string.remotenodes_remote_nodes_status_sensor_presence
        LPPSensorType.TEMPERATURE -> Res.string.remotenodes_remote_nodes_status_sensor_temperature
        LPPSensorType.HUMIDITY -> Res.string.remotenodes_remote_nodes_status_sensor_humidity
        else -> error("Unhandled sensor type: $type")
    }

    private fun sensorResourceThroughConcentration(type: LPPSensorType): StringResource = when (type) {
        LPPSensorType.ACCELEROMETER -> Res.string.remotenodes_remote_nodes_status_sensor_accelerometer
        LPPSensorType.BAROMETER -> Res.string.remotenodes_remote_nodes_status_sensor_barometer
        LPPSensorType.VOLTAGE -> Res.string.remotenodes_remote_nodes_status_sensor_voltage
        LPPSensorType.CURRENT -> Res.string.remotenodes_remote_nodes_status_sensor_current
        LPPSensorType.FREQUENCY -> Res.string.remotenodes_remote_nodes_status_sensor_frequency
        LPPSensorType.PERCENTAGE -> Res.string.remotenodes_remote_nodes_status_sensor_percentage
        LPPSensorType.ALTITUDE -> Res.string.remotenodes_remote_nodes_status_sensor_altitude
        LPPSensorType.LOAD -> Res.string.remotenodes_remote_nodes_status_sensor_load
        LPPSensorType.CONCENTRATION -> Res.string.remotenodes_remote_nodes_status_sensor_concentration
        else -> error("Unhandled sensor type: $type")
    }

    /**
     * Formats [point] for a single-line value cell. Multi-axis and GPS use a compact coordinate form;
     * floats use the type's fraction length.
     */
    fun formattedValue(
        point: LPPDataPoint,
        system: MeasurementSystem = MeasurementSystem.METRIC,
        symbols: LocalizedLengthSymbols = LocalizedLengthSymbols.LATIN,
    ): String {
        val unit = point.type.localizedUnitSymbol(system, symbols)
        return when (val value = point.value) {
            is LPPValue.Digital -> if (value.value) "On" else "Off"
            is LPPValue.Integer -> appendUnit(value.value.toString(), unit)
            is LPPValue.FloatValue -> {
                val converted = point.type.convertedValue(value.value, system)
                appendUnit(formatFixed(converted, point.type.convertedFractionLength(system)), unit)
            }
            is LPPValue.Vector3 ->
                "(${formatFixed(value.x, DIGIT_TWO)}, ${formatFixed(value.y, DIGIT_TWO)}, " +
                    "${formatFixed(value.z, DIGIT_TWO)})"
            is LPPValue.Gps ->
                "${formatFixed(value.latitude, DIGIT_FIVE)}, ${formatFixed(value.longitude, DIGIT_FIVE)} " +
                    "@ ${formatFixed(value.altitude, DIGIT_ONE)} ${symbols.meter}"
            is LPPValue.Rgb -> "RGB(${value.red}, ${value.green}, ${value.blue})"
            is LPPValue.Timestamp -> value.epochSeconds.toString()
        }
    }

    private fun appendUnit(text: String, unit: String): String = if (unit.isEmpty()) text else "$text $unit"

    /**
     * Fixed-fraction formatting without JVM `String.format`, so desktop and Android agree.
     */
    fun formatFixed(value: Double, fractionDigits: Int): String {
        if (fractionDigits <= 0) {
            return kotlin.math.round(value).toLong().toString()
        }
        val scale = pow10(fractionDigits)
        val scaled = kotlin.math.round(value * scale) / scale
        val negative = scaled < 0
        val abs = if (negative) -scaled else scaled
        val whole = abs.toLong()
        var frac = kotlin.math.round((abs - whole) * scale).toLong()
        var wholeOut = whole
        if (frac >= scale) {
            frac = 0
            wholeOut += 1
        }
        val fracStr = frac.toString().padStart(fractionDigits, '0')
        val sign = if (negative) "-" else ""
        return "$sign$wholeOut.$fracStr"
    }

    private fun pow10(n: Int): Long {
        var result = 1L
        repeat(n) { result *= RADIX_TEN }
        return result
    }
}
