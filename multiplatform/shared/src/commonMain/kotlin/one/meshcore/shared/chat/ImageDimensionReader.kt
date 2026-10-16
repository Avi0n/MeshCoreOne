package one.meshcore.shared.chat

/**
 * Pixel size of a decoded image header. Used to reserve bubble height before the full decode lands.
 */
data class ImagePixelSize(val width: Int, val height: Int)

/**
 * Reads width and height from PNG, JPEG, GIF, or WebP bytes without a platform decoder. A receive-time
 * probe uses this so the timeline can reserve the final aspect before the bubble is appended.
 */
object ImageDimensionReader {
    /**
     * True when [bytes] begin with the GIF magic (`GIF8`), matching iOS `ImageURLDetector.isGIFData`.
     * Used to pick the animated inline fragment after a gated fetch.
     */
    fun isGifData(bytes: ByteArray): Boolean = bytes.size >= GIF_MAGIC_END &&
        bytes[0] == 'G'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[THIRD_BYTE_OFFSET] == 'F'.code.toByte() &&
        bytes[FOURTH_BYTE_OFFSET] == '8'.code.toByte()

    fun read(bytes: ByteArray): ImagePixelSize? {
        val size = when {
            isPng(bytes) -> readPng(bytes)
            isGif(bytes) -> readGif(bytes)
            isJpeg(bytes) -> readJpeg(bytes)
            isWebp(bytes) -> readWebp(bytes)
            else -> null
        }
        return size?.takeIf { it.width > 0 && it.height > 0 }
    }

    private const val GIF_MAGIC_END = 4
    private const val THIRD_BYTE_OFFSET = 2
    private const val FOURTH_BYTE_OFFSET = 3
}
