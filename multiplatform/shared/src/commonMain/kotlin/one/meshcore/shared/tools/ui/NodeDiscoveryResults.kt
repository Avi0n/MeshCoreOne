package one.meshcore.shared.tools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.meshcore.shared.avatar.AvatarCategory
import one.meshcore.shared.contact.ui.DiscoveryAddTrailing
import one.meshcore.shared.contact.ui.NodeAvatar
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_node_discovery_no_results
import one.meshcore.shared.resources.tools_node_discovery_no_results_description
import one.meshcore.shared.resources.tools_node_discovery_repeaters
import one.meshcore.shared.resources.tools_node_discovery_rssi
import one.meshcore.shared.resources.tools_node_discovery_scan_prompt
import one.meshcore.shared.resources.tools_node_discovery_scan_prompt_description
import one.meshcore.shared.resources.tools_node_discovery_sensors
import one.meshcore.shared.resources.tools_node_discovery_snr_down
import one.meshcore.shared.resources.tools_node_discovery_snr_up
import one.meshcore.shared.resources.tools_node_discovery_type_repeater
import one.meshcore.shared.resources.tools_node_discovery_type_sensor
import one.meshcore.shared.tools.NodeDiscoveryFilter
import one.meshcore.shared.tools.NodeDiscoveryResult
import one.meshcore.shared.tools.NodeDiscoveryState
import one.meshcore.shared.ui.IconLeadEmptyColumn
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

// The scan-result area: the pre-scan prompt, the post-scan empty state, or the hit list. Split from the
// screen body so the filter/scan chrome and the result-state branches stay separately legible.
@Composable
internal fun ColumnScope.DiscoveryResults(
    state: NodeDiscoveryState,
    onFilterSelect: (NodeDiscoveryFilter) -> Unit,
    onAdd: (NodeDiscoveryResult) -> Unit,
) {
    when {
        !state.hasScannedOnce && !state.isScanning && state.sortedResults.isEmpty() -> {
            FilterRow(
                selected = state.filter,
                enabled = !state.isScanning,
                onSelect = onFilterSelect,
            )
            EmptyBlock(
                title = formattedStringResource(
                    Res.string.tools_node_discovery_scan_prompt,
                    filterTitle(state.filter),
                ),
                description = formattedStringResource(
                    Res.string.tools_node_discovery_scan_prompt_description,
                    filterTitle(state.filter),
                ),
                icon = AppIcons.Search,
            )
        }
        state.hasScannedOnce && !state.isScanning && state.sortedResults.isEmpty() -> {
            FilterRow(
                selected = state.filter,
                enabled = !state.isScanning,
                onSelect = onFilterSelect,
            )
            EmptyBlock(
                title = formattedStringResource(
                    Res.string.tools_node_discovery_no_results,
                    filterTitle(state.filter),
                ),
                description = formattedStringResource(
                    Res.string.tools_node_discovery_no_results_description,
                    filterTitle(state.filter),
                ),
                icon = AppIcons.Search,
            )
        }
        else ->
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item {
                    FilterRow(
                        selected = state.filter,
                        enabled = !state.isScanning,
                        onSelect = onFilterSelect,
                    )
                }
                items(state.sortedResults, key = { it.publicKeyHex + it.scanFilter.name }) { row ->
                    DiscoveryHitRow(
                        result = row,
                        isAdded = state.isAdded(row),
                        isAdding = state.addingPublicKeyHex == row.publicKeyHex,
                        onAdd = if (ContactType.fromRaw(row.nodeType) != null) {
                            { onAdd(row) }
                        } else {
                            null
                        },
                    )
                }
            }
    }
}

@Composable
private fun FilterRow(selected: NodeDiscoveryFilter, enabled: Boolean, onSelect: (NodeDiscoveryFilter) -> Unit) {
    val filters = NodeDiscoveryFilter.entries
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        filters.forEachIndexed { index, filter ->
            SegmentedButton(
                selected = filter == selected,
                onClick = { if (enabled) onSelect(filter) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = filters.size),
            ) {
                Text(filterTitle(filter), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun filterTitle(filter: NodeDiscoveryFilter): String = when (filter) {
    NodeDiscoveryFilter.REPEATERS -> stringResource(Res.string.tools_node_discovery_repeaters)
    NodeDiscoveryFilter.SENSORS -> stringResource(Res.string.tools_node_discovery_sensors)
}

@Composable
private fun DiscoveryHitRow(result: NodeDiscoveryResult, isAdded: Boolean, isAdding: Boolean, onAdd: (() -> Unit)?) {
    val typeLabel = when (result.scanFilter) {
        NodeDiscoveryFilter.REPEATERS -> stringResource(Res.string.tools_node_discovery_type_repeater)
        NodeDiscoveryFilter.SENSORS -> stringResource(Res.string.tools_node_discovery_type_sensor)
    }
    ListItem(
        headlineContent = { Text(result.name) },
        leadingContent = { DiscoveryHitAvatar(result.scanFilter) },
        supportingContent = {
            Column {
                Text(
                    text = result.publicKeyHex,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    Text(
                        text = formattedStringResource(
                            Res.string.tools_node_discovery_snr_down,
                            formatOneDecimal(result.snr),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        text = formattedStringResource(
                            Res.string.tools_node_discovery_snr_up,
                            formatOneDecimal(result.snrIn),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        text = formattedStringResource(
                            Res.string.tools_node_discovery_rssi,
                            result.rssi.toString(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            DiscoveryAddTrailing(isAdded = isAdded, isAdding = isAdding, onAdd = onAdd)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DiscoveryHitAvatar(filter: NodeDiscoveryFilter) {
    when (filter) {
        NodeDiscoveryFilter.REPEATERS -> NodeAvatar(AvatarCategory.REPEATER, DISCOVERY_HIT_AVATAR_SIZE)
        NodeDiscoveryFilter.SENSORS -> Box(
            modifier = Modifier
                .size(DISCOVERY_HIT_AVATAR_SIZE)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.Sensors,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Fills and centers within the remaining column space so the connect / scan prompts match the centered
// empty-state idiom the other tools use (Noise Floor, RX Log, CLI via PlaceholderPane).
@Composable
internal fun ColumnScope.EmptyBlock(title: String, description: String, icon: ImageVector) {
    Box(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        IconLeadEmptyColumn(
            title = title,
            description = description,
            icon = icon,
            textAlign = TextAlign.Center,
        )
    }
}

private val DISCOVERY_HIT_AVATAR_SIZE = 40.dp

private const val ONE_DECIMAL_SCALE = 10.0

/** One decimal place without JVM-only String.format. */
private fun formatOneDecimal(value: Double): String {
    val scaled = kotlin.math.round(value * ONE_DECIMAL_SCALE) / ONE_DECIMAL_SCALE
    return scaled.toString()
}
