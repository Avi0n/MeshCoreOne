package one.meshcore.shared.connection

import one.meshcore.shared.ui.filledBarCount
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bar glyph and the spoken description both come from this tier, so a listener and a reader are told
 * the same thing. Thresholds are the iOS `RSSITuning` ones.
 */
class BleSignalTierTest {
    @Test
    fun theBandsMeetAtTheIosThresholds() {
        assertEquals(BleSignalTier.STRONG, bleSignalTier(STRONG_RSSI_THRESHOLD))
        assertEquals(BleSignalTier.MEDIUM, bleSignalTier(STRONG_RSSI_THRESHOLD - 1))
        assertEquals(BleSignalTier.MEDIUM, bleSignalTier(MEDIUM_RSSI_THRESHOLD))
        assertEquals(BleSignalTier.WEAK, bleSignalTier(MEDIUM_RSSI_THRESHOLD - 1))
    }

    @Test
    fun aStrongerReadingNeverShowsFewerBars() {
        val fills = listOf(-30, -60, -70, -80, -95).map { bleSignalTier(it).filledBarCount }

        assertEquals(fills.sortedDescending(), fills)
    }

    @Test
    fun everyBandHasItsOwnFillAndWord() {
        val tiers = BleSignalTier.entries

        assertEquals(tiers.size, tiers.map { it.filledBarCount }.toSet().size)
        assertEquals(tiers.size, tiers.map { it.wordResource() }.toSet().size)
    }

    @Test
    fun unavailableAndNonNegativeReadingsAreNotUsable() {
        assertEquals(false, rssiIsUsable(UNAVAILABLE_RSSI))
        assertEquals(false, rssiIsUsable(0))
        assertEquals(false, rssiIsUsable(10))
        assertEquals(true, rssiIsUsable(-70))
    }

    @Test
    fun aFirstSampleIsNotSmoothed() {
        assertEquals(-70, smoothRssi(newRssi = -70, previousRssi = null))
    }

    @Test
    fun aLaterSampleIsWeightedTowardThePreviousReading() {
        // 0.2 * -50 + 0.8 * -90 = -82
        assertEquals(-82, smoothRssi(newRssi = -50, previousRssi = -90))
    }

    @Test
    fun aStrongTierHoldsUntilTheReadingClearsTheHysteresis() {
        assertEquals(BleSignalTier.STRONG, bleSignalTier(-62, BleSignalTier.STRONG))
        assertEquals(BleSignalTier.MEDIUM, bleSignalTier(-64, BleSignalTier.STRONG))
    }
}
