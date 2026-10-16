package one.meshcore.shared.ui

import one.meshcore.shared.connection.SavedRadioDevice
import one.meshcore.shared.model.Uuid

/** Saved-radio picker, other-app alerts, and bond commands. */
interface ConnectionSavedDeviceActions {
    /**
     * Saved connectable radios for the multi-device picker (iOS DeviceSelectionSheet).
     * Empty when none are reachable from this phone.
     */
    suspend fun listSavedDevices(): List<SavedRadioDevice> = emptyList()

    /**
     * BLE handles currently connected on this phone’s GATT stack (iOS
     * `systemConnectedPeripheralIDs`). Empty on desktop or when the connect permission is missing.
     */
    fun systemConnectedBleHandles(): Set<String> = emptySet()

    /**
     * Stable row id of the last radio this phone connected, for the iOS last-device adopt
     * exception on “Connected elsewhere”.
     */
    suspend fun lastConnectedDeviceId(): Uuid? = null

    /** Presents iOS `Could Not Connect` when another app already holds the radio. */
    fun presentConnectedElsewhereAlert(deviceId: String) = Unit

    /** Connects a saved radio (WiFi preferred, else BLE handle). */
    suspend fun connectSavedDevice(device: SavedRadioDevice) {
        error("Saved-device connect is not available")
    }

    /** Removes a saved radio from the picker (clears its connection methods). */
    suspend fun deleteSavedDevice(deviceId: Uuid) = Unit

    /** Dismisses the current connection/pairing alert. */
    fun dismissAlert()

    /** Asks the shell to re-open the connect sheet after Remove-and-Try-Again. */
    fun requestConnectPicker() = Unit

    /**
     * Whether Remove-and-Try-Again can drop the OS Bluetooth bond. iOS `hasSystemPairingRegistry`;
     * Android Companion Device Manager `removeBond` is API 36+. Default false so a host that
     * cannot drop the bond uses the Bluetooth-settings copy.
     */
    fun canDropOsBond(): Boolean = false
}
