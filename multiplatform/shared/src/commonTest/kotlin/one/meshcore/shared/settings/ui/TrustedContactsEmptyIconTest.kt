package one.meshcore.shared.settings.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class TrustedContactsEmptyIconTest {
    @Test
    fun filteredEmptyUsesMagnifyingGlass() {
        assertEquals(AppIcons.Search, trustedContactsEmptyIcon(isFiltered = true))
    }

    @Test
    fun unfilteredEmptyUsesPersonSlashAnalog() {
        assertEquals(AppIcons.GroupOff, trustedContactsEmptyIcon(isFiltered = false))
    }

    @Test
    fun disconnectedEmptyUsesTheCellTowerOffGlyph() {
        assertEquals(AppIcons.CellTowerOff, trustedContactsEmptyIcon(isFiltered = false, disconnected = true))
    }
}
