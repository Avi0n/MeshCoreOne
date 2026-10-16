package one.meshcore.shared.chat.linkpreview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewFitEncodedTest {
    @Test
    fun bytesThatFitAtTheFirstQualityAreReturned() {
        var scaled: Pair<Int, Int>? = null
        val result = PreviewImageProcessor.fitEncoded(
            width = 100,
            height = 50,
            encodeAtQuality = { quality -> byteArrayOf(quality.toByte()) },
            encodeScaled = { width, height ->
                scaled = width to height
                byteArrayOf()
            },
        )
        assertEquals(listOf(PreviewImageProcessor.INITIAL_JPEG_QUALITY.toByte()), result.toList())
        assertNull(scaled)
    }

    @Test
    fun qualityDropsByOneStepUntilTheBytesFit() {
        val calls = mutableListOf<Int>()
        PreviewImageProcessor.fitEncoded(
            width = 100,
            height = 50,
            encodeAtQuality = { quality ->
                calls += quality
                if (quality <= QUALITY_THAT_FITS) byteArrayOf(1) else oversized()
            },
            encodeScaled = { _, _ -> byteArrayOf() },
        )
        assertEquals(
            listOf(
                PreviewImageProcessor.INITIAL_JPEG_QUALITY,
                PreviewImageProcessor.INITIAL_JPEG_QUALITY - PreviewImageProcessor.JPEG_QUALITY_STEP,
                QUALITY_THAT_FITS,
            ),
            calls,
        )
    }

    @Test
    fun anOversizedFloorScalesOnceFromTheLastEncodedSize() {
        var scaled: Pair<Int, Int>? = null
        PreviewImageProcessor.fitEncoded(
            width = SOURCE_WIDTH,
            height = SOURCE_HEIGHT,
            encodeAtQuality = { oversized() },
            encodeScaled = { width, height ->
                scaled = width to height
                byteArrayOf(1)
            },
        )
        assertEquals(SOURCE_WIDTH / 2 to SOURCE_HEIGHT / 2, scaled)
    }

    private fun oversized(): ByteArray = ByteArray(PreviewImageProcessor.MAX_ENCODED_BYTES * OVERSIZE_FACTOR)

    private companion object {
        const val QUALITY_THAT_FITS = 70
        const val SOURCE_WIDTH = 400
        const val SOURCE_HEIGHT = 200
        const val OVERSIZE_FACTOR = 4
    }
}
