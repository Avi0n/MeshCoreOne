package one.meshcore.shared.chat.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes PNG/JPEG link-preview hero/icon bytes for Compose display, the seam behind [PreviewImageResolver].
 * Platform-specific image codecs, mirroring [one.meshcore.shared.map.decodeMapSnapshotImage]; iOS resolves
 * the same bytes through `imageResolver: (ImageReference) -> UIImage?`.
 */
expect fun decodePreviewImageBytes(bytes: ByteArray): ImageBitmap?

/** Encodes a decoded still for share. Null when the platform cannot write PNG. */
expect fun encodeImageBitmapPng(bitmap: ImageBitmap): ByteArray?

/** iOS `ImageURLDetector.inlineMaxPixelSize`. Heroes, icons, and inline stills share this display cap. */
internal const val MESSAGE_IMAGE_MAX_PIXEL_SIZE: Int = 900

internal class DisplayPixelSize(val width: Int, val height: Int)

/**
 * Power-of-two subsample that brings both edges to at most [maxPixel].
 * Keeps doubling until the divided edge fits, so the decode cannot stay above the cap.
 */
internal fun messageImageSampleSize(width: Int, height: Int, maxPixel: Int = MESSAGE_IMAGE_MAX_PIXEL_SIZE): Int {
    if (width <= 0 || height <= 0 || maxPixel <= 0) return 1
    var sample = 1
    while (sampleCanGrow(sample) && longestEdgeExceeds(width, height, sample, maxPixel)) {
        sample *= 2
    }
    return sample
}

/**
 * Size to draw when an edge exceeds [maxPixel]. Truncates, so a fractional edge cannot become max+1.
 * Null when the bitmap is already within the cap or has no pixels.
 */
internal fun displayScaleDown(
    width: Int,
    height: Int,
    maxPixel: Int = MESSAGE_IMAGE_MAX_PIXEL_SIZE,
): DisplayPixelSize? {
    val longest = maxOf(width, height)
    if (width <= 0 || height <= 0 || longest <= maxPixel) return null
    val scale = maxPixel.toFloat() / longest.toFloat()
    return DisplayPixelSize(
        width = (width * scale).toInt().coerceAtLeast(1),
        height = (height * scale).toInt().coerceAtLeast(1),
    )
}

private fun sampleCanGrow(sample: Int): Boolean = sample <= Int.MAX_VALUE / 2

private fun longestEdgeExceeds(width: Int, height: Int, sample: Int, maxPixel: Int): Boolean {
    val widthOver = width / sample > maxPixel
    val heightOver = height / sample > maxPixel
    return widthOver || heightOver
}
