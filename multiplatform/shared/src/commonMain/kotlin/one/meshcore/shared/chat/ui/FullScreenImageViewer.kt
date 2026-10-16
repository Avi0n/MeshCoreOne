package one.meshcore.shared.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_image_viewer_close
import one.meshcore.shared.resources.chats_image_viewer_share
import one.meshcore.shared.ui.IMAGE_OVERLAY_CONTENT_COLOR
import one.meshcore.shared.ui.LocalShareImageBytes
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.icons.CloseIconButton
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val FULLSCREEN_MIN_SCALE = 1f

/** iOS `ZoomableImageView.maximumZoomScale`. */
internal const val FULLSCREEN_MAX_SCALE = 5f

/** iOS double-tap zoom target. */
internal const val FULLSCREEN_DOUBLE_TAP_SCALE = 3f

/** iOS `FullScreenImageViewer` dismisses when the drag exceeds 150pt. */
private val DISMISS_DRAG = 150.dp

/** iOS `onDragEnded` velocity threshold, in dp per second. */
private val DISMISS_VELOCITY = 1000.dp

/** iOS fades the black scrim over 300pt of drag. */
private val DRAG_FADE_RANGE = 300.dp

internal fun fullscreenImageScrim(scrim: Color, fade: Float) = scrim.copy(alpha = fade)

/**
 * Whether a viewer gesture should dismiss, matching iOS `onDragEnded`
 * (`abs(offset) > 150 || abs(velocity) > 1000` at 1x zoom).
 */
internal fun shouldDismissFullScreenImage(
    dragY: Float,
    velocityY: Float,
    scale: Float,
    thresholdPx: Float,
    velocityThresholdPxPerSec: Float,
): Boolean = scale <= FULLSCREEN_MIN_SCALE &&
    (abs(dragY) >= thresholdPx || abs(velocityY) >= velocityThresholdPxPerSec)

/** iOS double-tap toggles 1x and 3x. */
internal fun nextDoubleTapScale(current: Float): Float =
    if (current > FULLSCREEN_MIN_SCALE) FULLSCREEN_MIN_SCALE else FULLSCREEN_DOUBLE_TAP_SCALE

internal data class FullscreenDoubleTapResult(val scale: Float, val offsetX: Float, val offsetY: Float)

/**
 * iOS `ZoomableImageView.handleDoubleTap`: zoom out to 1x, or zoom to 3x with the tap as the
 * viewport center (`scrollView.zoom(to:)`).
 */
internal fun applyFullscreenDoubleTap(
    scale: Float,
    tapX: Float,
    tapY: Float,
    viewportCenterX: Float,
    viewportCenterY: Float,
): FullscreenDoubleTapResult {
    val next = nextDoubleTapScale(scale)
    if (next <= FULLSCREEN_MIN_SCALE) {
        return FullscreenDoubleTapResult(FULLSCREEN_MIN_SCALE, offsetX = 0f, offsetY = 0f)
    }
    return FullscreenDoubleTapResult(
        scale = next,
        offsetX = (viewportCenterX - tapX) * next,
        offsetY = (viewportCenterY - tapY) * next,
    )
}

private fun VelocityTracker.trackFullscreenPointer(change: PointerInputChange?, pointerCount: Int): Boolean {
    if (pointerCount != SINGLE_POINTER_COUNT || change == null) return false
    addPointerInputChange(change)
    return true
}

private fun VelocityTracker.fullscreenReleaseVelocity(eligible: Boolean): Float =
    if (eligible) calculateVelocity().y else 0f

private const val SINGLE_POINTER_COUNT = 1

private class FullscreenGesturePolicy {
    var previousTapEligible = false
    var tapEligible = false
    var transformed = false

    val doubleTapEligible: Boolean get() = previousTapEligible && tapEligible

    fun recordPointerEligibility(eligible: Boolean) {
        tapEligible = tapEligible && eligible
    }
}

