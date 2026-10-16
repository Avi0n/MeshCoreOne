package one.meshcore.shared.chat.linkpreview

import kotlin.math.sqrt

/**
 * Bounds a scraped preview image's decode and re-compresses it, the platform half of the Swift
 * `LinkPreviewService` image path (`boundedDecode` + `fitToMaxSize`). Two safety properties, both
 * SAFETY-CRITICAL: the decode is bounded to [MAX_PIXEL_SIZE] on its largest edge so a small file crafted
 * to decompress into an oversized bitmap cannot exhaust memory (a decode bomb), and the re-encoded result
 * is fitted to [MAX_ENCODED_BYTES] so an oversized hero never bloats the cache. Returns null when the bytes
 * are not a decodable image. Kept an interface so [KtorLinkContentFetcher] stays platform-agnostic and the
 * decode is supplied per JVM target (`android.graphics.BitmapFactory` vs. `javax.imageio`).
 */
fun interface PreviewImageProcessor {
    /** Bounded-decodes and re-encodes [bytes] to a fitted JPEG, or null if they are not a decodable image. */
    fun fit(bytes: ByteArray): ByteArray?

    companion object {
        /**
         * Max pixel dimension for the bounded decode, matching the Swift `scrapedImageMaxPixelSize`. The
         * decode subsamples to keep the larger edge at or under this before any full-size buffer is
         * allocated, so a decode bomb is refused rather than materialised.
         */
        const val MAX_PIXEL_SIZE: Int = 2000

        /** Post-decode size ceiling, matching the Swift `maxImageSize` (500 KiB); over it, recompress. */
        const val MAX_ENCODED_BYTES: Int = 500 * 1024

        /** Initial JPEG quality for the bounded re-encode, matching `scrapedImageJPEGQuality`. */
        const val INITIAL_JPEG_QUALITY: Int = 90

        /** Lowest JPEG quality the fit loop drops to before scaling down, matching the Swift 0.1 floor. */
        const val MIN_JPEG_QUALITY: Int = 10

        /** Quality step for the fit loop, matching the Swift 0.1 decrement. */
        const val JPEG_QUALITY_STEP: Int = 10

        /**
         * Largest power-of-two subsample keeping both edges at or under [MAX_PIXEL_SIZE].
         * Both platform decoders use this so the bound stays one calculation.
         */
        fun sampleSize(width: Int, height: Int): Int {
            var sample = 1
            while (width / (sample * 2) >= MAX_PIXEL_SIZE || height / (sample * 2) >= MAX_PIXEL_SIZE) {
                sample *= 2
            }
            return sample
        }

        /**
         * Drops JPEG quality until the bytes fit [MAX_ENCODED_BYTES], then scales once from the last
         * encoded size if the quality floor is still too large. Both platform encoders use this ladder.
         */
        internal fun fitEncoded(
            width: Int,
            height: Int,
            encodeAtQuality: (Int) -> ByteArray,
            encodeScaled: (Int, Int) -> ByteArray,
        ): ByteArray {
            var quality = INITIAL_JPEG_QUALITY
            var encoded = encodeAtQuality(quality)
            while (encoded.size > MAX_ENCODED_BYTES && quality > MIN_JPEG_QUALITY) {
                quality -= JPEG_QUALITY_STEP
                encoded = encodeAtQuality(quality)
            }
            if (encoded.size <= MAX_ENCODED_BYTES) return encoded
            val scale = sqrt(MAX_ENCODED_BYTES.toDouble() / encoded.size)
            return encodeScaled(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
            )
        }
    }
}
