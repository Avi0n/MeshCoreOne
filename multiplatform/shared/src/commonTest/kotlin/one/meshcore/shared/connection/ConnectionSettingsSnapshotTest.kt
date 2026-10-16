package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionSettingsSnapshotTest {
    @Test
    fun wifiPortDraftDefaultsToFirmwarePortWhenUnset() {
        assertEquals("5000", wifiPortDraftText(null))
        assertEquals("4403", wifiPortDraftText(4403))
    }

    @Test
    fun customPinWhenNeitherDefaultNorUnset() {
        val snap = ConnectionSettingsSnapshot(
            transportType = TransportType.BLUETOOTH,
            blePin = 654_321L,
            wifiHost = null,
            wifiPort = null,
            hasBluetoothMethod = true,
            hasWifiMethod = false,
        )
        assertTrue(snap.isCustomBlePin)
    }

    @Test
    fun defaultPinForFirmwareDefaultAndUnset() {
        val defaultPin = ConnectionSettingsSnapshot(
            transportType = TransportType.BLUETOOTH,
            blePin = ConnectionSettingsSnapshot.BLE_PIN_FIRMWARE_DEFAULT,
            wifiHost = null,
            wifiPort = null,
            hasBluetoothMethod = true,
            hasWifiMethod = false,
        )
        assertFalse(defaultPin.isCustomBlePin)
        val unset = defaultPin.copy(blePin = ConnectionSettingsSnapshot.BLE_PIN_UNSET)
        assertFalse(unset.isCustomBlePin)
    }

    @Test
    fun wifiSectionOnlyWhileLiveTransportIsWifi() {
        assertTrue(showsWifiConnectionSettings(TransportType.WIFI))
        assertFalse(showsWifiConnectionSettings(TransportType.BLUETOOTH))
        assertFalse(showsWifiConnectionSettings(null))
    }
}
