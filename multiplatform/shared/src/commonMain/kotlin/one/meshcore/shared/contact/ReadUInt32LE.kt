package one.meshcore.shared.contact

/** Four bytes at [offset], little-endian, as an unsigned integer widened to [Long]. */
internal fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
    val b0 = bytes[offset].toLong() and BYTE_MASK
    val b1 = bytes[offset + BYTE_1].toLong() and BYTE_MASK
    val b2 = bytes[offset + BYTE_2].toLong() and BYTE_MASK
    val b3 = bytes[offset + BYTE_3].toLong() and BYTE_MASK
    return b0 or (b1 shl BITS_PER_BYTE) or (b2 shl BITS_PER_WORD) or (b3 shl BITS_PER_THREE_BYTES)
}

private const val BYTE_MASK = 0xFFL
private const val BYTE_1 = 1
private const val BYTE_2 = 2
private const val BYTE_3 = 3
private const val BITS_PER_BYTE = 8
private const val BITS_PER_WORD = 16
private const val BITS_PER_THREE_BYTES = 24
