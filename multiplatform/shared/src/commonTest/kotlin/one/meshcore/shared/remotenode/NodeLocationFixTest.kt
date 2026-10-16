package one.meshcore.shared.remotenode

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NodeLocationFixTest {
    private fun gps(lat: Double, lon: Double, alt: Double): LPPDataPoint =
        LPPDataPoint(channel = 1, type = LPPSensorType.GPS, value = LPPValue.Gps(lat, lon, alt))

    @Test
    fun primaryFixIsFirstValidGpsPoint() {
        val points = listOf(
            LPPDataPoint(channel = 1, type = LPPSensorType.TEMPERATURE, value = LPPValue.FloatValue(21.5)),
            gps(37.7749, -122.4194, 30.0),
        )
        val fix = NodeLocationFix.primaryFix(points)
        assertEquals(37.7749, fix?.latitude)
        assertEquals(-122.4194, fix?.longitude)
        assertEquals(30.0, fix?.altitude)
    }

    @Test
    fun nullIslandFixIsDropped() {
        assertNull(NodeLocationFix.primaryFix(listOf(gps(0.0, 0.0, 0.0))))
    }

    @Test
    fun outOfRangeFixIsDropped() {
        assertNull(NodeLocationFix.primaryFix(listOf(gps(999.0, 999.0, 0.0))))
    }

    @Test
    fun implausibleAltitudeDropsToNullButKeepsCoordinate() {
        val fix = NodeLocationFix.primaryFix(listOf(gps(37.0, -122.0, 99_999.0)))
        assertEquals(37.0, fix?.latitude)
        assertNull(fix?.altitude)
    }

    @Test
    fun noGpsPointYieldsNoFix() {
        val points =
            listOf(LPPDataPoint(channel = 1, type = LPPSensorType.TEMPERATURE, value = LPPValue.FloatValue(1.0)))
        assertNull(NodeLocationFix.primaryFix(points))
    }

    @Test
    fun locationIsFirstWinsWithinCaptureWindow() = runTest {
        var clock = 1_000.0
        val store = InMemoryNodeSnapshotStore(now = { clock })
        val key = ByteArray(32) { 0x02 }
        val first = NodeLocationFix(37.0, -122.0, 10.0)
        val id = store.recordSnapshot(key, status = null, telemetry = null, neighbors = null, location = first)
        // A later in-window capture with a different fix must not overwrite the first.
        clock = 1_001.0
        val later = NodeLocationFix(38.0, -121.0, 20.0)
        val id2 = store.recordSnapshot(key, status = null, telemetry = null, neighbors = null, location = later)
        assertEquals(id, id2)
        val snapshot = store.fetchLatestSnapshot(key)!!
        assertEquals(37.0, snapshot.latitude)
        assertEquals(10.0, snapshot.altitude)
    }
}
