package one.meshcore.shared.settings.ui

import one.meshcore.shared.connection.TransportType
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionHubIconTest {
    @Test
    fun wifiUsesWifiGlyph() {
        assertEquals(AppIcons.Wifi, connectionHubIcon(TransportType.WIFI))
    }

    @Test
    fun bluetoothUsesBluetoothGlyph() {
        assertEquals(AppIcons.Bluetooth, connectionHubIcon(TransportType.BLUETOOTH))
    }

    @Test
    fun unknownKeepsSync() {
        assertEquals(AppIcons.Sync, connectionHubIcon(null))
    }
}
