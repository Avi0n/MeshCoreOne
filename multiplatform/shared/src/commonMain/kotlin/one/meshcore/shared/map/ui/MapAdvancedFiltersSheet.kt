@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package one.meshcore.shared.map.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.map.MapFilterHost
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.map.MapHopRange
import one.meshcore.shared.map.MapLastHeardRange
import one.meshcore.shared.map.activeAdvancedDimensions
import one.meshcore.shared.map.resetAdvanced
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_close
import one.meshcore.shared.resources.map_filters_hops_explanation
import one.meshcore.shared.resources.map_filters_hops_header
import one.meshcore.shared.resources.map_filters_last_heard_any_time
import one.meshcore.shared.resources.map_filters_last_heard_explanation
import one.meshcore.shared.resources.map_filters_last_heard_header
import one.meshcore.shared.resources.map_filters_maximum
import one.meshcore.shared.resources.map_filters_minimum
import one.meshcore.shared.resources.map_filters_presets
import one.meshcore.shared.resources.map_filters_reset
import one.meshcore.shared.resources.map_filters_title
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.modalSheetExpandedValues
import org.jetbrains.compose.resources.stringResource

private val FILTER_SHEET_PADDING = 16.dp
private val FILTER_SECTION_GAP = 12.dp
private val FILTER_PRESET_GAP = 8.dp

/** Inclusive local node filters, applied live while the map stays visible behind the sheet. */
@Composable
internal fun MapAdvancedFiltersSheet(
    filter: MapFilterState,
    onChange: (MapFilterState) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val sheetState =
        rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = modalSheetExpandedValues)
    val dismiss = {
        focusManager.clearFocus()
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(
                rememberScrollState(),
            ).padding(FILTER_SHEET_PADDING),
            verticalArrangement = Arrangement.spacedBy(FILTER_SECTION_GAP),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(Res.string.map_filters_title), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = dismiss) {
                    Icon(AppIcons.Close, stringResource(Res.string.localizable_common_close))
                }
            }
            TextButton(
                onClick = {
                    focusManager.clearFocus()
                    onChange(filter.resetAdvanced())
                },
                enabled = filter.activeAdvancedDimensions(MapFilterHost.MAIN_MAP).isNotEmpty(),
            ) { Text(stringResource(Res.string.map_filters_reset)) }
            MapLastHeardFields(filter.lastHeard) { onChange(filter.copy(lastHeard = it)) }
            HorizontalDivider()
            MapHopFields(filter.hops) { onChange(filter.copy(hops = it)) }
        }
    }
}

@Composable
private fun MapLastHeardFields(range: MapLastHeardRange, onChange: (MapLastHeardRange) -> Unit) {
    Text(stringResource(Res.string.map_filters_last_heard_header), style = MaterialTheme.typography.titleMedium)
    MapFilterPresets(MapLastHeardRange.PRESETS, range, label = {
        it.maxAge?.compactLabel() ?: stringResource(Res.string.map_filters_last_heard_any_time)
    }, onChange)
    MapDurationBoundField(stringResource(Res.string.map_filters_minimum), range.minAge) {
        onChange(range.withMinAge(it))
    }
    MapDurationBoundField(stringResource(Res.string.map_filters_maximum), range.maxAge) {
        onChange(range.withMaxAge(it))
    }
    Text(range.label(summary = true), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(Res.string.map_filters_last_heard_explanation),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MapHopFields(range: MapHopRange, onChange: (MapHopRange) -> Unit) {
    Text(stringResource(Res.string.map_filters_hops_header), style = MaterialTheme.typography.titleMedium)
    MapFilterPresets(MapHopRange.PRESETS, range, { it.presetLabel() }, onChange)
    MapIntegerBoundField(
        label = stringResource(Res.string.map_filters_minimum),
        value = range.min,
        range = MapHopRange.HOP_DOMAIN,
        onCommit = { onChange(range.withMin(it ?: MapHopRange.HOP_DOMAIN.first)) },
        modifier = Modifier.fillMaxWidth(),
    )
    MapIntegerBoundField(
        label = stringResource(Res.string.map_filters_maximum),
        value = range.max,
        range = MapHopRange.HOP_DOMAIN,
        onCommit = { onChange(range.withMax(it)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(range.label(summary = true), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(Res.string.map_filters_hops_explanation),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun <T> MapFilterPresets(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onChange: (T) -> Unit,
) {
    val presetsLabel = stringResource(Res.string.map_filters_presets)
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).semantics {
            contentDescription =
                presetsLabel
        },
        horizontalArrangement = Arrangement.spacedBy(FILTER_PRESET_GAP),
    ) {
        values.forEach { preset ->
            FilterChip(
                selected = preset == selected,
                onClick = {
                    focusManager.clearFocus()
                    onChange(preset)
                },
                label = { Text(label(preset)) },
            )
        }
    }
}
