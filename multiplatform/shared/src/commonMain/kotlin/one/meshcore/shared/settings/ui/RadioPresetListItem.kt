package one.meshcore.shared.settings.ui

import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import one.meshcore.shared.settings.RadioPreset
import one.meshcore.shared.settings.detailLine

/** Preset name, detail line, and leading radio. Callers own the click and connection-hint modifiers. */
@Composable
internal fun RadioPresetListItem(
    preset: RadioPreset,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(preset.name) },
        supportingContent = {
            Text(
                text = preset.detailLine(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}
