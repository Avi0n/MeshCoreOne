package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.map.SnrQuality
import one.meshcore.shared.map.snrQuality
import one.meshcore.shared.model.toUppercaseHex
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Pure derived accessors on [MessageRepeatDto] that the repeat-details row consumes, ported from the Swift
 * `MessageRepeatDTO` computed properties. A repeat's `pathNodes` are the repeater key prefixes and
 * `pathLength` is the encoded hash-mode/hop byte, so the hash size, last-repeater hash, and hop count are
 * derived the same way as a message's path via [PathEncoding].
 */

/** Bytes per hop hash (1, 2, or 3) from the repeat's encoded [MessageRepeatDto.pathLength], defaulting to 1. */
val MessageRepeatDto.hashSize: Int
    get() = PathEncoding.decodePathLen(pathLength)?.hashSize ?: 1

/**
 * The last repeater's key-prefix bytes — the node we heard the echo from — or null when the path is empty.
 * When the path holds fewer bytes than one hash, the whole buffer is returned.
 */
val MessageRepeatDto.repeaterHash: ByteArray?
    get() {
        if (pathNodes.isEmpty()) return null
        val size = hashSize
        if (pathNodes.size < size) return pathNodes
        return pathNodes.copyOfRange(pathNodes.size - size, pathNodes.size)
    }

/** Number of hops in the repeat's path (`pathNodes.size / hashSize`), or the raw byte count when hashSize is 0. */
val MessageRepeatDto.hopCount: Int
    get() {
        val size = hashSize
        return if (size > 0) pathNodes.size / size else pathNodes.size
    }

/** The last repeater's hash as uppercase hex, or "00" when the path is empty. Mirrors the Swift default. */
val MessageRepeatDto.repeaterHashFormatted: String
    get() = repeaterHash?.toUppercaseHex() ?: MISSING_REPEATER_HASH

/** Each repeater hop's uppercase hex, chunked by hash size. */
val MessageRepeatDto.pathNodesHex: List<String>
    get() = PathEncoding.pathHops(pathNodes, hashSize).map { it.hex }

/** Signal-quality bucket for the repeat's SNR. */
val MessageRepeatDto.snrQuality: SnrQuality
    get() = snrQuality(snr)

/** SNR mapped to a 0-1 bar level for the signal-bar glyph. */
val MessageRepeatDto.snrLevel: Double
    get() = snrQuality.barLevel

/** RSSI as "N {unit}", or the missing-value placeholder when absent. */
fun MessageRepeatDto.rssiFormatted(unit: String): String = rssi?.let { "$it $unit" } ?: MISSING_VALUE_PLACEHOLDER

/** SNR to one fraction digit as "N.N {unit}", or the missing-value placeholder when absent. */
fun MessageRepeatDto.snrFormatted(unit: String): String =
    snr?.let { "${formatOneFractionDigit(it)} $unit" } ?: MISSING_VALUE_PLACEHOLDER

/** Uppercase-hex default for a repeat with no path bytes, mirroring the Swift `repeaterHashFormatted`. */
private const val MISSING_REPEATER_HASH = "00"

/** Display sentinel for a missing SNR/RSSI reading, mirroring the iOS `MessageRepeatDTO` placeholder. */
private const val MISSING_VALUE_PLACEHOLDER = "—"

private const val ONE_DECIMAL_SCALE = 10

/**
 * Formats [value] to exactly one fraction digit, locale-independently, so the numeric part of the
 * SNR string never picks up a comma-decimal locale. Preserves the sign for values that round to a
 * magnitude below one.
 */
private fun formatOneFractionDigit(value: Double): String {
    val scaled = (value * ONE_DECIMAL_SCALE).roundToLong()
    val whole = scaled / ONE_DECIMAL_SCALE
    val fraction = abs(scaled % ONE_DECIMAL_SCALE)
    val sign = if (scaled < 0 && whole == 0L) "-" else ""
    return "$sign$whole.$fraction"
}
