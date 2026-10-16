package one.meshcore.shared.avatar

import kotlin.test.Test
import kotlin.test.assertEquals

/** Mirrors iOS `AvatarCropGeometryTests`. */
class AvatarCropGeometryTest {
    @Test
    fun fillSizeCoversTheCropSquareForALandscapeImage() {
        val geometry = AvatarCropGeometry(cropSize = 100f, imageWidth = 200f, imageHeight = 100f)
        assertEquals(200f, geometry.baseDisplayWidth)
        assertEquals(100f, geometry.baseDisplayHeight)
    }

    @Test
    fun fillSizeCoversTheCropSquareForAPortraitImage() {
        val geometry = AvatarCropGeometry(cropSize = 100f, imageWidth = 100f, imageHeight = 200f)
        assertEquals(100f, geometry.baseDisplayWidth)
        assertEquals(200f, geometry.baseDisplayHeight)
    }

    @Test
    fun zeroImageSizeFallsBackToTheCropSquare() {
        val geometry = AvatarCropGeometry(cropSize = 100f, imageWidth = 0f, imageHeight = 0f)
        assertEquals(100f, geometry.baseDisplayWidth)
        assertEquals(100f, geometry.baseDisplayHeight)
    }

    @Test
    fun scaleClampsToOneThroughFour() {
        val geometry = AvatarCropGeometry(cropSize = 100f, imageWidth = 100f, imageHeight = 100f)
        assertEquals(1f, geometry.clampedScale(0.5f))
        assertEquals(1f, geometry.clampedScale(1f))
        assertEquals(2f, geometry.clampedScale(2f))
        assertEquals(4f, geometry.clampedScale(8f))
    }

    @Test
    fun offsetClampsSoTheCropSquareStaysCovered() {
        val geometry = AvatarCropGeometry(
            cropSize = 100f,
            imageWidth = 100f,
            imageHeight = 100f,
            scale = 2f,
        )
        val clamped = geometry.clampedOffset(1000f, -1000f)
        assertEquals(50f, clamped.width)
        assertEquals(-50f, clamped.height)
    }

    @Test
    fun livePinchReclampsOffsetToTheLiveScale() {
        val geometry = AvatarCropGeometry(
            cropSize = 100f,
            imageWidth = 100f,
            imageHeight = 100f,
            scale = 4f,
            offsetX = 150f,
        )
        val live = geometry.liveTransform(pinchDelta = 0.5f, dragX = 0f, dragY = 0f)
        assertEquals(2f, live.scale)
        assertEquals(50f, live.offsetX)
        assertEquals(0f, live.offsetY)
    }

    @Test
    fun drawRectAtScaleOneOffsetZeroMatchesTheCenteredFill() {
        val geometry = AvatarCropGeometry(cropSize = 100f, imageWidth = 200f, imageHeight = 100f)
        val rect = geometry.imageDrawRect(outputSide = 512f)
        assertEquals(-256f, rect.x, absoluteTolerance = 0.001f)
        assertEquals(0f, rect.y, absoluteTolerance = 0.001f)
        assertEquals(1024f, rect.width, absoluteTolerance = 0.001f)
        assertEquals(512f, rect.height, absoluteTolerance = 0.001f)
    }

    @Test
    fun cropSizeShrinksOnAShortLandscapeCanvas() {
        val size = AvatarCropGeometry.cropSize(
            availableWidth = 667f,
            availableHeight = 280f,
            isRegularWidth = false,
        )
        assertEquals(280f - AvatarCropGeometry.CROP_MARGIN * 2f, size)
    }

    @Test
    fun cropSizeUsesTheRegularCapOnTabletWidth() {
        val size = AvatarCropGeometry.cropSize(
            availableWidth = 1024f,
            availableHeight = 768f,
            isRegularWidth = true,
        )
        assertEquals(AvatarCropGeometry.REGULAR_MAX_CROP_SIZE, size)
    }
}
