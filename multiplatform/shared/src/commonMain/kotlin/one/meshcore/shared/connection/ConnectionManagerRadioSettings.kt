package one.meshcore.shared.connection

import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.contact.AutoAddConfigBits
import one.meshcore.shared.contact.clearNodeStorageFull
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.settings.NodesAutoAddDraft
import one.meshcore.shared.settings.NodesAutoAddSnapshot
import one.meshcore.shared.settings.manualAddContactsForWire
import one.meshcore.shared.settings.toAutoAddConfigBitmask
import one.meshcore.shared.transport.TcpTransportException

/**
 * Applies auto-add mode/types via setOtherParams + setAutoAddConfig (v1.12+), then updates the Device
 * row. The two commands are separate wire writes with separate firmware floors, so each proves itself
 * against the radio's own read-back before its half of the row is persisted.
 */
suspend fun ConnectionManager.applyNodesAutoAdd(draft: NodesAutoAddDraft) {
    val manualAdd = draft.manualAddContactsForWire()
    writeOtherParams { existing, info ->
        ConnectionManager.OtherParamsWrite(
            params = OtherParams.live(existing, info).copy(manualAddContacts = manualAdd),
            owned = OtherParams::describeManualAddContacts,
            persist = { it.copy(manualAddContacts = manualAdd) },
        )
    }
    writeAutoAddConfig(draft)
}

/**
 * Writes the auto-add bitmask / max-hops policy on firmware that has the command, proves the radio
 * adopted it, then persists it. Max hops is only compared under its own firmware floor: firmware
 * between the two floors accepts the command but reports no hop limit, and comparing one it never
 * claimed to support would fail a write that landed.
 */
internal suspend fun ConnectionManager.writeAutoAddConfig(draft: NodesAutoAddDraft) {
    val liveSession = session ?: error("No radio connected")
    val deviceId = lastConnectionStore?.deviceId() ?: error("No radio connected")
    val existing = database.deviceDao().getById(deviceId) ?: error("No device row")
    val snapshot = NodesAutoAddSnapshot(
        manualAddContacts = existing.manualAddContacts,
        autoAddConfig = existing.autoAddConfig,
        autoAddMaxHops = existing.autoAddMaxHops,
        firmwareVersionString = existing.firmwareVersionString,
    )
    if (!snapshot.supportsAutoAddConfig) return
    val bitmask = draft.toAutoAddConfigBitmask()
    val maxHops = draft.autoAddMaxHops
    liveSession.setAutoAddConfig(bitmask, maxHops)
    val reported = liveSession.getAutoAddConfig()
    val hopsMismatch = snapshot.supportsAutoAddMaxHops && reported.maxHops != maxHops
    if (reported.bitmask != bitmask || hopsMismatch) {
        throw AutoAddConfigVerificationFailed(
            expected = "bitmask=$bitmask, maxHops=$maxHops",
            actual = "bitmask=${reported.bitmask}, maxHops=${reported.maxHops}",
        )
    }
    persistDeviceSettingsAfterRadioAck(logger, FAILED_PERSIST_AUTO_ADD_CONFIG) {
        database.deviceDao().upsert(
            existing.copy(
                autoAddConfig = bitmask,
                autoAddMaxHops = if (snapshot.supportsAutoAddMaxHops) maxHops else existing.autoAddMaxHops,
            ),
        )
    }
    if (bitmask and AutoAddConfigBits.OVERWRITE_OLDEST != 0) {
        services?.contactService?.clearNodeStorageFull()
    }
}

/**
 * Connection-facing device fields for Settings → Connection (BLE PIN, WiFi reachability, transport).
 * `null` when disconnected or the Device row is missing.
 */
