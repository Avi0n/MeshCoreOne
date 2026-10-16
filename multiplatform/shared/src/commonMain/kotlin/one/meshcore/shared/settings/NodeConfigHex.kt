package one.meshcore.shared.settings

private const val NIBBLE_SHIFT = 4
private const val HEX_LETTER_OFFSET = 10

/** Parses contiguous even-length hex to bytes, or null for odd-length or any non-hex char (empty → empty). */
internal fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    var i = 0
    while (i < hex.length) {
        val hi = hexValue(hex[i])
        val lo = hexValue(hex[i + 1])
        if (hi < 0 || lo < 0) return null
        out[i / 2] = ((hi shl NIBBLE_SHIFT) or lo).toByte()
        i += 2
    }
    return out
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + HEX_LETTER_OFFSET
    in 'A'..'F' -> c - 'A' + HEX_LETTER_OFFSET
    else -> -1
}
