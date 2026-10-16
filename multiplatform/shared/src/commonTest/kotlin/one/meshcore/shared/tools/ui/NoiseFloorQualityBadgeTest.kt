package one.meshcore.shared.tools.ui

import one.meshcore.shared.tools.NoiseFloorQuality
import one.meshcore.shared.ui.SignalChromeRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** iOS `CurrentReadingSection.qualityBadge`: hide unknown; the text tint follows `NoiseFloorQuality`. */
class NoiseFloorQualityBadgeTest {
    @Test
    fun unknownDoesNotPaintABadge() {
        assertFalse(noiseFloorQualityShowsBadge(NoiseFloorQuality.UNKNOWN))
        assertTrue(noiseFloorQualityShowsBadge(NoiseFloorQuality.EXCELLENT))
        assertTrue(noiseFloorQualityShowsBadge(NoiseFloorQuality.GOOD))
        assertTrue(noiseFloorQualityShowsBadge(NoiseFloorQuality.FAIR))
        assertTrue(noiseFloorQualityShowsBadge(NoiseFloorQuality.POOR))
    }

    @Test
    fun badgeTintMatchesIosQualityColor() {
        assertEquals(SignalChromeRole.PRIMARY, noiseFloorQualityBadgeTint(NoiseFloorQuality.EXCELLENT))
        assertEquals(SignalChromeRole.PRIMARY, noiseFloorQualityBadgeTint(NoiseFloorQuality.GOOD))
        assertEquals(SignalChromeRole.TERTIARY, noiseFloorQualityBadgeTint(NoiseFloorQuality.FAIR))
        assertEquals(SignalChromeRole.ERROR, noiseFloorQualityBadgeTint(NoiseFloorQuality.POOR))
        assertEquals(SignalChromeRole.MUTED, noiseFloorQualityBadgeTint(NoiseFloorQuality.UNKNOWN))
    }
}
