package one.meshcore.shared.connection

import one.meshcore.shared.meshcore.AutoAddConfig
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.demoteDeviceToGhost
import one.meshcore.shared.settings.NodesAutoAddSnapshot

/**
 * The radio's live auto-add policy, or [stored] when the firmware predates the command or the read
 * fails. Gated on the same firmware check as the Apply-path write, so an unsupported radio is neither
 * queried nor overwritten; without the read the Nodes screen would show an all-off policy the user
 * never chose and Apply would push those zeros back to the radio.
 */
internal suspend fun ConnectionManager.readAutoAddConfig(
    firmwareVersionString: String,
    stored: AutoAddConfig,
): AutoAddConfig {
    val liveSession = session
    if (liveSession == null || !NodesAutoAddSnapshot.supportsAutoAddConfig(firmwareVersionString)) {
        return stored
    }
    return readAutoAddConfigOrStored(logger::warning, stored) { liveSession.getAutoAddConfig() }
}

internal fun ConnectionManager.wifiHandle(host: String, port: Int): String = "$host:$port"

/**
 * Saved radios the connect sheet can reconnect to (WiFi and/or BLE with a recoverable handle).
 * Sorted by most-recently-connected first. Backup shadows without reachability are omitted.
 */
suspend fun ConnectionManager.fetchSavedConnectableDevices(): List<SavedRadioDevice> {
    val lastId = lastConnectionStore?.deviceId()
    val lastHandle = lastConnectionStore?.handle()
    return database.deviceDao().listAll()
        .mapNotNull { entity -> toSavedRadioDevice(entity, lastId, lastHandle) }
        .sortedByDescending { it.lastConnected }
}

/**
 * Connects a [SavedRadioDevice]: WiFi preferred when present, otherwise the BLE transport handle.
 */
suspend fun ConnectionManager.connectSaved(device: SavedRadioDevice) {
    val wifi = device.wifi
    if (wifi != null) {
        connectViaWiFi(wifi.host, wifi.port, forceFullSync = true)
    } else {
        val handle = device.bleHandle ?: error("Saved device is not connectable")
        connect(handle, forceFullSync = true, forceReconnect = true)
    }
}

/**
 * Drops the in-memory and persisted bond stamp for [handle]. Holder-matched: only this handle's
 * shield is cleared. Call after a Danger Zone forget so a later encryption-timeout reconnect
 * cannot reuse the old 6h grace.
 */
suspend fun ConnectionManager.clearBondStamp(handle: String) {
    bondRefreshPersistEpoch++
    bondVerificationEpochSeconds.remove(handle)
    lastConnectionStore?.clearBondVerification(handle)
}

/**
 * Soft-deletes a saved radio row from the picker (keeps partition data until purge). Mirrors iOS
 * `deleteDevice`: latch pairing so a GATT drop during Companion Device Manager `disassociate`
 * cannot reconnect, remove the OS pairing, disconnect the live/in-flight radio, then demote.
 * [DeviceForgetCancelledException] from [removeFromPairingRegistry] leaves the row listed.
 */
suspend fun ConnectionManager.deleteSavedDevice(
    deviceId: Uuid,
    removeFromPairingRegistry: suspend (bleHandle: String) -> Unit = {},
) {
    val existing = database.deviceDao().getById(deviceId) ?: return
    val lastId = readLastConnectedDeviceId(
        readDeviceId = { lastConnectionStore?.deviceId() },
        warning = logger::warning,
        fallback = deviceId,
    )
    val lastHandle = lastConnectionStore?.handle()
    val bleHandle = DeviceSelectionFilter.bleHandle(
        existing.connectionMethods,
        lastHandleIfThisDevice = lastHandle.takeIf { lastId == deviceId },
    )
    val shouldTearDownSession = lastId == deviceId ||
        bleHandle != null &&
        currentDeviceId?.equals(bleHandle, ignoreCase = true) == true
    if (shouldTearDownSession) {
        pairingInProgress = true
    }
    try {
        bleHandle?.let { removeFromPairingRegistry(it) }
        if (shouldTearDownSession) {
            disconnect()
        }
        // Forgetting a radio must stop its bond shield (in-memory grace + persisted slot), holder-matched
        // by the BLE handle so forgetting one radio never destroys another's cross-launch shield. Mirrors
        // Swift's holder-matched bond clear in clear(for:).
        DeviceSelectionFilter.bleHandle(existing.connectionMethods, lastHandleIfThisDevice = null)?.let { handle ->
            clearBondStamp(handle)
        }
        // Remint Device.id so a later radio on this BLE handle cannot inherit the old radioID
        // (iOS deleteDevice → demoteDeviceToGhost). Danger Zone forgetKeepData already does this.
        applyForgetDevicePersist(
            deleteData = false,
            persist = { demoteDeviceToGhost(database, deviceId) },
            logger = logger,
        )
        if (lastId == deviceId) {
            applyLastConnectionPersist(
                persist = { lastConnectionStore?.clear() },
                logger = logger,
                failLine = FAILED_CLEAR_LAST_CONNECTION,
            )
        }
    } finally {
        if (shouldTearDownSession) {
            pairingInProgress = false
            if (wantsConnection) {
                armBleReconnectIfWanted()
            }
        }
    }
}

internal fun ConnectionManager.toSavedRadioDevice(
    entity: DeviceEntity,
    lastId: Uuid?,
    lastHandle: String?,
): SavedRadioDevice? {
    val methods = entity.connectionMethods
    val handle = DeviceSelectionFilter.bleHandle(
        methods = methods,
        lastHandleIfThisDevice = if (entity.id == lastId) lastHandle else null,
    )
    if (!DeviceSelectionFilter.isConnectable(methods, handle)) return null
    return SavedRadioDevice(
        id = entity.id,
        name = entity.nodeName,
        wifi = DeviceSelectionFilter.primaryWifi(methods),
        bleHandle = handle,
        lastConnected = entity.lastConnected,
    )
}
