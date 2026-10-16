package one.meshcore.shared.contact.ui

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_discovery_menu
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `DiscoveryMoreMenu` is an overflow labeled More, not a bare Clear button. */
class DiscoveryMoreMenuTest {
    @Test
    fun moreOverflowUsesTheIosMenuLabelAndIcon() {
        assertEquals(Res.string.contacts_discovery_menu, discoveryMoreMenuLabelRes())
        assertEquals(AppIcons.MoreVert, DISCOVERY_MORE_MENU_ICON)
    }
}
