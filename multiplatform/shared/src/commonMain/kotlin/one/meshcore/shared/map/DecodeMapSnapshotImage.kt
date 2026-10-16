package one.meshcore.shared.map

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes PNG/JPEG snapshot bytes for Compose display. Platform-specific image codecs. */
expect fun decodeMapSnapshotImage(bytes: ByteArray): ImageBitmap?
