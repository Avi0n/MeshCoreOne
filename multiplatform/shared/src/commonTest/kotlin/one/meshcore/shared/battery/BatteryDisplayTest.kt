package one.meshcore.shared.battery

import one.meshcore.shared.meshcore.BatteryInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Covers the pure battery-readout formatting the top-bar indicator and BLE menu render. */
class BatteryDisplayTest {
    private val liIon = OCVPreset.LI_ION.ocvArray

    @Test
    fun percentDerivesFromMillivoltsThroughOcvCurve() {
        // level is a millivolt reading, not a percentage: it must be mapped through the OCV curve, not
        // clamped. A raw 3700 mV would clamp to 100% under the old bug; the curve puts it near half.
        assertEquals(48, BatteryDisplay.percent(BatteryInfo(level = 3700), liIon))
        assertEquals(100, BatteryDisplay.percent(BatteryInfo(level = 4200), liIon))
        assertEquals(0, BatteryDisplay.percent(BatteryInfo(level = 3000), liIon))
    }

    @Test
    fun percentReachesLowBatteryThresholds() {
        // The crossing the threshold monitor depends on: a near-empty pack must read as a low percent so
        // the 20/10/5% low-battery notifications fire. The old millivolt clamp pinned these at 100%.
        assertEquals(10, BatteryDisplay.percent(BatteryInfo(level = 3300), liIon))
        assertEquals(0, BatteryDisplay.percent(BatteryInfo(level = 3100), liIon))
    }

    @Test
    fun percentTextAppendsSign() {
        assertEquals("50%", BatteryDisplay.percentText(BatteryInfo(level = 3720), liIon))
    }

    @Test
    fun voltageTextConvertsMillivoltsToTwoDecimalVolts() {
        assertEquals("3.70v", BatteryDisplay.voltageText(BatteryInfo(level = 3700)))
        assertEquals("4.20v", BatteryDisplay.voltageText(BatteryInfo(level = 4200)))
        assertEquals("0.00v", BatteryDisplay.voltageText(BatteryInfo(level = -5)))
    }

    @Test
    fun storageUsedPercentIsNullWhenFiguresMissing() {
        assertNull(BatteryDisplay.storageUsedPercent(BatteryInfo(level = 50)))
        assertNull(BatteryDisplay.storageUsedPercent(BatteryInfo(level = 50, usedStorageKB = 10, totalStorageKB = 0)))
    }

    @Test
    fun storageUsedPercentComputesRatio() {
        assertEquals(
            25,
            BatteryDisplay.storageUsedPercent(BatteryInfo(level = 50, usedStorageKB = 250, totalStorageKB = 1000)),
        )
    }

    @Test
    fun tierBandsFollowWarningThresholds() {
        assertEquals(BatteryTier.CRITICAL, BatteryDisplay.tier(BatteryInfo(level = 3300), liIon))
        assertEquals(BatteryTier.LOW, BatteryDisplay.tier(BatteryInfo(level = 3420), liIon))
        assertEquals(BatteryTier.MEDIUM, BatteryDisplay.tier(BatteryInfo(level = 3630), liIon))
        assertEquals(BatteryTier.FULL, BatteryDisplay.tier(BatteryInfo(level = 3990), liIon))
    }
}
