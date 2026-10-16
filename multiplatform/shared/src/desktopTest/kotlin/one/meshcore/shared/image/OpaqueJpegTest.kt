package one.meshcore.shared.image

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpaqueJpegTest {
    @Test
    fun anRgbImageRoundTripsSizeAndASolidColor() {
        val image = solid(BufferedImage.TYPE_INT_RGB, BLUE)
        val bytes = encodeOpaqueJpeg(image, quality = 95)
        assertEquals(JPEG_MARKER, bytes[0].toInt() and CHANNEL_MASK)
        assertEquals(JPEG_START_OF_IMAGE, bytes[1].toInt() and CHANNEL_MASK)
        val decoded = decode(bytes)
        assertEquals(SIDE, decoded.width)
        assertEquals(SIDE, decoded.height)
        val pixel = decoded.getRGB(CENTER, CENTER)
        assertTrue((pixel and CHANNEL_MASK) > SATURATED_CHANNEL_FLOOR)
        assertTrue(((pixel shr RED_SHIFT) and CHANNEL_MASK) < DIM_CHANNEL_CEILING)
    }

    @Test
    fun anArgbImageIsFlattenedBeforeEncoding() {
        val decoded = decode(encodeOpaqueJpeg(solid(BufferedImage.TYPE_INT_ARGB, OPAQUE_RED), quality = 95))
        assertEquals(SIDE, decoded.width)
        assertEquals(SIDE, decoded.height)
        val red = (decoded.getRGB(CENTER, CENTER) shr RED_SHIFT) and CHANNEL_MASK
        assertTrue(red > SATURATED_CHANNEL_FLOOR)
    }

    private fun solid(type: Int, color: Int): BufferedImage = BufferedImage(SIDE, SIDE, type).also { image ->
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) image.setRGB(x, y, color)
        }
    }

    private fun decode(bytes: ByteArray): BufferedImage = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))

    private companion object {
        const val SIDE = 8
        const val CENTER = 4
        const val BLUE = 0x0000FF
        val OPAQUE_RED = 0xFFFF0000.toInt()
        const val CHANNEL_MASK = 0xFF
        const val RED_SHIFT = 16
        const val SATURATED_CHANNEL_FLOOR = 200
        const val DIM_CHANNEL_CEILING = 40
        const val JPEG_MARKER = 0xFF
        const val JPEG_START_OF_IMAGE = 0xD8
    }
}
