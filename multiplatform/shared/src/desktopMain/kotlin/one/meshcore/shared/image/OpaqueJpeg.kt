package one.meshcore.shared.image

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Writes [image] as JPEG. Alpha is flattened onto an opaque RGB canvas first, because JPEG cannot
 * carry it. [quality] is 0–100.
 */
internal fun encodeOpaqueJpeg(image: BufferedImage, quality: Int): ByteArray {
    val rgb = if (image.type == BufferedImage.TYPE_INT_RGB) {
        image
    } else {
        BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also { flattened ->
            val graphics = flattened.createGraphics()
            try {
                graphics.drawImage(image, 0, 0, null)
            } finally {
                graphics.dispose()
            }
        }
    }
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val output = ByteArrayOutputStream()
    return ImageIO.createImageOutputStream(output).use { stream ->
        writer.output = stream
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality / JPEG_QUALITY_SCALE
        }
        try {
            writer.write(null, IIOImage(rgb, null, null), param)
        } finally {
            writer.dispose()
        }
        output.toByteArray()
    }
}

private const val JPEG_QUALITY_SCALE = 100f
