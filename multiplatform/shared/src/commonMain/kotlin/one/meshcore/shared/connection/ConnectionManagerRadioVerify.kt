package one.meshcore.shared.connection

import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.settings.RadioPresets

/**
 * Writes the LoRa params (and optional repeat flag) the radio already verified onto the Device row.
 * iOS `updateDevice(from:)` runs at this point, before the separate TX write.
 */
internal suspend fun ConnectionManager.persistVerifiedRadioParams(info: SelfInfo, clientRepeat: Boolean?) {
    val deviceId = lastConnectionStore?.deviceId() ?: return
    val existing = database.deviceDao().getById(deviceId) ?: return
    val updated = existing.copy(
        frequency = (info.radioFrequency * ConnectionManager.KHZ_PER_MHZ).toLong(),
        bandwidth = (info.radioBandwidth * ConnectionManager.HZ_PER_KHZ).toLong(),
        spreadingFactor = info.radioSpreadingFactor,
        codingRate = info.radioCodingRate,
        txPower = info.txPower,
    )
    val persistPrefix = if (clientRepeat != null) {
        FAILED_PERSIST_CLIENT_REPEAT_STATE
    } else {
        FAILED_PERSIST_RADIO_PARAMS
    }
    val persisted = if (clientRepeat != null) updated.copy(clientRepeat = clientRepeat) else updated
    persistDeviceSettingsAfterRadioAck(logger, persistPrefix) {
        database.deviceDao().upsert(persisted)
    }
    dropStaleAppliedPreset(persisted)
}

/**
 * Drops a last-applied catalog id whose RF no longer matches, unless Repeat Mode is on (restore
 * still paints the community name).
 */
internal suspend fun ConnectionManager.dropStaleAppliedPreset(device: DeviceEntity) {
    val retained = RadioPresets.retainedAppliedPresetID(
        rf = RadioPresets.RadioPresetRf(
            frequencyKHz = device.frequency,
            bandwidthHz = device.bandwidth,
            spreadingFactor = device.spreadingFactor,
            codingRate = device.codingRate,
        ),
        preferredID = device.appliedRadioPresetID ?: appliedRadioPresetStore?.get(device.radioID),
        clientRepeat = device.clientRepeat,
    )
    persistAppliedRadioPresetId(device, retained)
}

/**
 * Dual-writes the last-applied catalog id onto the Device row and the prefs cache. Null clears
 * both so a manual LoRa write cannot keep painting a stale community name.
 */
internal suspend fun ConnectionManager.persistAppliedRadioPresetId(device: DeviceEntity, presetId: String?) {
    if (presetId == null) {
        appliedRadioPresetStore?.clear(device.radioID)
    } else {
        appliedRadioPresetStore?.set(device.radioID, presetId)
    }
    if (device.appliedRadioPresetID == presetId) return
    database.deviceDao().upsert(device.copy(appliedRadioPresetID = presetId))
}

/**
 * Writes TX power and proves it stuck, without retuning LoRa parameters. Mirrors iOS
 * `SettingsService.setTxPowerVerified`. A `set radio` of the current params would retune a live mesh
 * just to change power, and a LoRa-only read-back would report success when the power write failed.
 */
suspend fun ConnectionManager.setTxPowerVerified(power: Int) {
    val liveSession = session ?: error("No radio connected")
    liveSession.setTxPower(power)
    val info = liveSession.getSelfInfo()
    if (info.txPower != power) {
        logger.warning(radioTxPowerVerificationDump(power, info.txPower))
        throw RadioParamsVerificationFailed(
            expected = "txPower=$power",
            actual = "txPower=${info.txPower}",
        )
    }
    val deviceId = lastConnectionStore?.deviceId() ?: return
    val existing = database.deviceDao().getById(deviceId) ?: return
    persistDeviceSettingsAfterRadioAck(logger, FAILED_PERSIST_RADIO_PARAMS) {
        database.deviceDao().upsert(existing.copy(txPower = info.txPower))
    }
}

/**
 * Saves the parameters the radio is running now so leaving repeat mode can restore them. Mirrors iOS
 * `ConnectionManager.savePreRepeatSettings`.
 */
