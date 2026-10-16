package one.meshcore.shared.tools.ui

import one.meshcore.shared.navigation.ToolSelection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolsHubDisconnectedChromeTest {
    @Test
    fun onlyCliUsesTheHubDisconnectedPlaceholder() {
        assertTrue(toolPaneShowsDisconnectedPlaceholder(ToolSelection.CLI, isRadioReady = false))
        assertFalse(toolPaneShowsDisconnectedPlaceholder(ToolSelection.CLI, isRadioReady = true))
        assertFalse(toolPaneShowsDisconnectedPlaceholder(ToolSelection.TRACE_PATH, isRadioReady = false))
        assertFalse(toolPaneShowsDisconnectedPlaceholder(ToolSelection.NOISE_FLOOR, isRadioReady = false))
        assertFalse(toolPaneShowsDisconnectedPlaceholder(ToolSelection.RX_LOG, isRadioReady = false))
        assertFalse(toolPaneShowsDisconnectedPlaceholder(ToolSelection.NODE_DISCOVERY, isRadioReady = false))
        assertFalse(toolPaneShowsDisconnectedPlaceholder(ToolSelection.LINE_OF_SIGHT, isRadioReady = false))
    }
}
