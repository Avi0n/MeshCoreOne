package one.meshcore.shared.avatar

import android.media.ExifInterface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * EXIF orientation must rotate Android decode the way iOS `UIImage` does, so a camera JPEG tagged
 * 6 (90°) is upright in Move and Scale and in the stored crop.
 */
class AvatarExifTest {
    @Test
    fun rotate90MapsToTheClockwiseOp() {
        assertEquals(AvatarExifOp.ROTATE_90, avatarExifOp(ExifInterface.ORIENTATION_ROTATE_90))
    }

    @Test
    fun rotate270MapsToTheCounterclockwiseOp() {
        assertEquals(AvatarExifOp.ROTATE_270, avatarExifOp(ExifInterface.ORIENTATION_ROTATE_270))
    }

    @Test
    fun rotate180Maps() {
        assertEquals(AvatarExifOp.ROTATE_180, avatarExifOp(ExifInterface.ORIENTATION_ROTATE_180))
    }

    @Test
    fun flipsAndTransposesMap() {
        assertEquals(AvatarExifOp.FLIP_HORIZONTAL, avatarExifOp(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
        assertEquals(AvatarExifOp.FLIP_VERTICAL, avatarExifOp(ExifInterface.ORIENTATION_FLIP_VERTICAL))
        assertEquals(AvatarExifOp.TRANSPOSE, avatarExifOp(ExifInterface.ORIENTATION_TRANSPOSE))
        assertEquals(AvatarExifOp.TRANSVERSE, avatarExifOp(ExifInterface.ORIENTATION_TRANSVERSE))
    }

    @Test
    fun normalAndUndefinedStayUpright() {
        assertEquals(AvatarExifOp.NONE, avatarExifOp(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(AvatarExifOp.NONE, avatarExifOp(ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals(AvatarExifOp.NONE, avatarExifOp(0))
    }

    @Test
    fun noneBuildsNoMatrix() {
        assertNull(avatarExifMatrix(AvatarExifOp.NONE))
    }
}
