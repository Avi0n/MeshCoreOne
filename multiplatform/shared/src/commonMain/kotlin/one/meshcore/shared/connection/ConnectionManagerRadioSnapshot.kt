package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.NodesAutoAddSnapshot
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.settings.RadioPresets

/**
 * Live LoRa parameters (kHz / Hz units) plus the firmware and capability read-outs from the
 * connected Device row. `null` when disconnected or the row is not yet persisted.
 */
suspend fun ConnectionManager.radioSnapshot(): RadioDeviceSnapshot? {
    val deviceId = lastConnectionStore?.deviceId() ?: return null
    val device = database.deviceDao().getById(deviceId) ?: return null
    val storedId = device.appliedRadioPresetID ?: appliedRadioPresetStore?.get(device.radioID)
    val appliedId = RadioPresets.retainedAppliedPresetID(
        rf = RadioPresets.RadioPresetRf(
            frequencyKHz = device.frequency,
            bandwidthHz = device.bandwidth,
            spreadingFactor = device.spreadingFactor,
            codingRate = device.codingRate,
        ),
        preferredID = storedId,
        clientRepeat = device.clientRepeat,
    )
    persistAppliedRadioPresetId(device, appliedId)
    return RadioDeviceSnapshot(
        frequencyKHz = device.frequency,
        bandwidthHz = device.bandwidth,
        spreadingFactor = device.spreadingFactor,
        codingRate = device.codingRate,
        txPower = device.txPower,
        maxTxPower = device.maxTxPower,
        firmwareVersion = device.firmwareVersion,
        firmwareVersionString = device.firmwareVersionString,
        manufacturerName = device.manufacturerName,
        buildDate = device.buildDate,
        maxContacts = device.maxContacts,
        maxChannels = device.maxChannels,
        clientRepeat = device.clientRepeat,
        preRepeatFrequencyKHz = device.preRepeatFrequency,
        appliedRadioPresetID = appliedId,
    )
}

/**
 * The connected Device row's id — the live-connection handle axis, never the data partition key.
 * Scopes the per-radio location preferences, which key off the same id the Swift
 * `DevicePreferenceStore` uses. `null` when no device row has been recorded.
 */
suspend fun ConnectionManager.connectedDeviceRowId(): Uuid? = lastConnectionStore?.deviceId()

/**
 * Whether the connected radio is in repeat mode (BLE menu repeat-mode indicator). `false` when
 * disconnected or the Device row is missing, mirroring iOS `device.clientRepeat`.
 */
suspend fun ConnectionManager.clientRepeat(): Boolean {
    val deviceId = lastConnectionStore?.deviceId() ?: return false
    return database.deviceDao().getById(deviceId)?.clientRepeat ?: false
}

/**
 * Auto-add policy fields for Settings → Advanced → Nodes. `null` when disconnected or no Device row.
 */
suspend fun ConnectionManager.nodesAutoAddSnapshot(): NodesAutoAddSnapshot? {
    val deviceId = lastConnectionStore?.deviceId() ?: return null
    val device = database.deviceDao().getById(deviceId) ?: return null
    return NodesAutoAddSnapshot(
        manualAddContacts = device.manualAddContacts,
        autoAddConfig = device.autoAddConfig,
        autoAddMaxHops = device.autoAddMaxHops,
        firmwareVersionString = device.firmwareVersionString,
    )
}
