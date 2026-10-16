package one.meshcore.shared.chat.ui

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Guards the link-preview byte decoder backing [PreviewImageResolver]: a decodable PNG yields a bitmap so a
 * stored hero renders instead of hanging on the loading card, and empty/garbage bytes fail closed to null so
 * the fragment falls back to its placeholder rather than crashing.
 */
class PreviewImageCodecTest {
    @Test
    fun emptyBytesDecodeToNull() {
        assertNull(decodePreviewImageBytes(ByteArray(0)))
    }

    @Test
    fun garbageBytesDecodeToNull() {
        assertNull(decodePreviewImageBytes(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun validPngDecodesToABitmap() {
        val bytes = Base64.decode(ONE_PIXEL_PNG)
        assertNotNull(decodePreviewImageBytes(bytes))
    }

    private companion object {
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
    }
}
