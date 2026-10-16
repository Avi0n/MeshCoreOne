package one.meshcore.shared.settings

import kotlin.math.abs

/**
 * The LoRa parameter option sets Settings → Advanced offers, byte-aligned with the Swift `RadioOptions`
 * catalog. Bandwidth, spreading factor and coding rate are enumerated rather than free-text: an
 * off-grid value still encodes onto the wire, but no other node on the mesh can hear it.
 */
object RadioOptions {
    /**
     * Bandwidth options in Hz — the unit the firmware expects, despite the `bandwidthKHz` parameter
     * name on the set-radio frame. Display order is ascending, which also decides which option a tie
     * in [nearestBandwidthHz] resolves to.
     */
    val bandwidthsHz: List<Long> = listOf(
        7_800L,
        10_400L,
        15_600L,
        20_800L,
        31_250L,
        41_700L,
        62_500L,
        125_000L,
        250_000L,
        500_000L,
    )

    /** The same options in kHz, the unit the manual form and the node-config planner carry. */
    val bandwidthsKHz: List<Double> = bandwidthsHz.map { it / HZ_PER_KHZ }

    /** Valid spreading factors (SF5-SF12). */
    val spreadingFactors: IntRange = 5..12

    /** Valid coding rates (5-8, meaning 4/5 through 4/8). */
    val codingRates: IntRange = 5..8

    /** Display text for a bandwidth in kHz: "7.8", "31.25", "125". Locale-independent by construction. */
    fun formatBandwidthKHz(kHz: Double): String = if (kHz % 1.0 == 0.0) kHz.toLong().toString() else kHz.toString()

    /**
     * The listed bandwidth closest to a device-reported one, or the reading unchanged when nothing listed
     * is near it. Firmware stores bandwidth as a float and reports it back rounded, so a radio on 7.8 kHz
     * can answer 7799 Hz and seeding the picker with the raw value would leave no option selected. The
     * bound is what keeps that convenience from rewriting a radio deliberately run off the listed grid:
     * such a reading is returned as it came, so the on-grid check still refuses to write it back.
     */
    fun nearestBandwidthHz(hz: Long): Long =
        bandwidthsHz.minByOrNull { abs(it - hz) }?.takeIf { abs(it - hz) <= SNAP_TOLERANCE_HZ } ?: hz

    /** [nearestBandwidthHz] in the kHz unit the form carries, with the same bound. */
    fun nearestBandwidthKHz(kHz: Double): Double =
        bandwidthsKHz.minByOrNull { abs(it - kHz) }?.takeIf { abs(it - kHz) <= SNAP_TOLERANCE_KHZ } ?: kHz

    /**
     * Whether a bandwidth is one of the listed options. The tolerance absorbs decimal-text round-tripping
     * only; it is far tighter than the gap between two neighbouring options.
     */
    fun isStandardBandwidthKHz(kHz: Double): Boolean = bandwidthsKHz.any { abs(it - kHz) < TOLERANCE_KHZ }

    private const val HZ_PER_KHZ = 1000.0
    private const val TOLERANCE_KHZ = 0.001

    // How far a reading may sit from a listed option and still be treated as that option rounded. Wide
    // enough for the firmware's float round-trip, far narrower than the gap between any two options.
    private const val SNAP_TOLERANCE_HZ = 50L
    private const val SNAP_TOLERANCE_KHZ = 0.05
}
