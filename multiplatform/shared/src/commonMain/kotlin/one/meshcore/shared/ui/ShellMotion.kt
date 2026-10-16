package one.meshcore.shared.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import one.meshcore.shared.layout.PickerSheetLayout

/** Shared-axis / fade / list / sheet timings; zeroed or skipped under reduce-motion. */
internal object ShellMotion {
    const val AXIS_DURATION_MS: Int = 220
    const val FADE_DURATION_MS: Int = 180
    const val SHEET_CONTENT_DURATION_MS: Int = 280

    /** Horizontal travel as a fraction of container width (Material shared-axis). */
    const val AXIS_SLIDE_FRACTION: Float = 0.08f

    /** Extra content rise inside a sheet (px-equivalent dp); must not affect measured height. */
    val SHEET_CONTENT_SLIDE = 12.dp
}

/**
 * Forward/back shared-axis for hierarchical pushes (chat/contact detail). [forward] is true when the
 * destination is deeper (enter from end).
 *
 * Size stays put. A size morph clips the incoming screen to the outgoing measured size. Opening a chat
 * changes that size — the tab bar leaves, and this layer's chats root is a stand-in rather than the
 * list — so the thread would grow in from a corner.
 */
internal fun AnimatedContentTransitionScope<*>.hierarchicalTransform(
    forward: Boolean,
    reduceMotion: Boolean,
): ContentTransform {
    if (reduceMotion) {
        return withoutSizeMorph(EnterTransition.None togetherWith ExitTransition.None)
    }
    val duration = ShellMotion.AXIS_DURATION_MS
    val slide: (Int) -> Int = { full -> (full * ShellMotion.AXIS_SLIDE_FRACTION).toInt().coerceAtLeast(1) }
    val transform = if (forward) {
        (
            fadeIn(tween(duration)) +
                slideInHorizontally(animationSpec = tween(duration)) { slide(it) }
            ) togetherWith (
            fadeOut(tween(duration)) +
                slideOutHorizontally(animationSpec = tween(duration)) { -slide(it) }
            )
    } else {
        (
            fadeIn(tween(duration)) +
                slideInHorizontally(animationSpec = tween(duration)) { -slide(it) }
            ) togetherWith (
            fadeOut(tween(duration)) +
                slideOutHorizontally(animationSpec = tween(duration)) { slide(it) }
            )
    }
    return withoutSizeMorph(transform)
}

/** Cross-fade for tab switches (no lateral slide — tabs are siblings, not a stack). */
internal fun AnimatedContentTransitionScope<*>.tabSwitchTransform(reduceMotion: Boolean): ContentTransform {
    if (reduceMotion) {
        return withoutSizeMorph(EnterTransition.None togetherWith ExitTransition.None)
    }
    val duration = ShellMotion.FADE_DURATION_MS
    return withoutSizeMorph(fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)))
}

private fun AnimatedContentTransitionScope<*>.withoutSizeMorph(transform: ContentTransform): ContentTransform =
    transform using null

/**
 * Lazy-list item enter / exit / re-order motion (M3 Expressive spring physics). Returns [Modifier]
 * unchanged when reduce-motion is on so inserts don't animate.
 */
@Composable
fun LazyItemScope.expressiveListItem(modifier: Modifier = Modifier): Modifier {
    val reduceMotion = rememberReduceMotion()
    if (reduceMotion) return modifier
    return modifier.animateItem(
        fadeInSpec = listItemFadeSpec(),
        fadeOutSpec = listItemFadeSpec(),
        placementSpec = listItemPlacementSpec(),
    )
}

private fun listItemFadeSpec(): FiniteAnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow)

private fun listItemPlacementSpec(): FiniteAnimationSpec<IntOffset> = spring(
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

/**
 * Subtle fade + rise for sheet body content. Content stays fully measured from frame 0 so
 * [androidx.compose.material3.ModalBottomSheet] can run its own slide/expand without a height pop;
 * motion is applied with [graphicsLayer] only. Respects reduce-motion.
 */
@Composable
fun ExpressiveSheetContent(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val reduceMotion = rememberReduceMotion()
    var entered by remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(Unit) {
        entered = true
    }
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "expressiveSheetContent",
    )
    val slidePx = with(LocalDensity.current) { ShellMotion.SHEET_CONTENT_SLIDE.toPx() }
    Column(
        modifier = modifier.graphicsLayer {
            if (!reduceMotion) {
                alpha = progress
                translationY = (1f - progress) * slidePx
            }
        },
        content = content,
    )
}

/** Phone-height picker on compact windows; medium+ uses [PickerSheetLayout.MEDIUM_HEIGHT_FRACTION]. */
@Composable
internal fun pickerSheetSizeModifier(compactHeightFraction: Float): Modifier {
    val widthDp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp().value
    }
    return Modifier
        .fillMaxWidth()
        .fillMaxHeight(PickerSheetLayout.heightFraction(widthDp, compactHeightFraction))
}
