package one.meshcore.shared.connection

import one.meshcore.shared.settings.ManualRadioParams

/**
 * Enters client-repeat mode: retunes to the repeat preset nearest the current frequency
 * (bandwidth/SF/CR untouched — Repeat Mode changes only the frequency). The parameters in use are
 * saved on the way in by the repeat transition. Mirrors iOS `RadioPresetSection.enableRepeatMode`.
 */
suspend fun ConnectionManager.enableClientRepeat(repeatPresetFrequencyKHz: Long) =
    applyRepeatPreset(repeatPresetFrequencyKHz)

/**
 * Retunes to a different repeat-preset frequency while already in client-repeat mode: applies the
 * frequency only (bandwidth/SF/CR untouched) with `clientRepeat` still on, and — unlike
 * [enableClientRepeat] — does not touch the saved pre-repeat parameters, so leaving repeat mode
 * still restores the values from before repeat was first entered. Mirrors iOS `applyPreset` in the
 * repeat branch.
 */
suspend fun ConnectionManager.applyRepeatFrequency(repeatPresetFrequencyKHz: Long) =
    applyRepeatPreset(repeatPresetFrequencyKHz)

/**
 * Writes the nearest repeat-preset frequency with repeat left on. Whether that enters repeat or
 * only retunes is decided inside [applyManualRadioParams] from the device's current mode.
 */
private suspend fun ConnectionManager.applyRepeatPreset(repeatPresetFrequencyKHz: Long) {
    val existing = deviceRow() ?: error("No device row")
    applyManualRadioParams(
        ManualRadioParams(
            frequencyMHz = repeatPresetFrequencyKHz / ConnectionManager.KHZ_PER_MHZ,
            bandwidthKHz = existing.bandwidth / ConnectionManager.HZ_PER_KHZ,
            spreadingFactor = existing.spreadingFactor,
            codingRate = existing.codingRate,
            txPower = null,
            clientRepeat = true,
        ),
    )
}

/**
 * Leaves client-repeat mode by restoring the radio parameters saved by [enableClientRepeat]; the
 * repeat transition clears the saved fields once the restore lands. Mirrors iOS
 * `RadioPresetSection.disableRepeatMode`, including the fallback to the radio's current params when
 * nothing was saved (a radio that entered repeat outside the app).
 */
suspend fun ConnectionManager.disableClientRepeat() {
    val existing = deviceRow() ?: error("No device row")
    val restoredFrequency = existing.preRepeatFrequency ?: existing.frequency
    applyManualRadioParams(
        ManualRadioParams(
            frequencyMHz = restoredFrequency / ConnectionManager.KHZ_PER_MHZ,
            bandwidthKHz = (existing.preRepeatBandwidth ?: existing.bandwidth) / ConnectionManager.HZ_PER_KHZ,
            spreadingFactor = existing.preRepeatSpreadingFactor ?: existing.spreadingFactor,
            codingRate = existing.preRepeatCodingRate ?: existing.codingRate,
            txPower = null,
            clientRepeat = false,
        ),
    )
}
