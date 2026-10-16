package one.meshcore.shared.model

import kotlin.math.min

/** The leading [count] bytes, or the whole array when it is shorter. Never pads. */
internal fun ByteArray.prefixBytes(count: Int): ByteArray = copyOfRange(0, min(count, size))

/** The leading [byteCount] bytes as uppercase hex, or the whole array when it is shorter. */
internal fun ByteArray.prefixHex(byteCount: Int): String = prefixBytes(byteCount).toUppercaseHex()

/** Uppercase hex, matching the iOS `Data.uppercaseHexString()` used for channel identity. */
fun ByteArray.toUppercaseHex(): String = hexString(UPPERCASE_HEX_DIGITS)

internal fun ByteArray.toLowercaseHex(): String = hexString(LOWERCASE_HEX_DIGITS)

/** Whether the leading bytes equal [prefix]. An empty prefix matches. */
internal fun ByteArray.startsWithPrefix(prefix: ByteArray): Boolean {
    if (prefix.size > size) return false
    for (index in prefix.indices) if (this[index] != prefix[index]) return false
    return true
}

private fun ByteArray.hexString(digits: String): String = buildString {
    for (byte in this@hexString) {
        val unsigned = byte.toInt() and BYTE_MASK
        append(digits[unsigned ushr HIGH_NIBBLE_SHIFT])
        append(digits[unsigned and LOW_NIBBLE_MASK])
    }
}

/** Decodes an even-length hex string, or `null` for odd length or any non-hex character. */
internal fun decodeHex(hex: String): ByteArray? {
    if (hex.length % HEX_CHARS_PER_BYTE != 0) return null
    val out = ByteArray(hex.length / HEX_CHARS_PER_BYTE)
    for (i in out.indices) {
        val hi = hex[i * HEX_CHARS_PER_BYTE].digitToIntOrNull(HEX_RADIX)
        val lo = hex[i * HEX_CHARS_PER_BYTE + 1].digitToIntOrNull(HEX_RADIX)
        if (hi == null || lo == null) return null
        out[i] = ((hi shl HIGH_NIBBLE_SHIFT) or lo).toByte()
    }
    return out
}

private const val HEX_RADIX = 16
private const val BYTE_MASK = 0xFF
private const val HIGH_NIBBLE_SHIFT = 4
private const val LOW_NIBBLE_MASK = 0x0F
private const val HEX_CHARS_PER_BYTE = 2
private const val UPPERCASE_HEX_DIGITS = "0123456789ABCDEF"
private const val LOWERCASE_HEX_DIGITS = "0123456789abcdef"
