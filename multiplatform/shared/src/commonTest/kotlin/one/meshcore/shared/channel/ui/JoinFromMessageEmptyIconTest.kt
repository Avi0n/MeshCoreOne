package one.meshcore.shared.channel.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class JoinFromMessageEmptyIconTest {
    @Test
    fun missingDeviceUsesAntennaSlash() {
        assertEquals(AppIcons.CellTowerOff, joinFromMessageUnavailableIcon(isMissingDevice = true))
    }

    @Test
    fun noSlotsUsesNumberCircleAnalog() {
        assertEquals(AppIcons.LooksOne, joinFromMessageUnavailableIcon(isMissingDevice = false))
    }
}
