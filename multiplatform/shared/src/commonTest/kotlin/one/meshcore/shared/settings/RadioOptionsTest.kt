package one.meshcore.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioOptionsTest {
    @Test
    fun bandwidthCatalogMatchesTheSwiftOptionSet() {
        assertEquals(
            listOf(7_800L, 10_400L, 15_600L, 20_800L, 31_250L, 41_700L, 62_500L, 125_000L, 250_000L, 500_000L),
            RadioOptions.bandwidthsHz,
        )
        assertEquals(5..12, RadioOptions.spreadingFactors)
        assertEquals(5..8, RadioOptions.codingRates)
    }

    @Test
    fun formatsBandwidthLikeTheSwiftPickerLabels() {
        assertEquals("7.8", RadioOptions.formatBandwidthKHz(7.8))
        assertEquals("31.25", RadioOptions.formatBandwidthKHz(31.25))
        assertEquals("125", RadioOptions.formatBandwidthKHz(125.0))
        assertEquals("500", RadioOptions.formatBandwidthKHz(500.0))
    }

    @Test
    fun snapsFirmwareRoundedBandwidthToTheNearestOption() {
        assertEquals(7_800L, RadioOptions.nearestBandwidthHz(7_799L))
        assertEquals(7_800L, RadioOptions.nearestBandwidthHz(7_801L))
        assertEquals(250_000L, RadioOptions.nearestBandwidthHz(250_000L))
    }

    @Test
    fun leavesADeliberatelyOffGridBandwidthAlone() {
        // Snapping is only meant to absorb the firmware's float rounding. A node run off the listed grid
        // must come back unchanged, so the on-grid check still refuses to write it, rather than being
        // quietly moved to another bandwidth by an apply aimed at frequency or spreading factor.
        assertEquals(137_000L, RadioOptions.nearestBandwidthHz(137_000L))
        assertEquals(137.0, RadioOptions.nearestBandwidthKHz(137.0))
        assertFalse(RadioOptions.isStandardBandwidthKHz(RadioOptions.nearestBandwidthKHz(137.0)))
    }

    @Test
    fun rejectsOffGridBandwidths() {
        assertTrue(RadioOptions.isStandardBandwidthKHz(62.5))
        assertTrue(RadioOptions.isStandardBandwidthKHz(7.8))
        assertFalse(RadioOptions.isStandardBandwidthKHz(100.0))
        assertFalse(RadioOptions.isStandardBandwidthKHz(63.0))
    }
}
