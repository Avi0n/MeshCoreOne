package one.meshcore.shared.tools.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_tools_hub_cli_subtitle
import one.meshcore.shared.resources.android_tools_hub_line_of_sight_subtitle
import one.meshcore.shared.resources.android_tools_hub_node_discovery_subtitle
import one.meshcore.shared.resources.android_tools_hub_noise_floor_subtitle
import one.meshcore.shared.resources.android_tools_hub_requires_radio
import one.meshcore.shared.resources.android_tools_hub_rx_log_subtitle
import one.meshcore.shared.resources.android_tools_hub_trace_path_subtitle
import one.meshcore.shared.resources.tools_cli
import one.meshcore.shared.resources.tools_line_of_sight
import one.meshcore.shared.resources.tools_node_discovery
import one.meshcore.shared.resources.tools_noise_floor
import one.meshcore.shared.resources.tools_rx_log
import one.meshcore.shared.resources.tools_title
import one.meshcore.shared.resources.tools_trace_path
import one.meshcore.shared.ui.PreferenceHubList
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolsHubList(
    isRadioReady: Boolean,
    selectedTool: ToolSelection?,
    onSelectTool: (ToolSelection?) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    PreferenceHubList(
        title = stringResource(Res.string.tools_title),
        listState = listState,
        modifier = modifier,
    ) {
        item {
            // Untitled group matches system Settings single-section density (card + list rows).
            PreferenceHubSection {
                ToolSelection.entries.forEach { entry ->
                    // Always open; radio-required tools gate the *run* inside the tool screen.
                    val needsRadioHint = entry.requiresRadio && !isRadioReady
                    ListItem(
                        headlineContent = { Text(toolTitle(entry)) },
                        supportingContent = {
                            Text(
                                text = if (needsRadioHint) {
                                    stringResource(Res.string.android_tools_hub_requires_radio)
                                } else {
                                    toolSupporting(entry)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = toolIcon(entry),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (selectedTool == entry) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectTool(entry) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun toolTitle(tool: ToolSelection): String = when (tool) {
    ToolSelection.TRACE_PATH -> stringResource(Res.string.tools_trace_path)
    ToolSelection.LINE_OF_SIGHT -> stringResource(Res.string.tools_line_of_sight)
    ToolSelection.RX_LOG -> stringResource(Res.string.tools_rx_log)
    ToolSelection.NOISE_FLOOR -> stringResource(Res.string.tools_noise_floor)
    ToolSelection.NODE_DISCOVERY -> stringResource(Res.string.tools_node_discovery)
    ToolSelection.CLI -> stringResource(Res.string.tools_cli)
}

@Composable
private fun toolSupporting(tool: ToolSelection): String = when (tool) {
    ToolSelection.TRACE_PATH -> stringResource(Res.string.android_tools_hub_trace_path_subtitle)
    ToolSelection.LINE_OF_SIGHT -> stringResource(Res.string.android_tools_hub_line_of_sight_subtitle)
    ToolSelection.RX_LOG -> stringResource(Res.string.android_tools_hub_rx_log_subtitle)
    ToolSelection.NOISE_FLOOR -> stringResource(Res.string.android_tools_hub_noise_floor_subtitle)
    ToolSelection.NODE_DISCOVERY -> stringResource(Res.string.android_tools_hub_node_discovery_subtitle)
    ToolSelection.CLI -> stringResource(Res.string.android_tools_hub_cli_subtitle)
}

internal fun toolIcon(tool: ToolSelection): ImageVector = when (tool) {
    ToolSelection.TRACE_PATH -> AppIcons.Timeline
    ToolSelection.LINE_OF_SIGHT -> AppIcons.Visibility
    ToolSelection.RX_LOG -> AppIcons.GraphicEq
    ToolSelection.NOISE_FLOOR -> AppIcons.Sensors
    ToolSelection.NODE_DISCOVERY -> AppIcons.CellTower
    ToolSelection.CLI -> AppIcons.Terminal
}
