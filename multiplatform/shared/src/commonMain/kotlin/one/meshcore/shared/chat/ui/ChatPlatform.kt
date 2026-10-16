package one.meshcore.shared.chat.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import coil3.request.ImageRequest

/**
 * True while a system back gesture (Android predictive back, iOS interactive pop analog) is in
 * progress. The compose bar holds its IME lift across that gesture so a hide inset cannot drop it
 * mid-screen.
 */
val LocalInteractivePopActive = staticCompositionLocalOf { false }

/**
 * Applies the software-keyboard inset to the composer so it rides above the IME. `imePadding` is an
 * Android concept; the desktop actual is a no-op (no soft keyboard). Kept behind expect/actual per the
 * plan's reverse-layout pitfall checklist rather than calling `imePadding()` directly in shared UI.
 */
@Composable
expect fun Modifier.chatImePadding(): Modifier

/** CLI terminal content already excludes the navigation bar from its layout bounds. */
@Composable
expect fun Modifier.cliImePadding(): Modifier

/** Window keyboard height, held during interactive back and unaffected by parent inset consumption. */
@Composable
expect fun currentImeBottomPx(): Int

/**
 * Draws a vertical scrollbar for the thread list where the platform has one (desktop), correcting for the
 * inverted [reverseLayout] axis, and no-ops where it does not (Android shows its own transient scrollbar).
 * Wrapping it here contains the "reverseLayout breaks scrollbars" pitfall to the platform actual instead
 * of leaking an inverted thumb into shared code.
 */
@Composable
expect fun ChatVerticalScrollbar(listState: LazyListState, reverseLayout: Boolean, modifier: Modifier)

/**
 * Applies the platform GIF-autoplay policy to an inline-image request: looping playback when [autoPlay]
 * is true, a static first frame when false. The animated-image decoder (`coil3.gif`) is Android-only — no
 * equivalent ships for the desktop JVM target — so the desktop actual is a no-op; Coil there always
 * renders a static frame regardless of [autoPlay].
 */
expect fun ImageRequest.Builder.applyGifAutoplay(autoPlay: Boolean): ImageRequest.Builder
