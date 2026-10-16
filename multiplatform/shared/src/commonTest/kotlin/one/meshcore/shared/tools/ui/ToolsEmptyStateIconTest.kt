package one.meshcore.shared.tools.ui

import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.pathedit.ui.PATH_EDIT_EMPTY_ICON
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolsEmptyStateIconTest {
    @Test
    fun pathEditEmptyUsesAntennaSlash() {
        assertEquals(AppIcons.CellTowerOff, PATH_EDIT_EMPTY_ICON)
    }

    @Test
    fun cliDisconnectedUsesTerminal() {
        assertEquals(AppIcons.Terminal, toolsDisconnectedIcon(ToolSelection.CLI))
        assertEquals(AppIcons.CellTowerOff, toolsDisconnectedIcon(ToolSelection.RX_LOG))
    }
}