internal suspend fun ConnectionManager.savePreRepeatParams() {
    val existing = deviceRow() ?: return
    persistDeviceSettingsAfterRadioAck(logger, FAILED_PERSIST_PRE_REPEAT_SETTINGS) {
        database.deviceDao().upsert(
            existing.copy(
                preRepeatFrequency = existing.frequency,
                preRepeatBandwidth = existing.bandwidth,
                preRepeatSpreadingFactor = existing.spreadingFactor,
                preRepeatCodingRate = existing.codingRate,
            ),
        )
    }
}

/**
 * Drops the saved pre-repeat parameters once they have been restored. Mirrors iOS
 * `ConnectionManager.clearPreRepeatSettings`.
 */
internal suspend fun ConnectionManager.clearPreRepeatParams() {
    val existing = deviceRow() ?: return
    persistDeviceSettingsAfterRadioAck(logger, FAILED_PERSIST_CLEARED_PRE_REPEAT_SETTINGS) {
        database.deviceDao().upsert(
            existing.copy(
                preRepeatFrequency = null,
                preRepeatBandwidth = null,
                preRepeatSpreadingFactor = null,
                preRepeatCodingRate = null,
            ),
        )
    }
}

/**
 * Re-reads what the radio holds now into the Device row, so a Settings screen opened long after
 * connect renders live values rather than the ones captured at the handshake. Mirrors the iOS
 * settings-screen startup reads: `getSelfInfo`, plus the auto-add policy behind its firmware gate.
 * A no-op while disconnected or before a Device row exists. A refused self-info read is logged and
 * leaves the row unchanged, because a stale screen beats an error the user cannot act on.
 *
 * Only the fields `getSelfInfo` reports are rewritten, on the row the connected device id already
 * names. Nothing here resolves an identity, so the `radioID` partition key that every per-radio row
 * hangs off cannot be re-minted by a refresh.
 */
suspend fun ConnectionManager.refreshDeviceSettings() {
    val liveSession = session
    val existing = deviceRow()
    if (liveSession == null || existing == null) return
    val info = readReportedSelfInfo(logger::warning) { liveSession.getSelfInfo() } ?: return
    val autoAdd = readAutoAddConfig(existing.firmwareVersionString, storedAutoAddConfig(existing))
    persistDeviceSettingsAfterRadioAck(logger, FAILED_PERSIST_DEVICE_SETTINGS_REFRESH) {
        database.deviceDao().upsert(
            existing.copy(
                publicKey = info.publicKey,
                nodeName = info.name,
                frequency = (info.radioFrequency * ConnectionManager.KHZ_PER_MHZ).toLong(),
                bandwidth = (info.radioBandwidth * ConnectionManager.HZ_PER_KHZ).toLong(),
                spreadingFactor = info.radioSpreadingFactor,
                codingRate = info.radioCodingRate,
                txPower = info.txPower,
                latitude = info.latitude,
                longitude = info.longitude,
                manualAddContacts = info.manualAddContacts,
                multiAcks = info.multiAcks,
                telemetryModeBase = info.telemetryModeBase,
                telemetryModeLoc = info.telemetryModeLocation,
                telemetryModeEnv = info.telemetryModeEnvironment,
                advertLocationPolicy = info.advertisementLocationPolicy,
                autoAddConfig = autoAdd.bitmask,
                autoAddMaxHops = autoAdd.maxHops,
            ),
        )
    }
    // The BLE-menu header reads the last-connection name, same as a verified rename.
    lastConnectionStore?.let { store ->
        val radio = store.radioId()
        val handle = store.handle()
        if (radio != null && handle != null) {
            applyLastConnectionPersist(
                persist = { store.persist(existing.id, radio, info.name, handle) },
                logger = logger,
                failLine = FAILED_PERSIST_LAST_CONNECTION,
            )
        }
    }
}

/** The last-connected Device row, or `null` when none has been recorded. */
internal suspend fun ConnectionManager.deviceRow(): DeviceEntity? {
    val deviceId = lastConnectionStore?.deviceId() ?: return null
    return database.deviceDao().getById(deviceId)
}
