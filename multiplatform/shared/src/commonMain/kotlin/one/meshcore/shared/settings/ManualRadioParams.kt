package one.meshcore.shared.settings

import kotlin.math.roundToLong

/**
 * Parsed manual radio fields for Settings → Advanced. Frequency and TX power are entered as text and
 * validated here; bandwidth, spreading factor and coding rate come from the [RadioOptions] pickers and
 * are re-checked so an option set can never be bypassed on the way to the radio.
 */
data class ManualRadioParams(
    val frequencyMHz: Double,
    val bandwidthKHz: Double,
    val spreadingFactor: Int,
    val codingRate: Int,
    val txPower: Int?,
    /** `null` leaves repeat mode untouched; the form sends it only when the radio supports the toggle. */
    val clientRepeat: Boolean? = null,
)

/** Outcome of parsing the advanced radio form fields. */
sealed class ManualRadioParseResult {
    data class Ok(val params: ManualRadioParams) : ManualRadioParseResult()
    data object Invalid : ManualRadioParseResult()
}

/**
 * Parses advanced radio form fields. Returns [ManualRadioParseResult.Invalid] when any required field
 * is missing or out of range. TX power is required, matching iOS `AdvancedRadioSection.applySettings`.
 * [maxTxPower] is the radio's own reported ceiling: a fixed ceiling would either clip a radio rated
 * higher or wave through a level the hardware cannot produce.
 */
fun parseManualRadioParams(
    texts: ManualRadioFormTexts,
    maxTxPower: Int = RadioDeviceSnapshot.DEFAULT_MAX_TX_POWER,
    clientRepeat: Boolean? = null,
): ManualRadioParseResult {
    val params = acceptedManualRadioParams(texts, maxTxPower, clientRepeat)
    return if (params == null) {
        ManualRadioParseResult.Invalid
    } else {
        ManualRadioParseResult.Ok(params)
    }
}

private fun acceptedManualRadioParams(
    texts: ManualRadioFormTexts,
    maxTxPower: Int,
    clientRepeat: Boolean?,
): ManualRadioParams? {
    val frequencyMHz = texts.frequencyMHz.trim().toDoubleOrNull()
    val bandwidthKHz = texts.bandwidthKHz.trim().toDoubleOrNull()
    val spreadingFactor = texts.spreadingFactor.trim().toIntOrNull()
    val codingRate = texts.codingRate.trim().toIntOrNull()
    val txPower = parseRequiredTxPower(texts.txPower, maxTxPower)
    return if (frequencyMHz == null || bandwidthKHz == null) {
        null
    } else if (spreadingFactor == null || codingRate == null || txPower == null) {
        null
    } else if (frequencyMHz !in MIN_FREQUENCY_MHZ..MAX_FREQUENCY_MHZ) {
        null
    } else if (!RadioOptions.isStandardBandwidthKHz(bandwidthKHz)) {
        null
    } else if (spreadingFactor !in RadioOptions.spreadingFactors || codingRate !in RadioOptions.codingRates) {
        null
    } else {
        ManualRadioParams(
            frequencyMHz = frequencyMHz,
            bandwidthKHz = bandwidthKHz,
            spreadingFactor = spreadingFactor,
            codingRate = codingRate,
            txPower = txPower,
            clientRepeat = clientRepeat,
        )
    }
}

/** Seeds the advanced form from a Device-row snapshot (kHz / Hz storage). */
fun RadioDeviceSnapshot.toFormTexts(): ManualRadioFormTexts = ManualRadioFormTexts(
    frequencyMHz = (frequencyKHz / KHZ_PER_MHZ).toString(),
    // A radio can report a bandwidth a fraction off a listed option, so the picker is seeded with the
    // nearest one rather than the raw value, which would leave no option selected.
    bandwidthKHz = RadioOptions.nearestBandwidthHz(bandwidthHz).let { (it / HZ_PER_KHZ).toString() },
    spreadingFactor = spreadingFactor.toString(),
    codingRate = codingRate.toString(),
    txPower = txPower?.toString().orEmpty(),
)

data class ManualRadioFormTexts(
    val frequencyMHz: String,
    val bandwidthKHz: String,
    val spreadingFactor: String,
    val codingRate: String,
    val txPower: String,
)

/**
 * Whether the Advanced radio form differs from the Device-row seed, matching iOS
 * `AdvancedRadioSection.settingsModified`. Repeat is compared only when the radio exposes the toggle.
 */
fun advancedRadioSettingsModified(
    current: ManualRadioFormTexts,
    seeded: ManualRadioFormTexts,
    clientRepeat: Boolean,
    snapshotClientRepeat: Boolean,
    supportsClientRepeat: Boolean,
): Boolean = current != seeded || (supportsClientRepeat && clientRepeat != snapshotClientRepeat)

/**
 * Apply is armed only when the link is up, something actually changed, and a write is not already
 * in flight or flashing success, matching iOS `canApply`.
 */
fun advancedSettingsCanApply(
    isDeviceConnected: Boolean,
    settingsModified: Boolean,
    isApplying: Boolean,
    showSuccess: Boolean,
): Boolean = isDeviceConnected && settingsModified && !isApplying && !showSuccess

/**
 * The frequency to submit when repeat mode is switched on. Repeat Mode requires an exact
 * firmware-approved frequency, so an off-band value snaps to the nearest repeat preset; without this the
 * radio rejects the write. Returns [currentFrequencyMHz] unchanged when it is already a repeat frequency.
 */
fun repeatModeSnappedFrequencyMHz(currentFrequencyMHz: Double): Double {
    val currentKHz = (currentFrequencyMHz * KHZ_PER_MHZ).roundToLong()
    if (RadioPresets.matchingRepeatPreset(currentKHz) != null) return currentFrequencyMHz
    return RadioPresets.nearestRepeatPreset(currentKHz)?.frequencyMHz ?: currentFrequencyMHz
}

/**
 * The frequency to restore when repeat mode is switched off: the one the radio used before it entered
 * repeat mode, so leaving the mode returns to the original band instead of stranding the radio on the
 * repeat frequency. Falls back to the current frequency when nothing was saved.
 */
fun RadioDeviceSnapshot.preRepeatFrequencyMHz(): Double = (preRepeatFrequencyKHz ?: frequencyKHz) / KHZ_PER_MHZ

private fun parseRequiredTxPower(raw: String, maxTxPower: Int): Int? {
    val parsed = raw.trim().toIntOrNull() ?: return null
    return parsed.takeIf { it >= TX_POWER_FLOOR && it <= maxTxPower }
}

private const val KHZ_PER_MHZ = 1000.0
private const val HZ_PER_KHZ = 1000.0

// Derived from the frozen firmware range (150,000-2,500,000 kHz) so the manual-entry form can't
// drift from the value the node-config planner itself validates against.
private val MIN_FREQUENCY_MHZ = NodeConfigLimits.FREQUENCY_RANGE_KHZ.first.toDouble() / KHZ_PER_MHZ
private val MAX_FREQUENCY_MHZ = NodeConfigLimits.FREQUENCY_RANGE_KHZ.last.toDouble() / KHZ_PER_MHZ
private val TX_POWER_FLOOR = NodeConfigLimits.TX_POWER_FLOOR.toInt()
