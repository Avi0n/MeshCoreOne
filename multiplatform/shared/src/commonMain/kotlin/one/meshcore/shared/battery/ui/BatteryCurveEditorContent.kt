package one.meshcore.shared.battery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_battery_curve_custom
import one.meshcore.shared.resources.settings_battery_curve_edit_values
import one.meshcore.shared.resources.settings_battery_curve_preset
import one.meshcore.shared.resources.settings_chart_accessibility
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubStatusRow
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private val SECTION_GAP = 8.dp

/**
 * Shared battery-curve editor body: preset picker, discharge chart, Edit Values grid.
 * Used by Node Status (nodePresets) and Settings → Advanced (selectablePresets).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatteryCurveEditorContent(
    availablePresets: List<OCVPreset>,
    selectedPreset: OCVPreset,
    values: List<Int>,
    enabled: Boolean,
    onSelectPreset: (OCVPreset) -> Unit,
    onCommitValues: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
) {
    val presets = remember(availablePresets) { availablePresets }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        ListItem(
            headlineContent = { Text(stringResource(Res.string.settings_battery_curve_preset)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PreferenceHubCardPadding),
            horizontalArrangement = Arrangement.spacedBy(SECTION_GAP),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
        ) {
            presets.forEach { preset ->
                val selected = selectedPreset == preset
                FilterChip(
                    selected = selected,
                    onClick = { onSelectPreset(preset) },
                    enabled = enabled,
                    label = { Text(preset.displayName) },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = AppIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        if (selectedPreset == OCVPreset.CUSTOM) {
            ListItem(
                headlineContent = { Text(stringResource(Res.string.settings_battery_curve_custom)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BatteryCurveChart(
            ocvArray = values,
            accessibilityLabel = stringResource(Res.string.settings_chart_accessibility),
            modifier = Modifier.padding(horizontal = PreferenceHubCardPadding),
        )
        ListItem(
            headlineContent = {
                Text(stringResource(Res.string.settings_battery_curve_edit_values))
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        VoltageFieldsGrid(
            committedValues = values,
            enabled = enabled,
            onCommit = onCommitValues,
            modifier = Modifier.padding(horizontal = PreferenceHubCardPadding),
        )
        errorMessage?.let { msg ->
            PreferenceHubStatusRow(text = msg, color = MaterialTheme.colorScheme.error)
        }
    }
}
