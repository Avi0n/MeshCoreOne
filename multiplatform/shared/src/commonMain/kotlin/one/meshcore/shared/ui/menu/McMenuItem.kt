package one.meshcore.shared.ui.menu

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import one.meshcore.shared.ui.icons.AppIcons

/**
 * Icon-led dropdown row with optional selection check and destructive coloring.
 * Prefer a non-null [leadingIcon] for scannability; selection-only rows may omit it.
 */
@Composable
fun McMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    role: McMenuRole = McMenuRole.Default,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val effectiveRole = mcMenuEffectiveRole(role, selected)
    val labelColor = mcMenuLabelColor(effectiveRole)
    val iconColor = mcMenuIconColor(effectiveRole)
    val showCheck = mcMenuShowsCheck(role, selected)
    DropdownMenuItem(
        text = { Text(text = text, color = labelColor) },
        onClick = onClick,
        modifier = modifier,
        leadingIcon = leadingIcon?.let { vector ->
            {
                Icon(imageVector = vector, contentDescription = null, tint = iconColor)
            }
        },
        trailingIcon = if (showCheck) {
            {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = iconColor,
                )
            }
        } else {
            null
        },
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = labelColor,
            leadingIconColor = iconColor,
            trailingIconColor = iconColor,
            disabledTextColor = labelColor.copy(alpha = MC_MENU_DISABLED_ALPHA),
            disabledLeadingIconColor = iconColor.copy(alpha = MC_MENU_DISABLED_ALPHA),
            disabledTrailingIconColor = iconColor.copy(alpha = MC_MENU_DISABLED_ALPHA),
        ),
    )
}
