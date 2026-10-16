package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.connection.ConnectionSettingsSnapshot
import one.meshcore.shared.connection.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSettingsChromeTest {

    @Test
    fun successfulNestedEditsRefreshTheDisplayedConnectionSnapshot() {
        val initial = ConnectionSettingsSnapshot(
            transportType = TransportType.BLUETOOTH,
            blePin = ConnectionSettingsSnapshot.BLE_PIN_FIRMWARE_DEFAULT,
            wifiHost = "old.local",
            wifiPort = 4403,
            hasBluetoothMethod = true,
            hasWifiMethod = true,
        )

        assertEquals(654321L, connectionSnapshotAfterBlePinSaved(initial, 654321L)?.blePin)
        assertEquals(
            initial.copy(wifiHost = "new.local", wifiPort = 4404),
            connectionSnapshotAfterWifiSaved(initial, "new.local", 4404),
        )
    }

    @Test
    fun missingWifiEndpointAlwaysCountsAsAChange() {
        assertTrue(connectionWifiHasChanges(null, null, "", ""))
        assertTrue(connectionWifiHasChanges(null, 4403, "radio.local", "4403"))
        assertTrue(connectionWifiHasChanges("radio.local", null, "radio.local", "4403"))
    }

    @Test
    fun wifiDraftMatchesTheSavedEndpointOnlyWhenBothFieldsMatch() {
        assertFalse(connectionWifiHasChanges("radio.local", 4403, "radio.local", "4403"))
        assertTrue(connectionWifiHasChanges("radio.local", 4403, "other.local", "4403"))
        assertTrue(connectionWifiHasChanges("radio.local", 4403, "radio.local", "4404"))
    }

    @Test
    fun cancelledWifiSaveClearsProgressWithoutReportingAnError() = runTest {
        val saving = mutableListOf<Boolean>()
        val reconnecting = mutableListOf<Boolean>()
        val errors = mutableListOf<String?>()
        var saved = false
        launchWifiEndpointSave(
            scope = this,
            save = { _, _ -> throw CancellationException("save cancelled") },
            request = WifiEndpointSave(
                isSaving = false,
                hostDraft = "radio.local",
                portDraft = "4403",
                hasChanges = true,
                invalidHostMessage = "invalid host",
                invalidPortMessage = "invalid port",
                onSavingChange = saving::add,
                onReconnectingChange = reconnecting::add,
                onError = errors::add,
                onSaved = { _, _ -> saved = true },
                settingsErrorText = { "save failed" },
            ),
        )
        runCurrent()

        assertEquals(listOf(true, false), saving)
        assertEquals(listOf(true, false), reconnecting)
        assertEquals(listOf<String?>(null), errors)
        assertFalse(saved)
    }
}
