package one.meshcore.shared.chat

private const val THIRD_BYTE_OFFSET = 2
private const val FOURTH_BYTE_OFFSET = 3

private const val PNG_SIG_0: Byte = 0x89.toByte()
private const val PNG_IHDR_TYPE_OFFSET = 12
private const val PNG_WIDTH_OFFSET = 16
private const val PNG_HEIGHT_OFFSET = 20
private const val PNG_IHDR_END = 24

private const val GIF_WIDTH_OFFSET = 6
private const val GIF_HEIGHT_OFFSET = 8
private const val GIF_HEADER_END = 10

internal fun isPng(bytes: ByteArray): Boolean = bytes.size >= PNG_IHDR_END &&
    bytes[0] == PNG_SIG_0 &&
    bytes[1] == 'P'.code.toByte() &&
    bytes[THIRD_BYTE_OFFSET] == 'N'.code.toByte() &&
    bytes[FOURTH_BYTE_OFFSET] == 'G'.code.toByte()

internal fun readPng(bytes: ByteArray): ImagePixelSize? {
    if (!imageAsciiEquals(bytes, PNG_IHDR_TYPE_OFFSET, "IHDR")) return null
    return ImagePixelSize(
        width = imageU32be(bytes, PNG_WIDTH_OFFSET),
        height = imageU32be(bytes, PNG_HEIGHT_OFFSET),
    )
}

internal fun isGif(bytes: ByteArray): Boolean = bytes.size >= GIF_HEADER_END &&
    bytes[0] == 'G'.code.toByte() &&
    bytes[1] == 'I'.code.toByte() &&
    bytes[THIRD_BYTE_OFFSET] == 'F'.code.toByte()

internal fun readGif(bytes: ByteArray): ImagePixelSize = ImagePixelSize(
    width = imageU16le(bytes, GIF_WIDTH_OFFSET),
    height = imageU16le(bytes, GIF_HEIGHT_OFFSET),
)
