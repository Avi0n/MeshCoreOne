package one.meshcore.shared.ui.menu

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Themed Material 3 dropdown shell. Forwards [MenuDefaults] so shape / container / elevation stay
 * centralized when expressive menu tokens become available on the CMP BOM.
 */
@Composable
fun McDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = MenuDefaults.shape,
        containerColor = MenuDefaults.containerColor,
        tonalElevation = MenuDefaults.TonalElevation,
        shadowElevation = MenuDefaults.ShadowElevation,
        content = content,
    )
}

/** Non-interactive section label inside a dropdown (filters, sort groups). */
@Composable
fun McMenuSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            horizontal = MENU_SECTION_H_PADDING,
            vertical = MENU_SECTION_V_PADDING,
        ),
    )
}

@Composable
fun McMenuDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier.padding(vertical = MENU_DIVIDER_V_PADDING))
}

// Align with MenuDefaults.DropdownMenuItemContentPadding horizontal (12.dp).
private val MENU_SECTION_H_PADDING = 12.dp
private val MENU_SECTION_V_PADDING = 8.dp
private val MENU_DIVIDER_V_PADDING = 4.dp
