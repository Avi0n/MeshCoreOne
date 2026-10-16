package one.meshcore.shared.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfirmAddContactDisconnectedIconTest {
    @Test
    fun disconnectedUsesAntennaSlash() {
        assertEquals(AppIcons.CellTowerOff, CONFIRM_ADD_CONTACT_DISCONNECTED_ICON)
    }
}