suspend fun ConnectionManager.connectionSettingsSnapshot(): ConnectionSettingsSnapshot? {
    val deviceId = lastConnectionStore?.deviceId() ?: return null
    val device = database.deviceDao().getById(deviceId) ?: return null
    val wifi = device.connectionMethods.filterIsInstance<ConnectionMethod.WiFi>().firstOrNull()
    val hasBluetooth = device.connectionMethods.any { it is ConnectionMethod.Bluetooth }
    return ConnectionSettingsSnapshot(
        transportType = currentTransportType,
        blePin = device.blePin,
        wifiHost = wifi?.host,
        wifiPort = wifi?.port,
        hasBluetoothMethod = hasBluetooth,
        hasWifiMethod = wifi != null,
    )
}

/**
 * Writes a BLE pairing PIN to the live radio, reboots to commit it, and persists it on the Device
 * row. Requires a connected session. The PIN command only writes RAM; the firmware applies it on
 * reboot, so the reboot is load-bearing (a written-but-unrebooted PIN never takes effect). The
 * radio drops the link before acking the reboot, so the expected disconnect is swallowed. Pass
 * [ConnectionSettingsSnapshot.BLE_PIN_FIRMWARE_DEFAULT] to revert a custom PIN to the factory
 * default. Mirrors iOS `BluetoothSection.setCustomPin` / `applyPendingPinType`.
 */
suspend fun ConnectionManager.setBlePin(pin: Long) {
    val liveSession = session ?: error("No radio connected")
    val deviceId = lastConnectionStore?.deviceId() ?: error("No radio connected")
    val existing = database.deviceDao().getById(deviceId) ?: error("No device row")
    liveSession.setDevicePin(pin)
    rebootAllowingExpectedDisconnect(liveSession)
    persistDeviceSettingsAfterRadioAck(logger, FAILED_PERSIST_BLE_PIN) {
        database.deviceDao().upsert(existing.copy(blePin = pin))
    }
}

/**
 * Reboots the companion radio. Firmware drops the link before acking, so a timeout or
 * connection-lost is the expected success (iOS `DeviceActionsSection` swallows
 * `BLEError.operationTimeout`). A real device error still throws.
 */
suspend fun ConnectionManager.rebootDevice() {
    val liveSession = session ?: error("No radio connected")
    rebootAllowingExpectedDisconnect(liveSession)
}

/**
 * Sends reboot and swallows the disconnect the radio produces before the write callback.
 * Used by PIN commit and Settings → Advanced reboot.
 */
internal suspend fun ConnectionManager.rebootAllowingExpectedDisconnect(liveSession: MeshSession) {
    try {
        liveSession.reboot()
    } catch (e: MeshBridgeException) {
        if (e.code != BridgeErrorCode.TIMEOUT && e.code != BridgeErrorCode.CONNECTION_LOST) throw e
    }
}

/**
 * Updates the stored WiFi host/port for the last (or live) device. Merges by transport type so BLE
 * descriptors are preserved. A live WiFi session is torn down and reconnected at the new address
 * (iOS `WiFiEditSheet.saveChanges`).
 */
suspend fun ConnectionManager.updateWifiEndpoint(host: String, port: Int) {
    val host = host.trim()
    if (host.isEmpty()) throw TcpTransportException.InvalidHost()
    val deviceId = lastConnectionStore?.deviceId() ?: error("No device row")
    val existing = database.deviceDao().getById(deviceId) ?: error("No device row")
    val previous = existing.connectionMethods.filterIsInstance<ConnectionMethod.WiFi>().firstOrNull()
    val next = ConnectionMethod.WiFi(host = host, port = port, displayName = previous?.displayName)
    persistDeviceSettingsAfterRadioAck(logger, FAILED_PERSIST_WIFI_ENDPOINT) {
        database.deviceDao().upsert(
            existing.copy(connectionMethods = mergeConnectionMethods(existing.connectionMethods, listOf(next))),
        )
    }
    val liveWifi = currentTransportType == TransportType.WIFI && state.value.isLinkUp
    if (!liveWifi) return
    wifiHost = host
    wifiPort = port
    disconnect(DisconnectReason.WIFI_ADDRESS_CHANGE)
    connectViaWiFi(host, port, forceFullSync = true)
}
