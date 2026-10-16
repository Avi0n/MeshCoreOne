package one.meshcore.shared.avatar

import androidx.compose.ui.graphics.ImageBitmap
import one.meshcore.shared.image.decodeSkiaImageBitmap
import one.meshcore.shared.image.encodeOpaqueJpeg
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

actual fun processAvatarImage(bytes: ByteArray): ByteArray? = decodeImage(bytes)?.let { source ->
    encodeOpaqueJpeg(downscale(source, AVATAR_MAX_DIMENSION), AVATAR_JPEG_QUALITY)
}

actual fun downsampleAvatarForCrop(bytes: ByteArray, maxPixelSize: Int): ByteArray? =
    decodeImage(bytes)?.let { source -> encodePng(downscale(source, maxPixelSize)) }

actual fun cropAvatarImage(bytes: ByteArray, geometry: AvatarCropGeometry): ByteArray? =
    decodeImage(bytes)?.let { source -> crop(source, geometry) }

private fun decodeImage(bytes: ByteArray): BufferedImage? = if (bytes.isEmpty()) {
    null
} else {
    runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
}

private fun crop(source: BufferedImage, geometry: AvatarCropGeometry): ByteArray {
    val side = AvatarCropGeometry.OUTPUT_SIDE.roundToInt()
    val output = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
    val graphics = output.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val rect = geometry.imageDrawRect(AvatarCropGeometry.OUTPUT_SIDE)
        graphics.drawImage(
            source,
            AffineTransform().apply {
                translate(rect.x.toDouble(), rect.y.toDouble())
                scale(rect.width / source.width.toDouble(), rect.height / source.height.toDouble())
            },
            null,
        )
    } finally {
        graphics.dispose()
    }
    return encodeOpaqueJpeg(output, AVATAR_JPEG_QUALITY)
}

/** Scales the image so its longest edge is at most [maxSide], leaving smaller images untouched. */
private fun downscale(image: BufferedImage, maxSide: Int): BufferedImage {
    val longest = maxOf(image.width, image.height)
    if (longest <= maxSide) return image
    val scale = maxSide.toDouble() / longest
    val width = (image.width * scale).roundToInt().coerceAtLeast(1)
    val height = (image.height * scale).roundToInt().coerceAtLeast(1)
    val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = target.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(image, 0, 0, width, height, null)
    } finally {
        graphics.dispose()
    }
    return target
}

private fun encodePng(image: BufferedImage): ByteArray = ByteArrayOutputStream().use { output ->
    ImageIO.write(image, "png", output)
    output.toByteArray()
}

actual fun decodeAvatarImage(bytes: ByteArray): ImageBitmap? = decodeSkiaImageBitmap(bytes)
