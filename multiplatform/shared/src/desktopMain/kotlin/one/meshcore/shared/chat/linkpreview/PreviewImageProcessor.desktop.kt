package one.meshcore.shared.chat.linkpreview

import one.meshcore.shared.image.encodeOpaqueJpeg
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader

/** Desktop [defaultPreviewImageProcessor], decoding through `javax.imageio` with source subsampling. */
actual fun defaultPreviewImageProcessor(): PreviewImageProcessor = DesktopPreviewImageProcessor

private object DesktopPreviewImageProcessor : PreviewImageProcessor {
    override fun fit(bytes: ByteArray): ByteArray? {
        val bitmap = boundedDecode(bytes) ?: return null
        return fitToMaxSize(bitmap)
    }

    /**
     * Decodes at a bounded pixel size. The reader exposes the intrinsic dimensions before decoding, so
     * source subsampling bounds the pixel buffer up front — a small file crafted to decompress into a huge
     * bitmap is subsampled, not materialised.
     */
    private fun boundedDecode(bytes: ByteArray): BufferedImage? {
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
        return stream.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (readers.hasNext()) decodeSubsampled(readers.next().also { it.input = input }) else null
        }
    }

    /** Reads the reader's image at a bounded subsampling; a decode failure yields null rather than throwing. */
    private fun decodeSubsampled(reader: ImageReader): BufferedImage? = try {
        runCatching {
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0) {
                null
            } else {
                val param = reader.defaultReadParam
                val sample = PreviewImageProcessor.sampleSize(width, height)
                if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0)
                reader.read(0, param)
            }
        }.getOrNull()
    } finally {
        reader.dispose()
    }

    private fun fitToMaxSize(image: BufferedImage): ByteArray = PreviewImageProcessor.fitEncoded(
        width = image.width,
        height = image.height,
        encodeAtQuality = { quality -> encodeOpaqueJpeg(image, quality) },
        encodeScaled = { scaledWidth, scaledHeight ->
            encodeOpaqueJpeg(
                scaledDown(image, scaledWidth, scaledHeight),
                PreviewImageProcessor.INITIAL_JPEG_QUALITY,
            )
        },
    )

    private fun scaledDown(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val scaled = image.getScaledInstance(width, height, Image.SCALE_SMOOTH)
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.drawImage(scaled, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return target
    }
}
