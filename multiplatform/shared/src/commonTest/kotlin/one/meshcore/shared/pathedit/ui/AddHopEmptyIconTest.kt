package one.meshcore.shared.pathedit.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class AddHopEmptyIconTest {
    @Test
    fun searchEmptyUsesMagnifyingGlass() {
        assertEquals(AppIcons.Search, addHopEmptyIcon(query = "ridge"))
    }

    @Test
    fun filterEmptyUsesAntennaSlash() {
        assertEquals(AppIcons.CellTowerOff, addHopEmptyIcon(query = ""))
        assertEquals(PATH_EDIT_EMPTY_ICON, addHopEmptyIcon(query = ""))
    }

    @Test
    fun maxHopsUsesCheckCircle() {
        assertEquals(AppIcons.CheckCircle, ADD_HOP_MAX_HOPS_ICON)
    }
}
