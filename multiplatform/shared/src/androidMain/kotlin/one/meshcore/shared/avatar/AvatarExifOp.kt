package one.meshcore.shared.avatar

/**
 * The EXIF orientation tag, mapped to a draw transform so Android decode matches iOS `UIImage`
 * (which applies orientation on load). [NONE] means the stored pixels are already upright.
 */
internal enum class AvatarExifOp {
    NONE,
    FLIP_HORIZONTAL,
    ROTATE_180,
    FLIP_VERTICAL,
    TRANSPOSE,
    ROTATE_90,
    TRANSVERSE,
    ROTATE_270,
}
