// AndroidOnly: WP-407 Pack CameraX luma crop/stride/rotation without changing the camera buffer position.
package com.meshcoreone.android.app.platform

import java.nio.ByteBuffer

internal data class PackedLuma(val bytes: ByteArray, val width: Int, val height: Int)

internal fun packLuma(
    buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
    left: Int, top: Int, width: Int, height: Int, rotation: Int,
): PackedLuma {
    require(width > 0 && height > 0 && left >= 0 && top >= 0 && rowStride > 0 && pixelStride > 0)
    require(rotation in setOf(0, 90, 180, 270))
    val rotated = rotation == 90 || rotation == 270
    val outputWidth = if (rotated) height else width
    val outputHeight = if (rotated) width else height
    val bytes = ByteArray(Math.multiplyExact(width, height))
    val origin = buffer.position().toLong()
    for (y in 0 until height) for (x in 0 until width) {
        val index = origin + (top.toLong() + y) * rowStride + (left.toLong() + x) * pixelStride
        require(index < buffer.limit()) { "Camera luma plane is truncated" }
        val out = when (rotation) {
            0 -> y * width + x
            90 -> x * height + height - 1 - y
            180 -> (height - 1 - y) * width + width - 1 - x
            else -> (width - 1 - x) * height + y
        }
        bytes[out] = buffer.get(Math.toIntExact(index))
    }
    return PackedLuma(bytes, outputWidth, outputHeight)
}
