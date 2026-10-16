package one.meshcore.shared.tools.ui

import one.meshcore.shared.tools.NoiseFloorQuality
import one.meshcore.shared.ui.SignalChromeRole

internal fun noiseFloorQualityShowsBadge(quality: NoiseFloorQuality): Boolean = quality != NoiseFloorQuality.UNKNOWN

internal fun noiseFloorQualityBadgeTint(quality: NoiseFloorQuality): SignalChromeRole = when (quality) {
    NoiseFloorQuality.EXCELLENT, NoiseFloorQuality.GOOD -> SignalChromeRole.PRIMARY
    NoiseFloorQuality.FAIR -> SignalChromeRole.TERTIARY
    NoiseFloorQuality.POOR -> SignalChromeRole.ERROR
    NoiseFloorQuality.UNKNOWN -> SignalChromeRole.MUTED
}
