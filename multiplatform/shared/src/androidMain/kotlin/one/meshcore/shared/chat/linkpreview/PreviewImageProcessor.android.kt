package one.meshcore.shared.chat.linkpreview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import one.meshcore.shared.avatar.decodeUprightBitmap
import java.io.ByteArrayOutputStream

/** Android [defaultPreviewImageProcessor], decoding through `BitmapFactory`. */
actual fun defaultPreviewImageProcessor(): PreviewImageProcessor = AndroidPreviewImageProcessor

private object AndroidPreviewImageProcessor : PreviewImageProcessor {
    override fun fit(bytes: ByteArray): ByteArray? {
        val bitmap = boundedDecode(bytes) ?: return null
        return try {
            fitToMaxSize(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodes at a bounded pixel size. `inJustDecodeBounds` reads the intrinsic dimensions without
     * allocating a pixel buffer, so `inSampleSize` can bound the decode before any large allocation — a
     * small file crafted to decompress into a huge bitmap is subsampled, not materialised.
     */
    private fun boundedDecode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = PreviewImageProcessor.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return decodeUprightBitmap(bytes, options)
    }

    private fun fitToMaxSize(bitmap: Bitmap): ByteArray = PreviewImageProcessor.fitEncoded(
        width = bitmap.width,
        height = bitmap.height,
        encodeAtQuality = { quality -> encode(bitmap, quality) },
        encodeScaled = { scaledWidth, scaledHeight ->
            val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            try {
                encode(scaled, PreviewImageProcessor.INITIAL_JPEG_QUALITY)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        },
    )

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        stream.toByteArray()
    }
}
