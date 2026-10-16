package one.meshcore.shared.chat

/** Writes [value] as a big-endian 32-bit integer. PNG and WebP fixtures both use this layout. */
internal fun putU32be(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = ((value ushr 24) and 0xFF).toByte()
    bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    bytes[offset + 3] = (value and 0xFF).toByte()
}
