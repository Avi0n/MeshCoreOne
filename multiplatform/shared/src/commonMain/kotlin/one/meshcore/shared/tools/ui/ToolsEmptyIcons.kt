package one.meshcore.shared.tools.ui

import androidx.compose.ui.graphics.vector.ImageVector
import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.ui.icons.AppIcons

/** iOS CLI disconnected: `terminal`; other radio tools: antenna slash. */
internal fun toolsDisconnectedIcon(tool: ToolSelection): ImageVector =
    if (tool == ToolSelection.CLI) AppIcons.Terminal else AppIcons.CellTowerOff
