package one.meshcore.shared.ui

/** Live-session connect, disconnect, and advert commands. */
interface ConnectionSessionActions {
    /** Connects to a picked BLE device (from the scan picker). */
    suspend fun connect(deviceId: String)

    /** Connects over WiFi/TCP (the onboarding WiFi sheet and the reconnect path). */
    suspend fun connectViaWiFi(host: String, port: Int)

    /** Connects to the bundled simulator/demo radio, when [ShellDependencies.supportsSimulatorConnect]. */
    suspend fun connectSimulator()

    /**
     * Reconnects the last radio: WiFi first from saved methods, then the BLE method handle.
     * @return false when nothing was stored (caller can fall through to scan).
     */
    suspend fun reconnectLastDevice(): Boolean = false

    /** Display name of the last radio, if known (for reconnect UI). */
    suspend fun lastDeviceName(): String? = null

    /**
     * Stands down last-radio BLE reconnect during companion pairing / scan-new (iOS
     * `isPairingFlowActive`). Default no-op for stubs.
     */
    fun setPairingInProgress(inProgress: Boolean) = Unit

    /** iOS `pairNewDevice` stamps `.wantsConnection()` before ASK. Default no-op for stubs. */
    suspend fun noteWantsConnection() = Unit

    /** Explicitly disconnects; persists the user-disconnected bit so nothing auto-reconnects. */
    suspend fun disconnect()

    /** Releases the live radio for pairing while retaining its target for cancellation recovery. */
    suspend fun disconnectForPairing() = disconnect()

    /** Sends a zero-hop (`flood=false`) or flood (`flood=true`) advertisement over the live session. */
    suspend fun sendAdvert(flood: Boolean): one.meshcore.shared.navigation.SendAdvertShortcutOutcome
}
