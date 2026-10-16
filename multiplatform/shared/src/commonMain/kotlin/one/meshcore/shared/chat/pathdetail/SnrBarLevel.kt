package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.map.SnrQuality

/**
 * The 0-1 signal-bar level for an [SnrQuality] bucket, mirroring the iOS `SNRQuality.barLevel` used for the
 * `cellularbars` variableValue in the path/repeat detail rows. The classifier itself (thresholds, buckets)
 * is reused from [one.meshcore.shared.map.snrQuality]; only the bar mapping lives here, the path/repeat
 * slice being the first consumer of the level. When an SNR owner promotes `SnrQuality` out of the map
 * package this mapping should move with it so RxLog and the SNR badges share one source.
 */
val SnrQuality.barLevel: Double
    get() = when (this) {
        SnrQuality.EXCELLENT -> BAR_LEVEL_EXCELLENT
        SnrQuality.GOOD -> BAR_LEVEL_GOOD
        SnrQuality.FAIR -> BAR_LEVEL_FAIR
        SnrQuality.POOR -> BAR_LEVEL_POOR
        SnrQuality.UNKNOWN -> BAR_LEVEL_UNKNOWN
    }

private const val BAR_LEVEL_EXCELLENT = 1.0
private const val BAR_LEVEL_GOOD = 0.75
private const val BAR_LEVEL_FAIR = 0.5
private const val BAR_LEVEL_POOR = 0.25
private const val BAR_LEVEL_UNKNOWN = 0.0
