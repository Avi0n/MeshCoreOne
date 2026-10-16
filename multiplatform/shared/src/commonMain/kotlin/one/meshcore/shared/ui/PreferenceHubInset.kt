package one.meshcore.shared.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Fills the overlay, keeps the scaffold inset fixed, then pads the content by the hub inset. */
fun Modifier.preferenceHubInset(contentPadding: PaddingValues): Modifier = fillMaxSize()
    .padding(contentPadding)
    .padding(top = PreferenceHubTopPadding, bottom = PreferenceHubSectionGap)

/** Fills the width, keeps the scaffold inset fixed, scrolls, then pads inside the scroll. */
@Composable
fun Modifier.preferenceHubInsetScroll(contentPadding: PaddingValues): Modifier = fillMaxWidth()
    .padding(contentPadding)
    .verticalScroll(rememberScrollState())
    .padding(top = PreferenceHubTopPadding, bottom = PreferenceHubSectionGap)

/** Pads the hub horizontal inset, the top inset, and the section gap. */
fun Modifier.preferenceHubFrame(): Modifier = padding(
    start = PreferenceHubHorizontalPadding,
    end = PreferenceHubHorizontalPadding,
    top = PreferenceHubTopPadding,
    bottom = PreferenceHubSectionGap,
)

/** Fills the overlay, keeps the scaffold inset fixed, then pads every hub edge. */
fun Modifier.preferenceHubOverlayFill(contentPadding: PaddingValues): Modifier = fillMaxSize()
    .padding(contentPadding)
    .preferenceHubFrame()

/** Fills the screen and pads the content by the hub inset. */
fun Modifier.preferenceHubFill(): Modifier = fillMaxSize()
    .padding(top = PreferenceHubTopPadding, bottom = PreferenceHubSectionGap)

/** Takes the remaining height in a column, fills the width, and scrolls. */
@Composable
fun ColumnScope.preferenceHubFormScroll(): Modifier = Modifier
    .weight(1f)
    .fillMaxWidth()
    .verticalScroll(rememberScrollState())

/** Fills the screen and pads every edge. */
fun Modifier.preferenceHubEdgeFill(): Modifier = fillMaxSize().padding(PreferenceHubEdgePadding)

/** Fills the screen, scrolls, then pads every edge. */
fun Modifier.preferenceHubEdgeFillScroll(scrollState: ScrollState): Modifier = fillMaxSize()
    .verticalScroll(scrollState)
    .padding(PreferenceHubEdgePadding)

/** Fills the width, scrolls, then pads every edge. */
@Composable
fun Modifier.preferenceHubEdgeScroll(): Modifier = fillMaxWidth()
    .verticalScroll(rememberScrollState())
    .padding(PreferenceHubEdgePadding)

/** Fills the width and pads a full-width action under card content. */
fun Modifier.preferenceHubCardAction(): Modifier = fillMaxWidth().padding(
    start = PreferenceHubCardPadding,
    end = PreferenceHubCardPadding,
    top = PreferenceHubCardActionTop,
    bottom = PreferenceHubCardPadding,
)
