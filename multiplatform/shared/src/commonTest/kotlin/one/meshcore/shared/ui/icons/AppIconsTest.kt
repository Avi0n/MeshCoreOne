package one.meshcore.shared.ui.icons

import one.meshcore.shared.navigation.AppTab
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class AppIconsTest {
    @Test
    fun tabSelectedAndUnselectedIconsDiffer() {
        for (tab in AppTab.entries) {
            assertNotEquals(
                AppIcons.tab(tab, selected = true),
                AppIcons.tab(tab, selected = false),
                "Selected and unselected icons must differ for $tab",
            )
        }
    }

    @Test
    fun tabMappingIsStable() {
        assertSame(AppIcons.ChatsSelected, AppIcons.tab(AppTab.CHATS, selected = true))
        assertSame(AppIcons.Chats, AppIcons.tab(AppTab.CHATS, selected = false))
        assertSame(AppIcons.SettingsSelected, AppIcons.tab(AppTab.SETTINGS, selected = true))
        assertSame(AppIcons.Settings, AppIcons.tab(AppTab.SETTINGS, selected = false))
    }
}
