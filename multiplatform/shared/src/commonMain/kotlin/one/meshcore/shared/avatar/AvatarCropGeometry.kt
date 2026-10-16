package one.meshcore.shared.avatar

/** Pan, zoom, and crop-guide math for the avatar crop canvas. */
data class AvatarCropGeometry(
    val cropSize: Float,
    val imageWidth: Float,
    val imageHeight: Float,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val baseDisplayWidth: Float
        get() {
            if (imageWidth <= 0f || imageHeight <= 0f) return cropSize
            val fillScale = maxOf(cropSize / imageWidth, cropSize / imageHeight)
            return imageWidth * fillScale
        }

    val baseDisplayHeight: Float
        get() {
            if (imageWidth <= 0f || imageHeight <= 0f) return cropSize
            val fillScale = maxOf(cropSize / imageWidth, cropSize / imageHeight)
            return imageHeight * fillScale
        }

    fun clampedScale(proposed: Float): Float = proposed.coerceIn(MIN_SCALE, MAX_SCALE)

    /** Keeps the crop square fully covered at the given scale. */
    fun clampedOffset(proposedX: Float, proposedY: Float, scale: Float = this.scale): AvatarCropOffset {
        val displayedWidth = baseDisplayWidth * scale
        val displayedHeight = baseDisplayHeight * scale
        val maxX = maxOf(0f, (displayedWidth - cropSize) / 2f)
        val maxY = maxOf(0f, (displayedHeight - cropSize) / 2f)
        return AvatarCropOffset(
            width = proposedX.coerceIn(-maxX, maxX),
            height = proposedY.coerceIn(-maxY, maxY),
        )
    }

    fun liveTransform(pinchDelta: Float, dragX: Float, dragY: Float): AvatarCropLiveTransform {
        val liveScale = clampedScale(scale * pinchDelta)
        val offset = clampedOffset(offsetX + dragX, offsetY + dragY, liveScale)
        return AvatarCropLiveTransform(scale = liveScale, offsetX = offset.width, offsetY = offset.height)
    }

    fun imageDrawRect(outputSide: Float): AvatarCropRect {
        val displayedWidth = baseDisplayWidth * scale
        val displayedHeight = baseDisplayHeight * scale
        val originX = (cropSize - displayedWidth) / 2f + offsetX
        val originY = (cropSize - displayedHeight) / 2f + offsetY
        val renderScale = outputSide / cropSize
        return AvatarCropRect(
            x = originX * renderScale,
            y = originY * renderScale,
            width = displayedWidth * renderScale,
            height = displayedHeight * renderScale,
        )
    }

    fun applyingZoomStep(delta: Float): AvatarCropGeometry {
        val nextScale = clampedScale(scale + delta)
        val offset = clampedOffset(offsetX, offsetY, nextScale)
        return copy(scale = nextScale, offsetX = offset.width, offsetY = offset.height)
    }

    fun applyingPan(deltaX: Float, deltaY: Float): AvatarCropGeometry {
        val offset = clampedOffset(offsetX + deltaX, offsetY + deltaY)
        return copy(offsetX = offset.width, offsetY = offset.height)
    }

    fun applyingLive(pinchDelta: Float, dragX: Float, dragY: Float): AvatarCropGeometry {
        val live = liveTransform(pinchDelta, dragX, dragY)
        return copy(scale = live.scale, offsetX = live.offsetX, offsetY = live.offsetY)
    }

    fun withCropSize(cropSize: Float): AvatarCropGeometry {
        val next = copy(cropSize = cropSize)
        val offset = next.clampedOffset(next.offsetX, next.offsetY)
        return next.copy(offsetX = offset.width, offsetY = offset.height)
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
        const val COMPACT_MAX_CROP_SIZE = 300f
        const val REGULAR_MAX_CROP_SIZE = 480f
        const val CROP_MARGIN = 32f
        const val OUTPUT_SIDE = 512f
        const val DECODE_MAX_PIXEL_SIZE = 1024
        const val ZOOM_STEP = 0.25f
        const val PAN_STEP_FRACTION = 0.125f
        const val REGULAR_WIDTH_DP = 600f

        fun cropSize(availableWidth: Float, availableHeight: Float, isRegularWidth: Boolean): Float {
            val maxSide = if (isRegularWidth) REGULAR_MAX_CROP_SIZE else COMPACT_MAX_CROP_SIZE
            val shortest = minOf(availableWidth, availableHeight)
            val fitted = minOf(maxSide, shortest - CROP_MARGIN * 2f)
            return maxOf(fitted, 1f)
        }
    }
}

data class AvatarCropOffset(val width: Float, val height: Float)

data class AvatarCropLiveTransform(val scale: Float, val offsetX: Float, val offsetY: Float)

data class AvatarCropRect(val x: Float, val y: Float, val width: Float, val height: Float)
