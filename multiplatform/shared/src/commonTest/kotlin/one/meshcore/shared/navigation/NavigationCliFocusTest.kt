package one.meshcore.shared.navigation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationCliFocusTest {
    @Test
    fun tabWidthAndSameSelectionPreserveTheCurrentPresentationClaim() = runTest {
        val navigation = NavigationCoordinator(backgroundScope)
        navigation.selectTool(ToolSelection.CLI)
        assertTrue(navigation.claimCliInitialKeyboardFocus())
        navigation.selectTab(AppTab.CHATS)
        navigation.setSidebarWide(true)
        navigation.selectTab(AppTab.TOOLS)
        navigation.setSidebarWide(false)
        navigation.selectTool(ToolSelection.CLI)
        assertFalse(navigation.claimCliInitialKeyboardFocus())
    }

    @Test
    fun explicitCloseAndFreshOpenReceiveAnotherInitialFocus() = runTest {
        val navigation = NavigationCoordinator(backgroundScope)
        navigation.selectTool(ToolSelection.CLI)
        assertTrue(navigation.claimCliInitialKeyboardFocus())
        navigation.selectTool(null)
        navigation.selectTool(ToolSelection.CLI)
        assertTrue(navigation.claimCliInitialKeyboardFocus())
    }

    @Test
    fun systemBackResetsThePresentationBeforeReopen() = runTest {
        val navigation = NavigationCoordinator(backgroundScope)
        navigation.selectTab(AppTab.TOOLS)
        navigation.selectTool(ToolSelection.CLI)
        assertTrue(navigation.claimCliInitialKeyboardFocus())
        assertTrue(navigation.handleSystemBack())
        navigation.selectTool(ToolSelection.CLI)
        assertTrue(navigation.claimCliInitialKeyboardFocus())
    }

    @Test
    fun activeTabCollapseResetsThePresentationBeforeReopen() = runTest {
        val navigation = NavigationCoordinator(backgroundScope)
        navigation.selectTool(ToolSelection.CLI)
        assertTrue(navigation.claimCliInitialKeyboardFocus())
        navigation.popToRoot(AppTab.TOOLS)
        navigation.selectTool(ToolSelection.CLI)
        assertTrue(navigation.claimCliInitialKeyboardFocus())
    }

    @Test
    fun radioTeardownResetsThePresentationBeforeReopen() = runTest {
        val navigation = NavigationCoordinator(backgroundScope)
        navigation.selectTool(ToolSelection.CLI)
        assertTrue(navigation.claimCliInitialKeyboardFocus())
        navigation.clearPerDeviceSelection()
        navigation.selectTool(ToolSelection.CLI)
        assertTrue(navigation.claimCliInitialKeyboardFocus())
    }

    @Test
    fun anotherToolCannotConsumeTheNextCliPresentationFocus() = runTest {
        val navigation = NavigationCoordinator(backgroundScope)
        assertFalse(navigation.claimCliInitialKeyboardFocus())
        navigation.selectTool(ToolSelection.RX_LOG)
        assertFalse(navigation.claimCliInitialKeyboardFocus())
        navigation.selectTool(ToolSelection.CLI)
        assertTrue(navigation.claimCliInitialKeyboardFocus())
        navigation.selectTool(ToolSelection.RX_LOG)
        navigation.selectTool(ToolSelection.CLI)
        assertTrue(navigation.claimCliInitialKeyboardFocus())
    }
}
