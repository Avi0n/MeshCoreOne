package one.meshcore.shared.avatar

import android.media.ExifInterface

/** Maps an [ExifInterface.TAG_ORIENTATION] value to the transform [decodeUprightBitmap] applies. */
internal fun avatarExifOp(orientation: Int): AvatarExifOp = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> AvatarExifOp.FLIP_HORIZONTAL
    ExifInterface.ORIENTATION_ROTATE_180 -> AvatarExifOp.ROTATE_180
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> AvatarExifOp.FLIP_VERTICAL
    ExifInterface.ORIENTATION_TRANSPOSE -> AvatarExifOp.TRANSPOSE
    ExifInterface.ORIENTATION_ROTATE_90 -> AvatarExifOp.ROTATE_90
    ExifInterface.ORIENTATION_TRANSVERSE -> AvatarExifOp.TRANSVERSE
    ExifInterface.ORIENTATION_ROTATE_270 -> AvatarExifOp.ROTATE_270
    else -> AvatarExifOp.NONE
}
