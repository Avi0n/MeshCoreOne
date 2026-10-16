package one.meshcore.shared.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

/**
 * Full-row [secondaryContainer] fill for the selected split-list item, matching Material 3
 * `ListItemDefaults` selected container. Compact lists pass [isSelected] false and draw nothing.
 */
@Composable
fun Modifier.selectedRowHighlight(isSelected: Boolean): Modifier {
    val fill = MaterialTheme.colorScheme.secondaryContainer
    return selectedRowHighlight(isSelected = isSelected, fill = fill)
}

internal fun Modifier.selectedRowHighlight(isSelected: Boolean, fill: Color): Modifier {
    val highlighted = if (!isSelected) this else background(fill)
    return highlighted.semantics { selected = isSelected }
}
