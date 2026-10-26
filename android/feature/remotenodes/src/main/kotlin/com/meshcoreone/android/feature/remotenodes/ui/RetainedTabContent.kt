// AndroidOnly: WP-313 Keeps a visited management tab composed so its terminal state survives tab switches.
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/**
 * Composes [content] on first selection and keeps it composed while another tab is shown, so state
 * remembered inside it lives until this route leaves composition (the actual session disposal).
 * An unselected tab is collapsed to zero size, clipped and removed from accessibility; it receives
 * no pointer or focus input.
 */
@Composable
internal fun RetainedTabContent(
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var visited by remember { mutableStateOf(selected) }
    if (selected && !visited) visited = true
    if (!selected && !visited) return
    Box(
        if (selected) modifier else Modifier.size(0.dp).clipToBounds().clearAndSetSemantics {},
    ) { content() }
}
