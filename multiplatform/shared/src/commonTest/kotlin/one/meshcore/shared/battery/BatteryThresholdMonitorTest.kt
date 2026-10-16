package one.meshcore.shared.battery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Mirrors the iOS `BatteryMonitoringTests` (defaults, no-op on absent battery) plus the threshold
 * crossing, dedupe, re-arm, and reset semantics of the `BatteryMonitor` notification logic.
 */
class BatteryThresholdMonitorTest {
    @Test
    fun thresholdsAndCadenceMatchTheSpec() {
        assertEquals(listOf(20, 10, 5), BatteryThresholdMonitor.WARNING_THRESHOLDS)
        assertEquals(120.seconds, BatteryThresholdMonitor.REFRESH_INTERVAL)
        assertEquals(900.seconds, BatteryThresholdMonitor.BACKGROUND_INTERVAL)
    }

    @Test
    fun aFreshMonitorHasNoNotifiedThresholds() {
        assertTrue(BatteryThresholdMonitor().notified.isEmpty())
    }

    @Test
    fun initializeWithNoPercentageOrAbsentBatteryClearsAndDoesNotNotify() {
        val monitor = BatteryThresholdMonitor()
        assertFalse(monitor.initialize(percentage = null, isBatteryPresent = true))
        assertTrue(monitor.notified.isEmpty())
        assertFalse(monitor.initialize(percentage = 3, isBatteryPresent = false))
        assertTrue(monitor.notified.isEmpty())
    }

    @Test
    fun initializeAboveAllThresholdsSeedsNothingAndDoesNotNotify() {
        val monitor = BatteryThresholdMonitor()
        assertFalse(monitor.initialize(percentage = 80, isBatteryPresent = true))
        assertTrue(monitor.notified.isEmpty())
    }

    @Test
    fun initializeBelowThresholdsSeedsThemAsAlreadyNotifiedAndNotifiesOnce() {
        val monitor = BatteryThresholdMonitor()
        assertTrue(monitor.initialize(percentage = 8, isBatteryPresent = true))
        assertEquals(setOf(20, 10), monitor.notified)
    }

    @Test
    fun checkIgnoresAnAbsentBattery() {
        assertFalse(BatteryThresholdMonitor().check(percentage = 3, isBatteryPresent = false))
    }

    @Test
    fun checkNotifiesOncePerThresholdCrossingAndRearmsOnRise() {
        val monitor = BatteryThresholdMonitor()

        assertTrue(monitor.check(percentage = 15, isBatteryPresent = true), "crosses 20")
        assertFalse(monitor.check(percentage = 15, isBatteryPresent = true), "still below 20, no re-notify")

        assertTrue(monitor.check(percentage = 8, isBatteryPresent = true), "crosses 10")
        assertEquals(setOf(20, 10), monitor.notified)

        assertFalse(monitor.check(percentage = 25, isBatteryPresent = true), "rose above 20, no notify")
        assertTrue(monitor.notified.isEmpty(), "risen thresholds are re-armed")

        assertTrue(monitor.check(percentage = 15, isBatteryPresent = true), "crosses 20 again after re-arm")
    }

    @Test
    fun checkMissedNotifiesOnceForTheBatchOfMissedThresholds() {
        val monitor = BatteryThresholdMonitor()
        assertTrue(monitor.checkMissed(percentage = 8, isBatteryPresent = true))
        assertEquals(setOf(20, 10), monitor.notified)
        assertFalse(monitor.checkMissed(percentage = 8, isBatteryPresent = true), "nothing new missed")
    }

    @Test
    fun clearThresholdsResetsTheNotifiedSet() {
        val monitor = BatteryThresholdMonitor()
        monitor.check(percentage = 3, isBatteryPresent = true)
        assertTrue(monitor.notified.isNotEmpty())
        monitor.clearThresholds()
        assertTrue(monitor.notified.isEmpty())
    }

    @Test
    fun aFetchIsOverdueWhenNeverFetchedOrPastTheBackgroundInterval() {
        assertTrue(BatteryThresholdMonitor.isFetchOverdue(elapsedSinceLastSuccess = null))
        assertTrue(BatteryThresholdMonitor.isFetchOverdue(elapsedSinceLastSuccess = 900.seconds))
        assertFalse(BatteryThresholdMonitor.isFetchOverdue(elapsedSinceLastSuccess = 899.seconds))
    }
}