/** Full-screen decoded bitmap: pinch/pan, double-tap 1x↔3x, drag-to-dismiss, Close, and Share of those bytes. */
@Composable
fun FullScreenBitmapViewer(bitmap: ImageBitmap, onDismiss: () -> Unit) {
    val shareBytes = LocalShareImageBytes.current
    FullScreenImageDialog(
        onDismiss = onDismiss,
        onShare = { encodeImageBitmapPng(bitmap)?.let(shareBytes) },
    ) { zoomModifier ->
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = zoomModifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Composable
private fun FullScreenImageDialog(
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    content: @Composable (zoomModifier: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val dismissPx = with(density) { DISMISS_DRAG.toPx() }
    val velocityPxPerSec = with(density) { DISMISS_VELOCITY.toPx() }
    val fadeRangePx = with(density) { DRAG_FADE_RANGE.toPx() }
    var scale by remember { mutableFloatStateOf(FULLSCREEN_MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val gesturePolicy = remember { FullscreenGesturePolicy() }
    val configuration = LocalViewConfiguration.current
    val currentDismiss by rememberUpdatedState(onDismiss)
    val fade = 1f - (abs(dragY) / fadeRangePx).coerceAtMost(1f) * 0.5f

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(fullscreenImageScrim(MaterialTheme.colorScheme.scrim, fade)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(dismissPx, velocityPxPerSec, configuration) {
                        detectTapGestures(
                            onDoubleTap = { tap ->
                                if (gesturePolicy.doubleTapEligible) {
                                    val result = applyFullscreenDoubleTap(
                                        scale = scale,
                                        tapX = tap.x,
                                        tapY = tap.y,
                                        viewportCenterX = size.width / 2f,
                                        viewportCenterY = size.height / 2f,
                                    )
                                    scale = result.scale
                                    offset = Offset(result.offsetX, result.offsetY)
                                    dragY = 0f
                                }
                            },
                        )
                    }
                    .pointerInput(dismissPx, velocityPxPerSec, configuration) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            gesturePolicy.transformed = true
                            gesturePolicy.tapEligible = false
                            val nextScale = (scale * zoom).coerceIn(FULLSCREEN_MIN_SCALE, FULLSCREEN_MAX_SCALE)
                            scale = nextScale
                            if (nextScale > FULLSCREEN_MIN_SCALE) {
                                offset += pan
                                dragY = 0f
                            } else {
                                offset = Offset.Zero
                                dragY += pan.y
                            }
                        }
                    }
                    .pointerInput(dismissPx, velocityPxPerSec, configuration) {
                        // Inner Main observes before our recognizers consume; Final follows their callbacks.
                        awaitEachGesture {
                            var completed = false
                            try {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                                gesturePolicy.previousTapEligible = gesturePolicy.tapEligible
                                gesturePolicy.tapEligible = !down.isConsumed
                                gesturePolicy.transformed = false
                                if (down.isConsumed) return@awaitEachGesture
                                val velocityTracker = VelocityTracker().apply { addPointerInputChange(down) }
                                var velocityEligible = true
                                var pointerCount = SINGLE_POINTER_COUNT
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val consumedBeforeRecognition = event.changes.any { it.isConsumed }
                                    if (consumedBeforeRecognition) return@awaitEachGesture
                                    pointerCount = maxOf(pointerCount, event.changes.size)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    velocityEligible =
                                        velocityEligible &&
                                        velocityTracker.trackFullscreenPointer(change, pointerCount)
                                    gesturePolicy.recordPointerEligibility(velocityEligible)
                                    awaitPointerEvent(PointerEventPass.Final)
                                } while (event.changes.any { it.pressed })
                                if (gesturePolicy.transformed &&
                                    shouldDismissFullScreenImage(
                                        dragY = dragY,
                                        velocityY = velocityTracker.fullscreenReleaseVelocity(velocityEligible),
                                        scale = scale,
                                        thresholdPx = dismissPx,
                                        velocityThresholdPxPerSec = velocityPxPerSec,
                                    )
                                ) {
                                    currentDismiss()
                                }
                                completed = true
                            } finally {
                                dragY = 0f
                                if (!completed) gesturePolicy.tapEligible = false
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                content(
                    Modifier
                        .offset { IntOffset(0, dragY.roundToInt()) }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
            CloseIconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                contentDescription = stringResource(Res.string.chats_image_viewer_close),
                tint = IMAGE_OVERLAY_CONTENT_COLOR,
            )
            AppIconButton(
                imageVector = AppIcons.Share,
                contentDescription = stringResource(Res.string.chats_image_viewer_share),
                onClick = onShare,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                tint = IMAGE_OVERLAY_CONTENT_COLOR,
            )
        }
    }
}
