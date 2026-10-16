package one.meshcore.shared.connection

import one.meshcore.shared.ui.ScannedDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scan-list orchestration matching iOS `RSSIScanTracker`: skip the unavailable sentinel, keep a
 * name when a later packet omits it, and drop a radio that has not advertised within the stale window.
 */
class RssiScanTrackerTest {
    @Test
    fun anUnavailableReadingIsIgnored() {
        val tracker = RssiScanTracker(nowMillis = { 0L })
        tracker.ingest(ScannedDevice(id = "a", name = "Alpha", rssi = UNAVAILABLE_RSSI))
        assertTrue(tracker.devices().isEmpty())
    }

    @Test
    fun aLaterPacketWithoutANameKeepsThePreviousName() {
        val tracker = RssiScanTracker(nowMillis = { 0L })
        tracker.ingest(ScannedDevice(id = "a", name = "Alpha", rssi = -70))
        tracker.ingest(ScannedDevice(id = "a", name = null, rssi = -68))
        assertEquals("Alpha", tracker.devices().single().name)
    }

    @Test
    fun aRadioNotSeenWithinTheStaleWindowIsDropped() {
        var now = 0L
        val tracker = RssiScanTracker(nowMillis = { now })
        tracker.ingest(ScannedDevice(id = "a", name = "Alpha", rssi = -70))
        now = RSSI_STALE_WINDOW_MS + 1
        tracker.expireStale()
        assertTrue(tracker.devices().isEmpty())
        assertNull(tracker.signalTier("a"))
    }

    @Test
    fun aFreshAdvertKeepsTheRadioOnTheList() {
        var now = 0L
        val tracker = RssiScanTracker(nowMillis = { now })
        tracker.ingest(ScannedDevice(id = "a", name = "Alpha", rssi = -70))
        now = RSSI_STALE_WINDOW_MS - 1
        tracker.expireStale()
        assertEquals("a", tracker.devices().single().id)
    }
}
