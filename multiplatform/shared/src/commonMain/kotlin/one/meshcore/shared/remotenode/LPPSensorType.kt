package one.meshcore.shared.remotenode

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.meshcore.shared.map.localeUsesMetric

/**
 * The user's measurement system, deciding whether locale-sensitive telemetry values are converted from
 * SI. The Swift side reads `Locale.current.measurementSystem`; only the metric-vs-non-metric split
 * matters (US and UK both convert), so this port collapses it to two cases. The platform supplies the
 * value; shared logic stays deterministic.
 */
enum class MeasurementSystem {
    METRIC,
    IMPERIAL,
    ;

    companion object {
        /**
         * US, GB, Liberia, and Myanmar convert, matching map-scale [localeUsesMetric] and iOS
         * `Locale.current.measurementSystem != .metric`.
         */
        fun forLocaleRegion(region: String): MeasurementSystem = if (localeUsesMetric(region)) METRIC else IMPERIAL
    }
}

/**
 * Cayenne Low Power Payload (LPP) sensor types, mirroring the Swift `LPPSensorType` (`UInt8`-raw). The
 * bridge decodes LPP frames; Kotlin only *consumes* the decoded points, so the byte-level decoder is not
 * ported. What is load-bearing here is [wireName]: it is the English string persisted in
 * `TelemetrySnapshotEntry.type` and the reverse-lookup key ([fromName]), so it must match the Swift
 * `name` byte-for-byte or old snapshots stop grouping. It is never shown to a user — the localized
 * label comes from `LPPDisplay.sensorTypeResource`. [unit] is the SI symbol; locale conversion is
 * applied only at display/chart-build time.
 */
@Serializable(with = LPPSensorTypeRawSerializer::class)
enum class LPPSensorType(val raw: Int, val wireName: String, val unit: String) {
    DIGITAL_INPUT(0, "Digital Input", ""),
    DIGITAL_OUTPUT(1, "Digital Output", ""),
    ANALOG_INPUT(2, "Analog Input", ""),
    ANALOG_OUTPUT(3, "Analog Output", ""),
    GENERIC_SENSOR(100, "Sensor", ""),
    ILLUMINANCE(101, "Illuminance", "lux"),
    PRESENCE(102, "Presence", ""),
    TEMPERATURE(103, "Temperature", "°C"),
    HUMIDITY(104, "Humidity", "%"),
    ACCELEROMETER(113, "Accelerometer", ""),
    BAROMETER(115, "Pressure", "hPa"),
    VOLTAGE(116, "Voltage", "V"),
    CURRENT(117, "Current", "A"),
    FREQUENCY(118, "Frequency", "Hz"),
    PERCENTAGE(120, "Percentage", "%"),
    ALTITUDE(121, "Altitude", "m"),
    LOAD(122, "Load", "kg"),
    CONCENTRATION(125, "Concentration", "ppm"),
    POWER(128, "Power", "W"),
    DISTANCE(130, "Distance", "m"),
    ENERGY(131, "Energy", "kWh"),
    DIRECTION(132, "Direction", "°"),
    UNIX_TIME(133, "Time", ""),
    GYROMETER(134, "Gyrometer", ""),
    COLOUR(135, "Colour", ""),
    GPS(136, "GPS", ""),
    SWITCH_VALUE(142, "Switch", ""),
    ;

    /** Chart ordering: voltage first, everything else after (ties broken by title at render). */
    val chartSortPriority: Int
        get() = if (this == VOLTAGE) 0 else 1

    /**
     * The SI value converted to [system]'s preferred unit. Only temperature, pressure, altitude, and
     * distance convert; every other type (and metric) returns the input unchanged. Store SI, convert on
     * display.
     */
    fun convertedValue(siValue: Double, system: MeasurementSystem): Double {
        if (system == MeasurementSystem.METRIC) return siValue
        return when (this) {
            TEMPERATURE -> siValue * CELSIUS_TO_FAHRENHEIT_SCALE + CELSIUS_TO_FAHRENHEIT_OFFSET
            BAROMETER -> siValue * HECTOPASCAL_PASCALS / INCH_OF_MERCURY_PASCALS
            ALTITUDE, DISTANCE -> siValue / FOOT_METERS
            else -> siValue
        }
    }

    /** The unit symbol shown for [system] (e.g. `°F`/`inHg`/`ft` on imperial), else the SI [unit]. */
    fun localizedUnitSymbol(
        system: MeasurementSystem,
        lengthSymbols: LocalizedLengthSymbols = LocalizedLengthSymbols.LATIN,
    ): String {
        if (system == MeasurementSystem.METRIC || !isLocaleSensitive) {
            return when (this) {
                ALTITUDE, DISTANCE -> lengthSymbols.meter
                else -> unit
            }
        }
        return when (this) {
            TEMPERATURE -> "°F"
            BAROMETER -> "inHg"
            ALTITUDE, DISTANCE -> lengthSymbols.foot
            else -> unit
        }
    }

