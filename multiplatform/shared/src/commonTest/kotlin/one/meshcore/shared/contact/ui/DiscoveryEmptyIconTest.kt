package one.meshcore.shared.contact.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryEmptyIconTest {
    @Test
    fun emptyUsesAntennaOn() {
        assertEquals(AppIcons.CellTower, discoveryEmptyIcon(isSearch = false))
    }

    @Test
    fun searchEmptyUsesMagnifyingGlass() {
        assertEquals(AppIcons.Search, discoveryEmptyIcon(isSearch = true))
    }
}
