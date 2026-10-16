package one.meshcore.shared.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

actual fun processAvatarImage(bytes: ByteArray): ByteArray? {
    if (bytes.isEmpty()) return null
    val source = decodeUprightBitmap(bytes) ?: return null
    return try {
        val scaled = downscale(source, AVATAR_MAX_DIMENSION)
        try {
            encodeJpeg(scaled)
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    } finally {
        source.recycle()
    }
}

actual fun downsampleAvatarForCrop(bytes: ByteArray, maxPixelSize: Int): ByteArray? {
    val decoded = decodedAvatarSample(bytes, maxPixelSize) ?: return null
    return try {
        val scaled = downscale(decoded, maxPixelSize)
        try {
            ByteArrayOutputStream().use { stream ->
                scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream)
                stream.toByteArray()
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
        }
    } finally {
        decoded.recycle()
    }
}

actual fun cropAvatarImage(bytes: ByteArray, geometry: AvatarCropGeometry): ByteArray? {
    if (bytes.isEmpty()) return null
    val source = decodeUprightBitmap(bytes) ?: return null
    return try {
        val side = AvatarCropGeometry.OUTPUT_SIDE.roundToInt()
        val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val rect = geometry.imageDrawRect(AvatarCropGeometry.OUTPUT_SIDE)
        val dest = RectF(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        Canvas(output).drawBitmap(source, null, dest, paint)
        try {
            encodeJpeg(output)
        } finally {
            output.recycle()
        }
    } finally {
        source.recycle()
    }
}

actual fun decodeAvatarImage(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return decodeUprightBitmap(bytes)?.asImageBitmap()
}

private fun decodedAvatarSample(bytes: ByteArray, maxPixelSize: Int): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    return if (longest <= 0) {
        null
    } else {
        decodeUprightBitmap(
            bytes,
            BitmapFactory.Options().apply { inSampleSize = sampleSize(longest, maxPixelSize) },
        )
    }
}

/**
 * Decodes [bytes] and applies EXIF orientation so pixels match iOS `UIImage` / `UIImage.draw`.
 * `BitmapFactory.decodeByteArray` leaves orientation unapplied.
 */
internal fun decodeUprightBitmap(bytes: ByteArray, options: BitmapFactory.Options? = null): Bitmap? {
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = avatarExifMatrix(avatarExifOp(orientation)) ?: return decoded
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (rotated !== decoded) decoded.recycle()
    return rotated
}

internal fun avatarExifMatrix(op: AvatarExifOp): Matrix? {
    if (op == AvatarExifOp.NONE) return null
    val matrix = Matrix()
    when (op) {
        AvatarExifOp.NONE -> return null
        AvatarExifOp.FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        AvatarExifOp.ROTATE_180 -> matrix.postRotate(HALF_TURN_DEGREES)
        AvatarExifOp.FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        AvatarExifOp.TRANSPOSE -> {
            matrix.postRotate(QUARTER_TURN_DEGREES)
            matrix.postScale(-1f, 1f)
        }
        AvatarExifOp.ROTATE_90 -> matrix.postRotate(QUARTER_TURN_DEGREES)
        AvatarExifOp.TRANSVERSE -> {
            matrix.postRotate(THREE_QUARTER_TURN_DEGREES)
            matrix.postScale(-1f, 1f)
        }
        AvatarExifOp.ROTATE_270 -> matrix.postRotate(THREE_QUARTER_TURN_DEGREES)
    }
    return matrix
}

/** Scales the bitmap so its longest edge is at most [maxSide], leaving smaller images untouched. */
private fun downscale(bitmap: Bitmap, maxSide: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= maxSide) return bitmap
    val scale = maxSide.toDouble() / longest
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

private fun sampleSize(longest: Int, maxPixelSize: Int): Int {
    var sample = 1
    while (longest / (sample * 2) >= maxPixelSize) {
        sample *= 2
    }
    return sample
}

private fun encodeJpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { stream ->
    bitmap.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, stream)
    stream.toByteArray()
}

private const val PNG_QUALITY = 100
private const val QUARTER_TURN_DEGREES = 90f
private const val HALF_TURN_DEGREES = 180f
private const val THREE_QUARTER_TURN_DEGREES = 270f
