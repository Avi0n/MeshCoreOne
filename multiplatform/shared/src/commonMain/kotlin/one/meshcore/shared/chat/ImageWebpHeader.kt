package one.meshcore.shared.chat

private const val WEBP_BRAND_OFFSET = 8
private const val WEBP_TYPE_END = 12
private const val WEBP_CHUNK_TYPE_OFFSET = 12
private const val WEBP_CHUNK_PAYLOAD = 20
private const val WEBP_VP8X_WIDTH = 24
private const val WEBP_VP8X_HEIGHT = 27
private const val WEBP_VP8X_END = 30
private const val WEBP_VP8L_SIG = 20
private const val WEBP_VP8L_BITS = 21
private const val WEBP_VP8L_END = 25
private const val WEBP_VP8L_SIGNATURE: Byte = 0x2F
private const val WEBP_VP8_START_CODE = 23
private const val WEBP_VP8_WIDTH = 26
private const val WEBP_VP8_HEIGHT = 28
private const val WEBP_VP8_END = 30
private const val WEBP_VP8_CODE_0: Byte = 0x9D.toByte()
private const val WEBP_VP8_CODE_1: Byte = 0x01
private const val WEBP_VP8_CODE_2: Byte = 0x2A
private const val WEBP_14BIT = 14
private const val WEBP_14BIT_MASK = 0x3FFF

internal fun isWebp(bytes: ByteArray): Boolean = bytes.size >= WEBP_TYPE_END &&
    imageAsciiEquals(bytes, 0, "RIFF") &&
    imageAsciiEquals(bytes, WEBP_BRAND_OFFSET, "WEBP")

internal fun readWebp(bytes: ByteArray): ImagePixelSize? {
    if (bytes.size < WEBP_CHUNK_PAYLOAD) return null
    return when {
        imageAsciiEquals(bytes, WEBP_CHUNK_TYPE_OFFSET, "VP8X") -> readWebpVp8x(bytes)
        imageAsciiEquals(bytes, WEBP_CHUNK_TYPE_OFFSET, "VP8L") -> readWebpVp8l(bytes)
        imageAsciiEquals(bytes, WEBP_CHUNK_TYPE_OFFSET, "VP8 ") -> readWebpVp8(bytes)
        else -> null
    }
}

private fun readWebpVp8x(bytes: ByteArray): ImagePixelSize? {
    if (bytes.size < WEBP_VP8X_END) return null
    return ImagePixelSize(
        width = imageU24le(bytes, WEBP_VP8X_WIDTH) + 1,
        height = imageU24le(bytes, WEBP_VP8X_HEIGHT) + 1,
    )
}

private fun readWebpVp8l(bytes: ByteArray): ImagePixelSize? {
    if (bytes.size < WEBP_VP8L_END) return null
    if (bytes[WEBP_VP8L_SIG] != WEBP_VP8L_SIGNATURE) return null
    val bits = imageU32le(bytes, WEBP_VP8L_BITS)
    return ImagePixelSize(
        width = (bits and WEBP_14BIT_MASK) + 1,
        height = ((bits shr WEBP_14BIT) and WEBP_14BIT_MASK) + 1,
    )
}

private fun readWebpVp8(bytes: ByteArray): ImagePixelSize? {
    if (bytes.size < WEBP_VP8_END) return null
    if (bytes[WEBP_VP8_START_CODE] != WEBP_VP8_CODE_0 ||
        bytes[WEBP_VP8_START_CODE + 1] != WEBP_VP8_CODE_1 ||
        bytes[WEBP_VP8_START_CODE + 2] != WEBP_VP8_CODE_2
    ) {
        return null
    }
    return ImagePixelSize(
        width = imageU16le(bytes, WEBP_VP8_WIDTH) and WEBP_14BIT_MASK,
        height = imageU16le(bytes, WEBP_VP8_HEIGHT) and WEBP_14BIT_MASK,
    )
}
