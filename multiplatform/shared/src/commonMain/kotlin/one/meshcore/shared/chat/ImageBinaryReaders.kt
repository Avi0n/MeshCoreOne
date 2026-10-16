package one.meshcore.shared.chat

private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8
private const val TWO_BYTE_SHIFT = 16
private const val THREE_BYTE_SHIFT = 24
private const val THIRD_BYTE_OFFSET = 2
private const val FOURTH_BYTE_OFFSET = 3

internal fun imageAsciiEquals(bytes: ByteArray, offset: Int, expected: String): Boolean {
    if (offset + expected.length > bytes.size) return false
    return expected.indices.all { bytes[offset + it] == expected[it].code.toByte() }
}

internal fun imageU16be(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (bytes[offset + 1].toInt() and BYTE_MASK)

internal fun imageU16le(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and BYTE_MASK) or
    ((bytes[offset + 1].toInt() and BYTE_MASK) shl BITS_PER_BYTE)

internal fun imageU24le(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and BYTE_MASK) or
    ((bytes[offset + 1].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or
    ((bytes[offset + THIRD_BYTE_OFFSET].toInt() and BYTE_MASK) shl TWO_BYTE_SHIFT)

internal fun imageU32be(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and BYTE_MASK) shl THREE_BYTE_SHIFT) or
        ((bytes[offset + 1].toInt() and BYTE_MASK) shl TWO_BYTE_SHIFT) or
        ((bytes[offset + THIRD_BYTE_OFFSET].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or
        (bytes[offset + FOURTH_BYTE_OFFSET].toInt() and BYTE_MASK)

internal fun imageU32le(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and BYTE_MASK) or
    ((bytes[offset + 1].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or
    ((bytes[offset + THIRD_BYTE_OFFSET].toInt() and BYTE_MASK) shl TWO_BYTE_SHIFT) or
    ((bytes[offset + FOURTH_BYTE_OFFSET].toInt() and BYTE_MASK) shl THREE_BYTE_SHIFT)
