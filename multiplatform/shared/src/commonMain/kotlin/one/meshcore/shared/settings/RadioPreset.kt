package one.meshcore.shared.settings

import kotlin.math.roundToInt

/**
 * Radio configuration preset for common regional settings. Frequency/bandwidth units match Swift:
 * [frequencyMHz] on the wire via setRadio, [frequencyKHz]/[bandwidthHz] for Device row / plan storage.
 */
data class RadioPreset(
    val id: String,
    val name: String,
    val region: RadioRegion,
    val frequencyMHz: Double,
    val bandwidthKHz: Double,
    val spreadingFactor: Int,
    val codingRate: Int,
    val repeatSectionHeader: String? = null,
    /** Bytes per hop hash (`1`, `2`, or `3`). `null` leaves the radio's current hash unchanged. */
    val pathHashSize: Int? = null,
    val availability: PresetAvailability,
    val recommendationPriority: Int = DEFAULT_RECOMMENDATION_PRIORITY,
) {
    /** Frequency in kHz for protocol/device-row encoding. */
    val frequencyKHz: Long
        get() = (frequencyMHz * KHZ_PER_MHZ).roundToInt().toLong()

    /** Bandwidth in Hz for protocol/device-row encoding. */
    val bandwidthHz: Long
        get() = (bandwidthKHz * HZ_PER_KHZ).roundToInt().toLong()

    /** Firmware `path_hash_mode`, or `null` when [pathHashSize] is `null`. */
    val pathHashMode: Int?
        get() = pathHashSize?.let { it - 1 }

    companion object {
        const val DEFAULT_RECOMMENDATION_PRIORITY = 100
        const val FAVORITE_RECOMMENDATION_PRIORITY = 110
        private const val KHZ_PER_MHZ = 1000.0
        private const val HZ_PER_KHZ = 1000.0
    }
}
