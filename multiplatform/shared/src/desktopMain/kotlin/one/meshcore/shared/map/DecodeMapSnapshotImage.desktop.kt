package one.meshcore.shared.map

import androidx.compose.ui.graphics.ImageBitmap
import one.meshcore.shared.image.decodeSkiaImageBitmap

actual fun decodeMapSnapshotImage(bytes: ByteArray): ImageBitmap? = decodeSkiaImageBitmap(bytes)
