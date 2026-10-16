package one.meshcore.shared.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * Decodes [bytes] with Skia. Empty or undecodable bytes return null. Desktop does not apply an EXIF
 * orientation pass; Android preview and avatar decode upright on their own.
 */
internal fun decodeSkiaImageBitmap(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}
