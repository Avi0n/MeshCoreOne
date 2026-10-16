package one.meshcore.shared.trace

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A saved-path row shows its success rate as a bare percentage, so the health band is what a screen reader
 * uses to say what that number means. The bands are the iOS thresholds.
 */
class SavedPathHealthTest {
    @Test
    fun theBandsMeetAtTheIosThresholds() {
        assertEquals(SavedPathHealth.HEALTHY, savedPathHealth(HEALTHY_SUCCESS_RATE))
        assertEquals(SavedPathHealth.DEGRADED, savedPathHealth(HEALTHY_SUCCESS_RATE - 1))
        assertEquals(SavedPathHealth.DEGRADED, savedPathHealth(DEGRADED_SUCCESS_RATE))
        assertEquals(SavedPathHealth.POOR, savedPathHealth(DEGRADED_SUCCESS_RATE - 1))
    }

    /** A path with no runs reports full success, so it must not be announced as failing. */
    @Test
    fun aPathWithNoRunsReadsAsHealthy() {
        assertEquals(SavedPathHealth.HEALTHY, savedPathHealth(FULL_SUCCESS_RATE))
    }

    @Test
    fun everyBandHasItsOwnWord() {
        val bands = SavedPathHealth.entries
        assertEquals(bands.size, bands.map { it.wordResource() }.toSet().size)
    }
}
