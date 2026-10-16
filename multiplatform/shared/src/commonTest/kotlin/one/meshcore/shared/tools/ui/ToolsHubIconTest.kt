package one.meshcore.shared.tools.ui

import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolsHubIconTest {
    @Test
    fun hubIconsMatchIosToolSelectionGlyphs() {
        assertEquals(AppIcons.Timeline, toolIcon(ToolSelection.TRACE_PATH))
        assertEquals(AppIcons.Visibility, toolIcon(ToolSelection.LINE_OF_SIGHT))
        assertEquals(AppIcons.GraphicEq, toolIcon(ToolSelection.RX_LOG))
        assertEquals(AppIcons.Sensors, toolIcon(ToolSelection.NOISE_FLOOR))
        assertEquals(AppIcons.CellTower, toolIcon(ToolSelection.NODE_DISCOVERY))
        assertEquals(AppIcons.Terminal, toolIcon(ToolSelection.CLI))
    }
}
