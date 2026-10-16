package one.meshcore.shared.avatar

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvatarImageCodecDesktopTest {
    @Test
    fun invalidImagesFailClosed() {
        val garbage = byteArrayOf(1, 2, 3)

        assertNull(processAvatarImage(garbage))
        assertNull(downsampleAvatarForCrop(garbage, maxPixelSize = DOWNSAMPLE_SIDE))
        assertNull(cropAvatarImage(garbage, geometry()))
    }

    @Test
    fun processingCapsTheLongestEdgeAndEncodesJpeg() {
        val decoded = decode(requireNotNull(processAvatarImage(sourceBytes())))

        assertEquals(AVATAR_MAX_DIMENSION, decoded.width)
        assertEquals(AVATAR_MAX_DIMENSION / ASPECT_RATIO, decoded.height)
    }

    @Test
    fun cropPreparationCapsTheLongestEdgeAndEncodesPng() {
        val decoded = decode(requireNotNull(downsampleAvatarForCrop(sourceBytes(), DOWNSAMPLE_SIDE)))

        assertEquals(DOWNSAMPLE_SIDE, decoded.width)
        assertEquals(DOWNSAMPLE_SIDE / ASPECT_RATIO, decoded.height)
    }

    @Test
    fun cropRendersTheConfiguredSquareOutput() {
        val decoded = decode(requireNotNull(cropAvatarImage(sourceBytes(), geometry())))

        assertEquals(AvatarCropGeometry.OUTPUT_SIDE.toInt(), decoded.width)
        assertEquals(AvatarCropGeometry.OUTPUT_SIDE.toInt(), decoded.height)
    }

    private fun geometry() = AvatarCropGeometry(
        cropSize = DOWNSAMPLE_SIDE.toFloat(),
        imageWidth = SOURCE_WIDTH.toFloat(),
        imageHeight = SOURCE_HEIGHT.toFloat(),
    )

    private fun sourceBytes(): ByteArray = ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(SOURCE_WIDTH, SOURCE_HEIGHT, BufferedImage.TYPE_INT_RGB), "png", output)
        output.toByteArray()
    }

    private fun decode(bytes: ByteArray): BufferedImage = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))

    private companion object {
        const val SOURCE_WIDTH = 1024
        const val SOURCE_HEIGHT = 256
        const val ASPECT_RATIO = SOURCE_WIDTH / SOURCE_HEIGHT
        const val DOWNSAMPLE_SIDE = 128
    }
}
