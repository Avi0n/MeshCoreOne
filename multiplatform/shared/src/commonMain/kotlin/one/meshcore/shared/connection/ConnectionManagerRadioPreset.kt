package one.meshcore.shared.connection

import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.settings.ManualRadioParams
import one.meshcore.shared.settings.RadioPreset
import one.meshcore.shared.settings.RadioPresets

/**
 * Applies a regional radio preset over the live session (wire units MHz / kHz). iOS
 * `applyRadioPresetVerified` writes RF without publishing `.deviceUpdated`, then the optional
 * path-hash, then stamps the catalog id — a failed hash must not persist new RF or a catalog id
 * over RF-equal aliases.
 */
suspend fun ConnectionManager.applyRadioPreset(preset: RadioPreset) {
    val args = RadioPresets.setRadioArgs(preset)
    val info = setRadioParamsAndVerify(
        frequencyMHz = args.frequencyMHz,
        bandwidthKHz = args.bandwidthKHz,
        spreadingFactor = args.spreadingFactor,
        codingRate = args.codingRate,
        clientRepeat = null,
    )
    applyPresetPathHashIfNeeded(preset)
    persistVerifiedRadioParams(info, clientRepeat = null)
    deviceRow()?.let { persistAppliedRadioPresetId(it, preset.id) }
}

/**
 * After LoRa lands, write the preset's hop-hash width when the catalog carries one and the radio
 * is firmware v10+. A null [RadioPreset.pathHashSize] leaves the radio's current hash unchanged.
 */
internal suspend fun ConnectionManager.applyPresetPathHashIfNeeded(preset: RadioPreset) {
    val mode = preset.pathHashMode ?: return
    val liveSession = session ?: return
    if (liveSession.queryDevice().firmwareVersion < ConnectionManager.PATH_HASH_MODE_MIN_FIRMWARE_VERSION) return
    setPathHashMode(mode)
}

/**
 * Applies manual LoRa parameters (wire units: MHz / kHz) and optional TX power, then refreshes the
 * Device row. Used by Settings → Advanced.
 *
 * LoRa (and optional repeat) is verified and persisted before TX, matching iOS Advanced Radio's
 * `setRadioParamsVerified` then `setTxPowerVerified`. A later TX refusal must not leave the Device
 * row describing the old frequency.
 *
 * A [clientRepeat] flag that flips the device's current mode carries the pre-repeat bookkeeping with
 * it: entering repeat saves the parameters in use so leaving it can restore them, and leaving repeat
 * clears them once the restore has landed. Holding that here rather than at each call site is what
 * keeps a form-driven apply and [enableClientRepeat] on the same rules.
 */
suspend fun ConnectionManager.applyManualRadioParams(params: ManualRadioParams) {
    val clientRepeat = params.clientRepeat
    val wasRepeat = deviceRow()?.clientRepeat ?: false
    val entersRepeat = clientRepeat == true && !wasRepeat
    val leavesRepeat = clientRepeat == false && wasRepeat
    if (entersRepeat) savePreRepeatParams()
    val info = setRadioParamsAndVerify(
        frequencyMHz = params.frequencyMHz,
        bandwidthKHz = params.bandwidthKHz,
        spreadingFactor = params.spreadingFactor,
        codingRate = params.codingRate,
        clientRepeat = clientRepeat,
    )
    persistVerifiedRadioParams(info, clientRepeat)
    if (leavesRepeat) clearPreRepeatParams()
    // iOS Advanced Radio writes LoRa then TX as two verified steps, each persisting on success.
    // Bundling them would leave the Device row on the old LoRa params when only TX is refused.
    if (params.txPower != null) setTxPowerVerified(params.txPower)
}

/**
 * Writes LoRa (and optional repeat) to the radio and verifies the read-back. Does not persist the
 * Device row — iOS `setRadioParamsAndVerify` is the same split used by named preset apply.
 */
internal suspend fun ConnectionManager.setRadioParamsAndVerify(
    frequencyMHz: Double,
    bandwidthKHz: Double,
    spreadingFactor: Int,
    codingRate: Int,
    clientRepeat: Boolean?,
): SelfInfo {
    val liveSession = session ?: error("No radio connected")
    liveSession.setRadio(
        frequency = frequencyMHz,
        bandwidth = bandwidthKHz,
        spreadingFactor = spreadingFactor,
        codingRate = codingRate,
        clientRepeat = clientRepeat,
    )
    val info = liveSession.getSelfInfo()
    val expected = LoRaParams(frequencyMHz, bandwidthKHz, spreadingFactor, codingRate)
    val reported = LoRaParams(
        frequencyMHz = info.radioFrequency,
        bandwidthKHz = info.radioBandwidth,
        spreadingFactor = info.radioSpreadingFactor,
        codingRate = info.radioCodingRate,
    )
    if (!expected.matches(reported)) {
        logger.warning(radioParamsVerificationDump(expected, reported))
        throw RadioParamsVerificationFailed(expected = expected.describe(), actual = reported.describe())
    }
    if (clientRepeat != null) {
        val actualRepeat = liveSession.queryDevice().clientRepeat
        if (actualRepeat != clientRepeat) {
            logger.warning(radioClientRepeatVerificationDump(clientRepeat, actualRepeat))
            throw RadioParamsVerificationFailed(
                expected = "clientRepeat=$clientRepeat",
                actual = "clientRepeat=$actualRepeat",
            )
        }
    }
    return info
}
