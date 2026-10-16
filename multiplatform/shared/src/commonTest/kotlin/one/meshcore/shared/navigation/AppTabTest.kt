package one.meshcore.shared.navigation

import one.meshcore.shared.layout.SidebarVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppTabTest {
    @Test
    fun rawIndicesArePinnedToTheSelectionIntegers() {
        assertEquals(0, AppTab.CHATS.index)
        assertEquals(1, AppTab.NODES.index)
        assertEquals(2, AppTab.MAP.index)
        assertEquals(3, AppTab.TOOLS.index)
        assertEquals(4, AppTab.SETTINGS.index)
    }

    @Test
    fun fromIndexResolvesEachTabAndFallsBackToChats() {
        for (tab in AppTab.entries) {
            assertEquals(tab, AppTab.fromIndex(tab.index))
        }
        assertEquals(AppTab.CHATS, AppTab.fromIndex(99))
        assertEquals(AppTab.CHATS, AppTab.fromIndex(-1))
    }

    @Test
    fun mapCollapsesToDetailOnlyAndOtherSectionsToContentDetail() {
        assertEquals(SidebarVisibility.DetailOnly, AppTab.MAP.collapsedSidebarVisibility)
        for (tab in AppTab.entries.filter { it != AppTab.MAP }) {
            assertEquals(SidebarVisibility.ContentDetail, tab.collapsedSidebarVisibility)
        }
    }

    @Test
    fun onlyChatsNodesAndMapShowTheConnectionIndicator() {
        assertTrue(AppTab.CHATS.showsConnectionIndicator)
        assertTrue(AppTab.NODES.showsConnectionIndicator)
        assertTrue(AppTab.MAP.showsConnectionIndicator)
        assertFalse(AppTab.TOOLS.showsConnectionIndicator)
        assertFalse(AppTab.SETTINGS.showsConnectionIndicator)
    }
}
