package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.map.SnrQuality
import one.meshcore.shared.map.snrQuality
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the SNR threshold classifier (reused from the map layer) and the 0-1 bar-level mapping this
 * slice adds, against the iOS `SNRQuality` init and `barLevel`.
 */
class SnrBarLevelTest {
    @Test
    fun thresholdsClassifyEachBucket() {
        assertEquals(SnrQuality.EXCELLENT, snrQuality(6.1))
        assertEquals(SnrQuality.GOOD, snrQuality(6.0)) // boundary: > 6 is excellent, so 6.0 is good
        assertEquals(SnrQuality.GOOD, snrQuality(0.1))
        assertEquals(SnrQuality.FAIR, snrQuality(0.0)) // boundary: > 0 is good, so 0.0 is fair
        assertEquals(SnrQuality.FAIR, snrQuality(-5.9))
        assertEquals(SnrQuality.POOR, snrQuality(-6.0)) // boundary: > -6 is fair, so -6.0 is poor
        assertEquals(SnrQuality.UNKNOWN, snrQuality(null))
    }

    @Test
    fun barLevelsMatchTheBuckets() {
        assertEquals(1.0, SnrQuality.EXCELLENT.barLevel)
        assertEquals(0.75, SnrQuality.GOOD.barLevel)
        assertEquals(0.5, SnrQuality.FAIR.barLevel)
        assertEquals(0.25, SnrQuality.POOR.barLevel)
        assertEquals(0.0, SnrQuality.UNKNOWN.barLevel)
    }
}
