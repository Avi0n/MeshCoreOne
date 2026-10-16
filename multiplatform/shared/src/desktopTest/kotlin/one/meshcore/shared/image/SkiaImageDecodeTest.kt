package one.meshcore.shared.image

import one.meshcore.shared.avatar.decodeAvatarImage
import one.meshcore.shared.chat.ui.decodePreviewImageBytes
import one.meshcore.shared.map.decodeMapSnapshotImage
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkiaImageDecodeTest {
    @Test
    fun emptyAndGarbageBytesDecodeToNull() {
        assertNull(decodeSkiaImageBitmap(byteArrayOf()))
        assertNull(decodePreviewImageBytes(byteArrayOf(1, 2, 3)))
        assertNull(decodeMapSnapshotImage(byteArrayOf(1, 2, 3)))
        assertNull(decodeAvatarImage(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun aJpegDecodesToTheSameSizeOnEveryDesktopPath() {
        val bytes = encodeOpaqueJpeg(BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB), quality = 90)
        val decoded = listOf(
            decodeSkiaImageBitmap(bytes),
            decodePreviewImageBytes(bytes),
            decodeMapSnapshotImage(bytes),
            decodeAvatarImage(bytes),
        )
        assertTrue(decoded.all { it != null && it.width == SIDE && it.height == SIDE })
        assertEquals(SIDE, decoded.first()?.width)
    }
}

private const val SIDE = 8
