package one.meshcore.shared.connection

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_accessibility_signal_strength_medium
import one.meshcore.shared.resources.localizable_accessibility_signal_strength_strong
import one.meshcore.shared.resources.localizable_accessibility_signal_strength_weak
import org.jetbrains.compose.resources.StringResource

/**
 * How strong a scanned radio's signal is, in the three bands iOS `RSSITuning` uses. One tier drives both
 * the bar meter fill and the spoken description so a reader and a listener are never told different things.
 */
enum class BleSignalTier {
    STRONG,
    MEDIUM,
    WEAK,
}

/** RSSI (dBm) at or above which a signal reads as [BleSignalTier.STRONG]. Mirrors iOS `strongThreshold`. */
const val STRONG_RSSI_THRESHOLD: Int = -60

/** RSSI (dBm) at or above which a signal reads as [BleSignalTier.MEDIUM]. Mirrors iOS `mediumThreshold`. */
const val MEDIUM_RSSI_THRESHOLD: Int = -80

/** CoreBluetooth/Kable sentinel when RSSI is unavailable. Mirrors iOS `unavailableRSSI`. */
const val UNAVAILABLE_RSSI: Int = -127

/** dBm a reading must clear before the displayed tier changes. Mirrors iOS `tierHysteresis`. */
const val RSSI_TIER_HYSTERESIS: Int = 3

/** Weight on the newest sample when smoothing RSSI. Mirrors iOS `smoothingNewWeight`. */
const val RSSI_SMOOTHING_NEW_WEIGHT: Double = 0.2

/** Milliseconds without an advert before a radio is treated as gone. Mirrors iOS `staleWindow`. */
const val RSSI_STALE_WINDOW_MS: Long = 4_000L

/** Cadence of the stale-peripheral expiry sweep. Mirrors iOS `expiryTick`. */
const val RSSI_EXPIRY_TICK_MS: Long = 2_000L

/** Whether an RSSI reading is a real negative dBm value, not the unavailable sentinel. */
fun rssiIsUsable(rssi: Int): Boolean = rssi < 0 && rssi != UNAVAILABLE_RSSI

/** Exponentially smooths [newRssi] against [previousRssi]; the first sample is unchanged. */
fun smoothRssi(newRssi: Int, previousRssi: Int?): Int {
    if (previousRssi == null) return newRssi
    return (RSSI_SMOOTHING_NEW_WEIGHT * newRssi + (1.0 - RSSI_SMOOTHING_NEW_WEIGHT) * previousRssi).toInt()
}

/** The band an RSSI reading falls in. A first reading ([current] null) maps with no hysteresis. */
fun bleSignalTier(rssi: Int, current: BleSignalTier? = null): BleSignalTier = if (current == null) {
    bleSignalTierFromRssi(rssi)
} else {
    when (current) {
        BleSignalTier.STRONG -> strongSignalTier(rssi)
        BleSignalTier.MEDIUM -> mediumSignalTier(rssi)
        BleSignalTier.WEAK -> weakSignalTier(rssi)
    }
}

private fun bleSignalTierFromRssi(rssi: Int): BleSignalTier = when {
    rssi >= STRONG_RSSI_THRESHOLD -> BleSignalTier.STRONG
    rssi >= MEDIUM_RSSI_THRESHOLD -> BleSignalTier.MEDIUM
    else -> BleSignalTier.WEAK
}

private fun strongSignalTier(rssi: Int): BleSignalTier = when {
    rssi < MEDIUM_RSSI_THRESHOLD - RSSI_TIER_HYSTERESIS -> BleSignalTier.WEAK
    rssi < STRONG_RSSI_THRESHOLD - RSSI_TIER_HYSTERESIS -> BleSignalTier.MEDIUM
    else -> BleSignalTier.STRONG
}

private fun mediumSignalTier(rssi: Int): BleSignalTier = when {
    rssi >= STRONG_RSSI_THRESHOLD + RSSI_TIER_HYSTERESIS -> BleSignalTier.STRONG
    rssi < MEDIUM_RSSI_THRESHOLD - RSSI_TIER_HYSTERESIS -> BleSignalTier.WEAK
    else -> BleSignalTier.MEDIUM
}

private fun weakSignalTier(rssi: Int): BleSignalTier = when {
    rssi >= STRONG_RSSI_THRESHOLD + RSSI_TIER_HYSTERESIS -> BleSignalTier.STRONG
    rssi >= MEDIUM_RSSI_THRESHOLD + RSSI_TIER_HYSTERESIS -> BleSignalTier.MEDIUM
    else -> BleSignalTier.WEAK
}

/** The shipped words for a band, for readers who get nothing from the bar meter. */
fun BleSignalTier.wordResource(): StringResource = when (this) {
    BleSignalTier.STRONG -> Res.string.localizable_accessibility_signal_strength_strong
    BleSignalTier.MEDIUM -> Res.string.localizable_accessibility_signal_strength_medium
    BleSignalTier.WEAK -> Res.string.localizable_accessibility_signal_strength_weak
}
