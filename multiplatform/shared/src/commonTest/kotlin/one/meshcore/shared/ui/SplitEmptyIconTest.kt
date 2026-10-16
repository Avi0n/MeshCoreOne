package one.meshcore.shared.ui

import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SplitEmptyIconTest {
    @Test
    fun connectRadioEmptyUsesAntennaSlash() {
        assertEquals(AppIcons.CellTowerOff, CONNECT_RADIO_EMPTY_ICON)
    }

    @Test
    fun connectRadioWifiUsesWifiCircleAnalog() {
        assertEquals(AppIcons.Wifi, CONNECT_RADIO_WIFI_ICON)
    }

    @Test
    fun connectRadioScanUsesAntenna() {
        assertEquals(AppIcons.CellTower, CONNECT_RADIO_SCAN_ICON)
    }

    @Test
    fun wideDetailEmptyUsesIosSplitGlyphs() {
        assertEquals(AppIcons.Chat, emptyDetailIcon(AppTab.CHATS))
        assertEquals(AppIcons.Smartphone, emptyDetailIcon(AppTab.NODES))
        assertEquals(AppIcons.ToolsSelected, emptyDetailIcon(AppTab.TOOLS))
        assertEquals(AppIcons.SettingsSelected, emptyDetailIcon(AppTab.SETTINGS))
        assertNull(emptyDetailIcon(AppTab.MAP))
    }
}
