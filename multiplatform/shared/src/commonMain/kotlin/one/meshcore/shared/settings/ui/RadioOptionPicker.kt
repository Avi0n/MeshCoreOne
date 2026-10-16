package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McDropdownMenu
import one.meshcore.shared.ui.menu.McMenuItem

/**
 * A menu-style picker over a fixed LoRa option set, the Material analogue of the iOS
 * `.pickerStyle(.menu)` rows. Keeping the options in a menu is what stops an off-grid value reaching
 * the radio in the first place, on the local radio and on a remote node alike.
 */
@Composable
internal fun <T> RadioOptionPicker(
    label: String,
    selectedLabel: String,
    selectedAccessibilityLabel: String,
    accessibilityHint: String,
    options: List<T>,
    optionLabel: (T) -> String,
    optionAccessibilityLabel: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    leadingIcon: ImageVector? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                if (leadingIcon == null) {
                    Text(label)
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PICKER_LEADING_ICON_GAP),
                    ) {
                        Icon(
                            imageVector = leadingIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(PICKER_LEADING_ICON_DP.dp),
                        )
                        Text(label)
                    }
                }
            },
            supportingContent = {
                Text(selectedLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingContent = {
                Icon(AppIcons.ExpandMore, contentDescription = null)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = true }
                // A bare "250" or "9" tells a screen-reader user nothing about the unit or what the value
                // costs them, so the row speaks the value with its unit and what changing it does.
                .semantics {
                    contentDescription = "$label, $selectedAccessibilityLabel"
                    stateDescription = accessibilityHint
                },
        )
        McDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                val text = optionLabel(option)
                val spoken = optionAccessibilityLabel(option)
                McMenuItem(
                    text = text,
                    enabled = enabled,
                    selected = text == selectedLabel,
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    modifier = Modifier.semantics { contentDescription = spoken },
                )
            }
        }
    }
}

private val PICKER_LEADING_ICON_GAP = 8.dp
private const val PICKER_LEADING_ICON_DP = 18
