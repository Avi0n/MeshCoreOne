package one.meshcore.shared.chat.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import one.meshcore.shared.avatar.decodeUprightBitmap
import java.io.ByteArrayOutputStream

actual fun decodePreviewImageBytes(bytes: ByteArray): ImageBitmap? {
    val decoded = boundedUprightBitmap(bytes) ?: return null
    return decoded.asImageBitmap()
}

/**
 * Bounds the pixel buffer before the bubble draws it. EXIF is applied on the subsampled decode,
 * then a final scale catches a decoder that still lands above [MESSAGE_IMAGE_MAX_PIXEL_SIZE].
 */
private fun boundedUprightBitmap(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = messageImageSampleSize(bounds.outWidth, bounds.outHeight)
    }
    val upright = decodeUprightBitmap(bytes, options) ?: return null
    return scaleDownToDisplayCap(upright)
}

private fun scaleDownToDisplayCap(decoded: Bitmap): Bitmap {
    val target = displayScaleDown(decoded.width, decoded.height) ?: return decoded
    val scaled = Bitmap.createScaledBitmap(decoded, target.width, target.height, true)
    if (scaled !== decoded) decoded.recycle()
    return scaled
}

actual fun encodeImageBitmapPng(bitmap: ImageBitmap): ByteArray? {
    val out = ByteArrayOutputStream()
    val ok = bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
    return if (ok) out.toByteArray() else null
}

private const val PNG_QUALITY = 100
