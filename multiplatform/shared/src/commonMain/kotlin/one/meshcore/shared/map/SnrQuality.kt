package one.meshcore.shared.map

/**
 * Signal-quality buckets for an SNR reading, mirroring the iOS MC1Services `SNRQuality`. The color
 * values attached to each bucket are not modeled here; the map layer needs only the
 * bucket to pick a trace [LineStyle]. Kept in the map package until an SNR owner slice lands, then it
 * should be promoted to a shared location so RxLog and the SNR badges consume one classifier.
 */
enum class SnrQuality {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    UNKNOWN,
}

/**
 * Classifies an SNR value (dB) into a [SnrQuality] bucket. Thresholds match the iOS `SNRQuality`
 * initializer: `> 6` excellent, `> 0` good, `> -6` fair, `<= -6` poor, `null` unknown.
 */
fun snrQuality(snr: Double?): SnrQuality {
    if (snr == null) return SnrQuality.UNKNOWN
    return when {
        snr > SNR_EXCELLENT_THRESHOLD -> SnrQuality.EXCELLENT
        snr > SNR_GOOD_THRESHOLD -> SnrQuality.GOOD
        snr > SNR_FAIR_THRESHOLD -> SnrQuality.FAIR
        else -> SnrQuality.POOR
    }
}

private const val SNR_EXCELLENT_THRESHOLD = 6.0
private const val SNR_GOOD_THRESHOLD = 0.0
private const val SNR_FAIR_THRESHOLD = -6.0
