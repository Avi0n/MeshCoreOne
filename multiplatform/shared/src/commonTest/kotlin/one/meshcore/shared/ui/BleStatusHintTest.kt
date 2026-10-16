package one.meshcore.shared.ui

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_ble_status_accessibility_hint_connected
import one.meshcore.shared.resources.settings_ble_status_accessibility_hint_disconnected
import kotlin.test.Test
import kotlin.test.assertEquals

class BleStatusHintTest {
    @Test
    fun disconnectedHintIsConnectDevice() {
        assertEquals(
            Res.string.settings_ble_status_accessibility_hint_disconnected,
            bleStatusHintResource(isConnected = false),
        )
    }

    @Test
    fun connectedHintIsConnectionOptions() {
        assertEquals(
            Res.string.settings_ble_status_accessibility_hint_connected,
            bleStatusHintResource(isConnected = true),
        )
    }
}
