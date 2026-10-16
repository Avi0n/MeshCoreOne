package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import coil3.gif.repeatCount
import coil3.request.ImageRequest

@Composable
actual fun Modifier.chatImePadding(): Modifier = ownedKeyboardLiftPadding(currentImeBottomPx())

@Composable
actual fun Modifier.cliImePadding(): Modifier {
    val density = LocalDensity.current
    val proposedPx = imeLiftInsideNavigationBounds(
        imeBottom = currentImeBottomPx(),
        navigationBottom = WindowInsets.navigationBars.getBottom(density),
    )
    return ownedKeyboardLiftPadding(proposedPx)
}

@Composable
private fun Modifier.ownedKeyboardLiftPadding(proposedPx: Int): Modifier {
    val density = LocalDensity.current
    val view = LocalView.current
    var currentPx by remember(view) { mutableIntStateOf(0) }
    val layoutNotifications = remember(view) { ChatKeyboardLift.LayoutNotifications(0, view::requestLayout) }
    val liftPx = ChatKeyboardLift.resolvedLift(
        current = currentPx.toFloat(),
        proposed = proposedPx.toFloat(),
        isInteractivePopActive = LocalInteractivePopActive.current,
    ).toInt()
    SideEffect { currentPx = liftPx }
    return padding(bottom = with(density) { liftPx.toDp() })
        .onGloballyPositioned { layoutNotifications.onPlaced(liftPx) }
}

@Composable
actual fun currentImeBottomPx(): Int {
    val density = LocalDensity.current
    val view = LocalView.current
    val composeInset = WindowInsets.ime.getBottom(density)
    val rootInset = ViewCompat.getRootWindowInsets(view)
        ?.getInsets(WindowInsetsCompat.Type.ime())
        ?.bottom
        ?: 0
    var currentPx by remember(view) { mutableIntStateOf(0) }
    val heldPx = ChatKeyboardLift.resolvedLift(
        current = currentPx.toFloat(),
        proposed = maxOf(composeInset, rootInset).toFloat(),
        isInteractivePopActive = LocalInteractivePopActive.current,
    ).toInt()
    SideEffect { currentPx = heldPx }
    return heldPx
}

// Android renders its own transient over-scroll scrollbar; a persistent one is not idiomatic, so the
// thread list draws none. The reverse-layout inversion is therefore a no-op here.
@Composable
actual fun ChatVerticalScrollbar(listState: LazyListState, reverseLayout: Boolean, modifier: Modifier) = Unit

// A request with no explicit repeatCount loops indefinitely (Coil's default), matching iOS's continuous
// GIF loop; repeatCount(0) shows only the first decoded frame, matching the autoplay-disabled setting.
private const val GIF_STATIC_FRAME_REPEAT_COUNT = 0

actual fun ImageRequest.Builder.applyGifAutoplay(autoPlay: Boolean): ImageRequest.Builder =
    if (autoPlay) this else repeatCount(GIF_STATIC_FRAME_REPEAT_COUNT)
