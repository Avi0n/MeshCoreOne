package one.meshcore.shared.avatar

import androidx.compose.ui.graphics.ImageBitmap

/** Longest-edge cap applied before an avatar is stored, matching the iOS 512pt downscale. */
const val AVATAR_MAX_DIMENSION: Int = 512

/** JPEG quality (0-100) for the stored avatar, matching the iOS 0.8 compression. */
const val AVATAR_JPEG_QUALITY: Int = 80

/**
 * Downscales [bytes] so its longest edge is at most [AVATAR_MAX_DIMENSION] and re-encodes as JPEG,
 * mirroring the iOS `processAvatarImage`. Returns null when [bytes] is not a decodable image. The
 * platform image codec runs on the calling thread, so callers move it off the UI dispatcher.
 */
expect fun processAvatarImage(bytes: ByteArray): ByteArray?

/** Decodes a stored avatar JPEG [bytes] for Compose display, or null when the bytes are undecodable. */
expect fun decodeAvatarImage(bytes: ByteArray): ImageBitmap?

/**
 * Downsamples [bytes] so its longest edge is at most [maxPixelSize] before the crop canvas, matching
 * iOS `ImageURLDetector.downsampledImage`. Android applies EXIF orientation so the preview matches
 * iOS `UIImage`. Returns PNG bytes, or null when [bytes] will not decode.
 */
expect fun downsampleAvatarForCrop(
    bytes: ByteArray,
    maxPixelSize: Int = AvatarCropGeometry.DECODE_MAX_PIXEL_SIZE,
): ByteArray?

/**
 * Renders the pan/zoom crop of [bytes] into a [AvatarCropGeometry.OUTPUT_SIDE] JPEG, matching iOS
 * `AvatarCropView.croppedImage` then `processAvatarImage`. Returns null when [bytes] will not decode.
 */
expect fun cropAvatarImage(bytes: ByteArray, geometry: AvatarCropGeometry): ByteArray?
