package one.meshcore.shared.map.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.map.MapAdvancedFilterDimension
import one.meshcore.shared.map.MapFilterHost
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.map.activeAdvancedDimensions
import one.meshcore.shared.map.clear
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.map_filters_edit_hint
import one.meshcore.shared.resources.map_filters_remove
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private val ACTIVE_FILTER_GAP = 8.dp

@Composable
internal fun MapActiveFiltersBar(filter: MapFilterState, onChange: (MapFilterState) -> Unit, onEdit: () -> Unit) {
    val editHint = stringResource(Res.string.map_filters_edit_hint)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ACTIVE_FILTER_GAP),
    ) {
        filter.activeAdvancedDimensions(MapFilterHost.MAIN_MAP).forEach { dimension ->
            val label = when (dimension) {
                MapAdvancedFilterDimension.LAST_HEARD -> filter.lastHeard.label()
                MapAdvancedFilterDimension.HOPS -> filter.hops.label()
            }
            val removeLabel = formattedStringResource(Res.string.map_filters_remove, label)
            InputChip(
                selected = true,
                onClick = onEdit,
                label = { Text(label) },
                modifier = Modifier.semantics { onClick(label = editHint, action = null) },
                trailingIcon = {
                    IconButton(onClick = { onChange(filter.clear(dimension)) }) {
                        Icon(AppIcons.Close, removeLabel)
                    }
                },
            )
        }
    }
}
