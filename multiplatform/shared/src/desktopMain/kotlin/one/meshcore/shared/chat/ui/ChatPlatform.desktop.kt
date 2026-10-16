package one.meshcore.shared.chat.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil3.request.ImageRequest

// Desktop has no soft keyboard, so no IME inset is needed.
@Composable
actual fun Modifier.chatImePadding(): Modifier = this

@Composable
actual fun Modifier.cliImePadding(): Modifier = this

@Composable
actual fun currentImeBottomPx(): Int = 0

// No animated-image decoder ships for the desktop JVM target, so Coil already renders a static first
// frame regardless of repeat count; this is a no-op rather than a silent wrong-package reference.
actual fun ImageRequest.Builder.applyGifAutoplay(autoPlay: Boolean): ImageRequest.Builder = this

/**
 * The desktop scrollbar. `rememberScrollbarAdapter` maps the [LazyListState] scroll position to the thumb;
 * under `reverseLayout` the list's logical index 0 sits at the visual bottom, which the adapter already
 * reflects, so the thumb tracks the visible viewport rather than the logical order.
 */
@Composable
actual fun ChatVerticalScrollbar(listState: LazyListState, reverseLayout: Boolean, modifier: Modifier) {
    VerticalScrollbar(adapter = rememberScrollbarAdapter(listState), modifier = modifier)
}
