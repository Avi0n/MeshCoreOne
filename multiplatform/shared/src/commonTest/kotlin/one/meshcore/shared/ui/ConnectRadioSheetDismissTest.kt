package one.meshcore.shared.ui

import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.SavedRadioDevice
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectRadioSheetDismissTest {
    @Test
    fun changeDeviceWhileReadyDoesNotDismiss() {
        assertFalse(shouldDismissConnectSheetAfterReady(startedConnect = false, sawNonReady = false))
    }

    @Test
    fun aConnectThisSheetStartedDismissesAfterLeavingReady() {
        assertTrue(shouldDismissConnectSheetAfterReady(startedConnect = true, sawNonReady = true))
    }

    @Test
    fun aConnectThatNeverLeftReadyDoesNotDismiss() {
        assertFalse(shouldDismissConnectSheetAfterReady(startedConnect = true, sawNonReady = false))
    }

    @Test
    fun aConnectStillInFlightDoesNotDismissOnReady() {
        assertFalse(
            shouldDismissConnectSheetAfterReady(
                startedConnect = true,
                sawNonReady = true,
                connectInFlight = true,
            ),
        )
    }

    @Test
    fun aDisconnectedSheetAutoStartsBleScan() {
        assertTrue(shouldAutoStartConnectSheetBleScan(DeviceConnectionState.DISCONNECTED))
    }

    @Test
    fun aLiveOrConnectingSheetDoesNotAutoStartBleScan() {
        assertFalse(shouldAutoStartConnectSheetBleScan(DeviceConnectionState.CONNECTING))
        assertFalse(shouldAutoStartConnectSheetBleScan(DeviceConnectionState.CONNECTED))
        assertFalse(shouldAutoStartConnectSheetBleScan(DeviceConnectionState.SYNCING))
        assertFalse(shouldAutoStartConnectSheetBleScan(DeviceConnectionState.READY))
    }

    @Test
    fun aSuccessfulConnectDismissesTheSheet() {
        assertTrue(
            shouldDismissConnectSheetAfterAttempt(success = true, otherApp = false, dismissOnFailure = false),
        )
    }

    @Test
    fun aFailedConnectStaysOpenUnlessToldToDismiss() {
        assertFalse(
            shouldDismissConnectSheetAfterAttempt(success = false, otherApp = false, dismissOnFailure = false),
        )
        assertTrue(
            shouldDismissConnectSheetAfterAttempt(success = false, otherApp = true, dismissOnFailure = false),
        )
        assertTrue(
            shouldDismissConnectSheetAfterAttempt(success = false, otherApp = false, dismissOnFailure = true),
        )
    }

    @Test
    fun emptySavedListShowsNoPairedChromeUntilScan() {
        assertTrue(shouldShowConnectRadioEmptyState(savedCount = 0, showScanSection = false))
        assertFalse(shouldShowConnectRadioEmptyState(savedCount = 0, showScanSection = true))
        assertFalse(shouldShowConnectRadioEmptyState(savedCount = 1, showScanSection = false))
        assertFalse(
            shouldShowConnectRadioEmptyState(
                savedCount = 0,
                showScanSection = false,
                needsSetupCount = 1,
            ),
        )
    }

    @Test
    fun wifiSavedDeviceExposesEditDraft() {
        val wifi = SavedRadioDevice(
            id = Uuid.normalized("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"),
            name = "Kitchen",
            wifi = ConnectionMethod.WiFi("192.168.1.20", 5000, null),
            bleHandle = null,
            lastConnected = 0.0,
        )
        assertEquals("192.168.1.20" to 5000, savedWifiEditDraft(wifi))
    }

    @Test
    fun bleSavedDeviceHasNoWifiEditDraft() {
        val ble = SavedRadioDevice(
            id = Uuid.normalized("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"),
            name = "Pocket",
            wifi = null,
            bleHandle = "AA:BB",
            lastConnected = 0.0,
        )
        assertNull(savedWifiEditDraft(ble))
    }

    @Test
    fun wifiSavedDeviceIsNeverUnreachableWithoutRssi() {
        assertFalse(savedBleDeviceIsUnreachable(connectsViaWiFi = true, rssi = null))
    }

    @Test
    fun bleSavedDeviceWithoutRssiIsUnreachable() {
        assertTrue(savedBleDeviceIsUnreachable(connectsViaWiFi = false, rssi = null))
    }

    @Test
    fun bleSavedDeviceWithRssiIsReachable() {
        assertFalse(savedBleDeviceIsUnreachable(connectsViaWiFi = false, rssi = -70))
    }

    @Test
    fun anUnreachableBleRowCannotBeSelected() {
        assertFalse(savedBleRowIsEnabled(connecting = false, isConnectable = true, unreachable = true))
        assertTrue(savedBleRowIsEnabled(connecting = false, isConnectable = true, unreachable = false))
        assertFalse(savedBleRowIsEnabled(connecting = true, isConnectable = true, unreachable = false))
    }

    @Test
    fun aConnectedElsewhereRowExposesTheOtherAppHint() {
        assertEquals(ELSEWHERE_HINT, savedDeviceElsewhereHint(elsewhere = true, hint = ELSEWHERE_HINT))
        assertNull(savedDeviceElsewhereHint(elsewhere = false, hint = ELSEWHERE_HINT))
    }

    @Test
    fun connectingPreservesTheWifiEditorAndError() {
        val state = wifiEditorState(connecting = true)
        val editor = state.editingWifi

        state.dismissWifiEditorIfIdle()

        assertTrue(state.showWiFi)
        assertSame(editor, state.editingWifi)
        assertEquals("Connection attempt failed", state.error)
    }

    @Test
    fun idleDismissalClearsTheWifiEditorAndRetainsError() {
        val state = wifiEditorState(connecting = false)

        state.dismissWifiEditorIfIdle()

        assertFalse(state.showWiFi)
        assertNull(state.editingWifi)
        assertEquals("Connection attempt failed", state.error)
    }

    @Test
    fun elsewhereSavedRowSpeaksOnlyTheOtherAppPhrase() {
        val speech = savedDeviceSpeech(speechInput(elsewhere = true, unreachable = false, tierWord = "Good"))
        assertEquals("Kitchen is connected elsewhere", speech.status)
        assertEquals(speech.status, speech.label)
    }

    @Test
    fun unreachableSavedRowSpeaksOutOfRangeInsideTheDeviceLabel() {
        val speech = savedDeviceSpeech(speechInput(elsewhere = false, unreachable = true, tierWord = null))
        assertEquals("Out of range", speech.status)
        assertEquals("Kitchen, Out of range", speech.label)
    }

    @Test
    fun aLiveSavedRowSpeaksTheTransportAndSignal() {
        val speech = savedDeviceSpeech(speechInput(elsewhere = false, unreachable = false, tierWord = "Good"))
        assertEquals("Bluetooth, Good", speech.status)
        assertEquals("Kitchen, Bluetooth, Good", speech.label)
    }

    @Test
    fun aSavedRowWithoutASignalWordSpeaksTheTransport() {
        val speech = savedDeviceSpeech(speechInput(elsewhere = false, unreachable = false, tierWord = null))
        assertEquals("Bluetooth", speech.status)
        assertEquals("Kitchen, Bluetooth", speech.label)
    }

    @Test
    fun aScannedRowSpeaksTheSignalWord() {
        assertEquals(
            "Hilltop, Good",
            scannedDeviceSpokenLabel("Hilltop", "Good", "Out of range", "%s, %s"),
        )
    }

    @Test
    fun aScannedRowWithoutAReadingSpeaksOutOfRange() {
        assertEquals(
            "Hilltop, Out of range",
            scannedDeviceSpokenLabel("Hilltop", null, "Out of range", "%s, %s"),
        )
    }
}

private fun speechInput(elsewhere: Boolean, unreachable: Boolean, tierWord: String?) = SavedDeviceSpeechInput(
    elsewhere = elsewhere,
    unreachable = unreachable,
    deviceName = "Kitchen",
    methodLabel = "Bluetooth",
    elsewhereSpoken = "%s is connected elsewhere",
    outOfRange = "Out of range",
    labelTemplate = "%s, %s",
    tierWord = tierWord,
)

private const val ELSEWHERE_HINT = "This device is in use by another app."

private fun wifiEditorState(connecting: Boolean) = ConnectRadioSheetState(initialSawNonReady = true).apply {
    showWiFi = true
    this.connecting = connecting
    error = "Connection attempt failed"
    editingWifi = SavedRadioDevice(
        id = Uuid.normalized("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"),
        name = "Kitchen",
        wifi = ConnectionMethod.WiFi("192.168.1.20", 5000, null),
        bleHandle = null,
        lastConnected = 0.0,
    )
}