    /** Fraction digits for the converted value, matching the Swift `convertedFractionLength`. */
    fun convertedFractionLength(system: MeasurementSystem): Int {
        if (system == MeasurementSystem.IMPERIAL && isLocaleSensitive) {
            return when (this) {
                TEMPERATURE -> 1
                BAROMETER -> 2
                ALTITUDE -> 0
                DISTANCE -> 1
                else -> 1
            }
        }
        return when (this) {
            TEMPERATURE, BAROMETER, ALTITUDE -> 1
            DISTANCE -> DISTANCE_FRACTION_DIGITS
            else -> 1
        }
    }

    private val isLocaleSensitive: Boolean
        get() = this == TEMPERATURE || this == BAROMETER || this == ALTITUDE || this == DISTANCE

    companion object {
        // Foundation UnitConverter coefficients, reproduced so conversions match the Swift output exactly.
        private const val DISTANCE_FRACTION_DIGITS = 3
        private const val CELSIUS_TO_FAHRENHEIT_SCALE = 9.0 / 5.0
        private const val CELSIUS_TO_FAHRENHEIT_OFFSET = 32.0
        private const val FOOT_METERS = 0.3048
        private const val HECTOPASCAL_PASCALS = 100.0
        private const val INCH_OF_MERCURY_PASCALS = 3386.39

        fun fromRaw(raw: Int): LPPSensorType? = entries.firstOrNull { it.raw == raw }

        /** Reverse lookup by the persisted English [wireName], mirroring `LPPSensorType(name:)`. */
        fun fromName(wireName: String): LPPSensorType? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * A decoded LPP value, mirroring the Swift `LPPValue`. The bridge produces these; the consumer keeps the
 * union so channel-0 filtering and numeric-only snapshot extraction can discriminate cases.
 *
 * [Integer] and [Timestamp] are 64-bit because the LPP fields behind them are unsigned 32-bit: a signed
 * 32-bit reader turns a generic sensor reading at or above 2^31 negative, and any time after 19 January
 * 2038 into 1901.
 */
@Serializable(with = LPPValueSerializer::class)
sealed interface LPPValue {
    @Serializable
    data class Digital(val value: Boolean) : LPPValue

    @Serializable
    data class Integer(val value: Long) : LPPValue

    @Serializable
    data class FloatValue(val value: Double) : LPPValue

    @Serializable
    data class Vector3(val x: Double, val y: Double, val z: Double) : LPPValue

    @Serializable
    data class Gps(val latitude: Double, val longitude: Double, val altitude: Double) : LPPValue

    @Serializable
    data class Rgb(val red: Int, val green: Int, val blue: Int) : LPPValue

    @Serializable
    data class Timestamp(@SerialName("value") val epochSeconds: Long) : LPPValue
}

/**
 * A single decoded LPP data point, mirroring the Swift `LPPDataPoint`. [channel] is the sensor instance
 * (`TELEM_CHANNEL_SELF = 1` is the device's own battery/self reading; channel 0 is filtered by the
 * consumer). The bridge forwards these already decoded: MeshCore's `LPPDecoder` is the product's only
 * Cayenne LPP parser, and nothing on this side of the boundary reads telemetry bytes.
 */
@Serializable
data class LPPDataPoint(val channel: Int, @SerialName("typeRawValue") val type: LPPSensorType, val value: LPPValue)

/**
 * Reads [LPPSensorType] from the verbatim LPP type byte the bridge emits as `typeRawValue`. The Swift
 * decoder stops its frame walk at the first unrecognized type, so an unknown raw here means the two
 * sides disagree about the type table and is a decode failure, not a point to skip.
 */
internal object LPPSensorTypeRawSerializer : KSerializer<LPPSensorType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("one.meshcore.shared.remotenode.LPPSensorType", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): LPPSensorType {
        val raw = decoder.decodeInt()
        return LPPSensorType.fromRaw(raw) ?: throw SerializationException("unknown LPP sensor type: $raw")
    }

    override fun serialize(encoder: Encoder, value: LPPSensorType) = encoder.encodeInt(value.raw)
}

/** Selects the [LPPValue] case from the boundary's `kind` discriminator (see `ENCODING.md`). */
internal object LPPValueSerializer : JsonContentPolymorphicSerializer<LPPValue>(LPPValue::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<LPPValue> =
        when (val kind = element.jsonObject["kind"]?.jsonPrimitive?.content) {
            "digital" -> LPPValue.Digital.serializer()
            "integer" -> LPPValue.Integer.serializer()
            "float" -> LPPValue.FloatValue.serializer()
            "vector3" -> LPPValue.Vector3.serializer()
            "gps" -> LPPValue.Gps.serializer()
            "rgb" -> LPPValue.Rgb.serializer()
            "timestamp" -> LPPValue.Timestamp.serializer()
            else -> throw SerializationException("unknown LPP value kind: $kind")
        }
}
