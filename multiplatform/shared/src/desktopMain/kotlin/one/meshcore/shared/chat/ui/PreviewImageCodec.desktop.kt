package one.meshcore.shared.chat.ui

import androidx.compose.ui.graphics.ImageBitmap
import one.meshcore.shared.image.decodeSkiaImageBitmap
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader

actual fun decodePreviewImageBytes(bytes: ByteArray): ImageBitmap? {
    val bounded = imageIoBoundedPng(bytes)
    if (bounded != null) return decodeSkiaImageBitmap(bounded)
    return decodeSkiaImageBitmap(bytes)
}

actual fun encodeImageBitmapPng(bitmap: ImageBitmap): ByteArray? = null

/**
 * Subsamples through ImageIO before any full-size pixel buffer, then re-encodes at the display cap.
 * An image already inside the cap, or a format ImageIO cannot read, falls through to Skia.
 */
private fun imageIoBoundedPng(bytes: ByteArray): ByteArray? {
    if (bytes.isEmpty()) return null
    val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
    return stream.use { input ->
        val readers = ImageIO.getImageReaders(input)
        val image = if (readers.hasNext()) {
            decodeSubsampled(readers.next().also { it.input = input })
        } else {
            null
        }
        image?.let(::pngBytes)
    }
}

private fun decodeSubsampled(reader: ImageReader): BufferedImage? = try {
    runCatching {
        val width = reader.getWidth(0)
        val height = reader.getHeight(0)
        val sample = messageImageSampleSize(width, height)
        if (sample == 1) return@runCatching null
        val param = reader.defaultReadParam
        param.setSourceSubsampling(sample, sample, 0, 0)
        val decoded = reader.read(0, param)
        val target = displayScaleDown(decoded.width, decoded.height)
        if (target == null) decoded else scaleDown(decoded, target.width, target.height)
    }.getOrNull()
} finally {
    reader.dispose()
}

private fun scaleDown(image: BufferedImage, width: Int, height: Int): BufferedImage {
    val scaled = image.getScaledInstance(width, height, Image.SCALE_SMOOTH)
    val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    try {
        graphics.drawImage(scaled, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return target
}

private fun pngBytes(image: BufferedImage): ByteArray? {
    val output = ByteArrayOutputStream()
    val wrote = ImageIO.write(image, "png", output)
    return if (wrote) output.toByteArray() else null
}
