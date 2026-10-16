package one.meshcore.shared.settings

import one.meshcore.shared.chat.pathdetail.PathEncoding
import kotlin.math.roundToLong

/**
 * The firmware-accepted ranges and encodings the node-config planner validates against
 * §15 master table (the Swift `PacketBuilder`/`PathEncoding`/`ProtocolLimits` constants). Frozen wire values.
 */
object NodeConfigLimits {
    const val PRIVATE_KEY_SIZE: Int = 64
    const val PUBLIC_KEY_SIZE: Int = 32
    const val CHANNEL_SECRET_SIZE: Int = 16
    const val MAX_USABLE_NAME_BYTES: Int = 31

    val FREQUENCY_RANGE_KHZ: UIntRange = 150_000u..2_500_000u
    val BANDWIDTH_RANGE_HZ: UIntRange = 7_000u..500_000u
    val SPREADING_FACTOR_RANGE: UIntRange = 5u..12u
    val CODING_RATE_RANGE: UIntRange = 5u..8u
    const val TX_POWER_FLOOR: Byte = -9

    const val LATITUDE_LIMIT: Double = 90.0
    const val LONGITUDE_LIMIT: Double = 180.0
    const val COORDINATE_SCALE: Double = 1_000_000.0

    const val FLOOD_PATH_SENTINEL: UByte = 0xFFu

    const val MAX_PATH_HASH_MODE: Int = PathEncoding.MAX_PATH_HASH_MODE
    const val MAX_HOP_COUNT: Int = PathEncoding.MAX_HOP_COUNT
    const val MAX_PATH_BYTES: Int = PathEncoding.MAX_PATH_BYTES

    /**
     * The path-length byte for the planner's contact records, through the one path codec so the hop count
     * is clamped: an overlong count would otherwise overflow the low 6 bits into the hash-size mode and
     * describe a route width the path bytes do not have.
     */
    fun encodePathLen(hashSize: Int, hopCount: Int): UByte =
        PathEncoding.encodePathLen(hashSize = hashSize, hopCount = hopCount).toUByte()

    /** The integer a coordinate is stored as on the device, so a sub-resolution difference compares equal. */
    fun scaledCoordinate(value: Double): Long = (value * COORDINATE_SCALE).roundToLong()
}
