package one.meshcore.shared.chat

private const val BYTE_MASK = 0xFF
private const val JPEG_SCAN_START = 2
private const val JPEG_MARKER_LENGTH = 2
private const val JPEG_MARKER: Byte = 0xFF.toByte()
private const val JPEG_SOI: Byte = 0xD8.toByte()
private const val JPEG_EOI = 0xD9
private const val JPEG_TEM = 0x01
private const val JPEG_RST_START = 0xD0
private const val JPEG_RST_END = 0xD7
private const val JPEG_SOF_START = 0xC0
private const val JPEG_SOF_END = 0xCF
private const val JPEG_DHT = 0xC4
private const val JPEG_JPG = 0xC8
private const val JPEG_DAC = 0xCC
private const val JPEG_MIN = 4
private const val JPEG_MARKER_OVERHEAD = 3
private const val JPEG_LENGTH_MIN = 2
private const val JPEG_SOF_HEIGHT = 3
private const val JPEG_SOF_WIDTH = 5
private const val JPEG_SOF_SIZE_END = 7

internal fun isJpeg(bytes: ByteArray): Boolean = bytes.size >= JPEG_MIN &&
    bytes[0] == JPEG_MARKER &&
    bytes[1] == JPEG_SOI

internal fun readJpeg(bytes: ByteArray): ImagePixelSize? {
    var index = JPEG_SCAN_START
    while (index + JPEG_MARKER_OVERHEAD < bytes.size) {
        when (val step = jpegStep(bytes, index)) {
            is JpegScan.Done -> return step.size
            is JpegScan.Advance -> index = step.index
        }
    }
    return null
}

private fun jpegStep(bytes: ByteArray, index: Int): JpegScan {
    if (bytes[index] != JPEG_MARKER) return JpegScan.Done(null)
    val marker = bytes[index + 1].toInt() and BYTE_MASK
    val afterMarker = index + JPEG_MARKER_LENGTH
    if (isStandaloneJpegMarker(marker)) return JpegScan.Advance(afterMarker)
    return sizedSegment(bytes, afterMarker, marker)
}

private fun sizedSegment(bytes: ByteArray, index: Int, marker: Int): JpegScan {
    val length = segmentLength(bytes, index) ?: return JpegScan.Done(null)
    if (isJpegSizeMarker(marker)) return JpegScan.Done(sofSize(bytes, index))
    return JpegScan.Advance(index + length)
}

private fun segmentLength(bytes: ByteArray, index: Int): Int? {
    if (index + 1 >= bytes.size) return null
    val length = imageU16be(bytes, index)
    if (length < JPEG_LENGTH_MIN) return null
    return length
}

private fun sofSize(bytes: ByteArray, index: Int): ImagePixelSize? {
    if (index + JPEG_SOF_SIZE_END > bytes.size) return null
    return ImagePixelSize(
        width = imageU16be(bytes, index + JPEG_SOF_WIDTH),
        height = imageU16be(bytes, index + JPEG_SOF_HEIGHT),
    )
}

private sealed interface JpegScan {
    class Done(val size: ImagePixelSize?) : JpegScan

    class Advance(val index: Int) : JpegScan
}

private fun isStandaloneJpegMarker(marker: Int): Boolean = marker == JPEG_SOI.toInt() and BYTE_MASK ||
    marker == JPEG_EOI ||
    marker == JPEG_TEM ||
    marker in JPEG_RST_START..JPEG_RST_END

private fun isJpegSizeMarker(marker: Int): Boolean = marker in JPEG_SOF_START..JPEG_SOF_END &&
    marker != JPEG_DHT &&
    marker != JPEG_JPG &&
    marker != JPEG_DAC
