package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageDimensionReaderTest {
    @Test
    fun readsPngIhdr() {
        val bytes = ByteArray(24)
        bytes[0] = 0x89.toByte()
        bytes[1] = 'P'.code.toByte()
        bytes[2] = 'N'.code.toByte()
        bytes[3] = 'G'.code.toByte()
        bytes[12] = 'I'.code.toByte()
        bytes[13] = 'H'.code.toByte()
        bytes[14] = 'D'.code.toByte()
        bytes[15] = 'R'.code.toByte()
        putU32be(bytes, 16, 800)
        putU32be(bytes, 20, 400)
        assertEquals(ImagePixelSize(800, 400), ImageDimensionReader.read(bytes))
    }

    @Test
    fun readsGifLogicalScreen() {
        val bytes = ByteArray(10)
        bytes[0] = 'G'.code.toByte()
        bytes[1] = 'I'.code.toByte()
        bytes[2] = 'F'.code.toByte()
        bytes[3] = '8'.code.toByte()
        bytes[4] = '9'.code.toByte()
        bytes[5] = 'a'.code.toByte()
        bytes[6] = 0x20
        bytes[7] = 0x03
        bytes[8] = 0x90.toByte()
        bytes[9] = 0x01
        assertEquals(ImagePixelSize(800, 400), ImageDimensionReader.read(bytes))
    }

    @Test
    fun readsJpegSof0() {
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xC0.toByte(),
            0x00, 0x0B,
            0x08,
            0x01, 0x90.toByte(),
            0x03, 0x20,
        )
        assertEquals(ImagePixelSize(800, 400), ImageDimensionReader.read(bytes))
    }

    @Test
    fun readsJpegSizeAfterSkippedSegments() {
        val sof = byteArrayOf(
            0xFF.toByte(), 0xC0.toByte(),
            0x00, 0x0B,
            0x08,
            0x01, 0x90.toByte(),
            0x03, 0x20,
        )
        val afterDht = byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0xFF.toByte(),
            0xC4.toByte(),
            0x00,
            0x02,
        ) + sof
        val afterRst = byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0xFF.toByte(),
            0xD0.toByte(),
        ) + sof
        assertEquals(ImagePixelSize(800, 400), ImageDimensionReader.read(afterDht))
        assertEquals(ImagePixelSize(800, 400), ImageDimensionReader.read(afterRst))
    }

    @Test
    fun rejectsTruncatedOrCorruptJpeg() {
        assertNull(ImageDimensionReader.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x00)))
        assertNull(
            ImageDimensionReader.read(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC4.toByte(), 0x00, 0x00),
            ),
        )
        assertNull(
            ImageDimensionReader.read(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08),
            ),
        )
    }

    @Test
    fun readsWebpVp8x() {
        val bytes = ByteArray(30)
        writeAscii(bytes, 0, "RIFF")
        writeAscii(bytes, 8, "WEBP")
        writeAscii(bytes, 12, "VP8X")
        bytes[24] = 0x1F
        bytes[25] = 0x03
        bytes[26] = 0x00
        bytes[27] = 0x8F.toByte()
        bytes[28] = 0x01
        bytes[29] = 0x00
        assertEquals(ImagePixelSize(800, 400), ImageDimensionReader.read(bytes))
    }

    @Test
    fun rejectsGarbageAndEmpty() {
        assertNull(ImageDimensionReader.read(byteArrayOf(1, 2, 3)))
        assertNull(ImageDimensionReader.read(byteArrayOf()))
    }

    private fun writeAscii(bytes: ByteArray, offset: Int, value: String) {
        value.forEachIndexed { index, char -> bytes[offset + index] = char.code.toByte() }
    }
}
