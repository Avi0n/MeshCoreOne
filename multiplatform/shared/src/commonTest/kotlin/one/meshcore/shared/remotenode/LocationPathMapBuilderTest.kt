package one.meshcore.shared.remotenode

import one.meshcore.shared.map.LineStyle
import one.meshcore.shared.map.MapPinIcon
import one.meshcore.shared.map.PinStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationPathMapBuilderTest {
    private var counter = 0
    private val pinIds = { "pin-${counter++}" }

    @Test
    fun emptyWhenNoValidFix() {
        val path = LocationPathMapBuilder.build(
            listOf(locationSnapshot(1.0, null, null), locationSnapshot(2.0, 0.0, 0.0)),
            newPinId = pinIds,
        )
        assertTrue(path.points.isEmpty())
        assertTrue(path.lines.isEmpty())
        assertTrue(path.reports.isEmpty())
    }

    @Test
    fun singleFixIsLoneHeroPinNoTrail() {
        val path = LocationPathMapBuilder.build(
            listOf(locationSnapshot(1.0, 37.0, -122.0, alt = 12.0)),
            newPinId = pinIds,
        )
        assertEquals(1, path.points.size)
        assertTrue(path.lines.isEmpty())
        val report = path.reports.values.single()
        assertEquals(12.0, report.altitude)
        assertEquals(37.0, report.coordinate.latitude)
    }

    @Test
    fun consecutiveFixesJoinIntoOneTrail() {
        val path = LocationPathMapBuilder.build(
            listOf(
                locationSnapshot(0.0, 37.0, -122.0),
                locationSnapshot(60.0, 37.1, -122.1),
                locationSnapshot(120.0, 37.2, -122.2),
            ),
            newPinId = pinIds,
        )
        assertEquals(3, path.points.size)
        assertEquals(1, path.lines.size)
        assertEquals(3, path.lines.single().coordinates.size)
    }

    @Test
    fun longSilenceBreaksTrailIntoSegments() {
        val gap = LocationPathMapBuilder.MAX_CONNECTED_INTERVAL_SECONDS + 1
        val path = LocationPathMapBuilder.build(
            listOf(
                locationSnapshot(0.0, 37.0, -122.0),
                locationSnapshot(60.0, 37.1, -122.1),
                locationSnapshot(60.0 + gap, 38.0, -121.0),
                locationSnapshot(120.0 + gap, 38.1, -121.1),
            ),
            newPinId = pinIds,
        )
        // Two runs of two fixes each → two separate trail segments.
        assertEquals(2, path.lines.size)
        assertEquals(2, path.lines[0].coordinates.size)
        assertEquals(2, path.lines[1].coordinates.size)
    }

    @Test
    fun decimationCapsPreviewPinsButFullMapKeepsAll() {
        val many = (0 until 200).map { locationSnapshot(it.toDouble(), 37.0 + it * 0.001, -122.0) }
        val preview = LocationPathMapBuilder.build(many, decimatePins = true, newPinId = pinIds)
        assertTrue(preview.points.size <= LocationPathMapBuilder.MAX_PINS)
        val full = LocationPathMapBuilder.build(many, decimatePins = false, newPinId = pinIds)
        assertEquals(200, full.points.size)
    }

    /**
     * The trail joins sampled fixes; it is not a route a packet took. Drawing it in the message-path
     * style leaves a reader unable to tell where a node has been from how a message actually travelled.
     */
    @Test
    fun theTrailIsNeverDrawnInTheProvenRouteStyle() {
        val path = LocationPathMapBuilder.build(
            listOf(
                locationSnapshot(0.0, 37.0, -122.0),
                locationSnapshot(60.0, 37.1, -122.1),
                locationSnapshot(120.0, 37.2, -122.2),
            ),
            newPinId = pinIds,
        )
        for (line in path.lines) {
            assertNotEquals(LineStyle.MESSAGE_PATH, line.style, "a location trail must not read as a route")
            assertEquals(LineStyle.LOCATION_TRAIL, line.style)
        }
    }

    /** A sampled fix drawn as a repeater diamond claims a repeater stood there. */
    @Test
    fun pinsUseTheLocationFixStylesNotNodeKinds() {
        val path = LocationPathMapBuilder.build(
            listOf(
                locationSnapshot(0.0, 37.0, -122.0),
                locationSnapshot(60.0, 37.1, -122.1),
                locationSnapshot(120.0, 37.2, -122.2),
            ),
            newPinId = pinIds,
        )
        assertEquals(PinStyle.LOCATION_FIX_LATEST, path.points.last().pinStyle)
        for (point in path.points.dropLast(1)) {
            assertEquals(PinStyle.LOCATION_FIX, point.pinStyle)
        }
    }

    /**
     * Recency is graded by rank among the dots actually plotted, so a short or decimated trail still
     * spans the whole ramp and reads directionally.
     */
    @Test
    fun dotsSpanTheWholeRecencyRampOldestToNewest() {
        val many = (0 until 200).map { locationSnapshot(it.toDouble(), 37.0 + it * 0.001, -122.0) }
        val dots = LocationPathMapBuilder.build(many, newPinId = pinIds).points.dropLast(1)

        assertEquals(0, dots.first().hopIndex)
        assertEquals(MapPinIcon.RECENCY_BUCKET_COUNT - 1, dots.last().hopIndex)
        assertEquals(
            dots.map { it.hopIndex }.sortedBy { it ?: 0 },
            dots.map { it.hopIndex },
            "recency must rise monotonically along the trail",
        )
        // The hero carries no grade of its own; it is the emphasized pin, not the newest dot.
        assertNull(LocationPathMapBuilder.build(many, newPinId = pinIds).points.last().hopIndex)
    }

    @Test
    fun aSingleDotSitsAtTheOldestGrade() {
        val path = LocationPathMapBuilder.build(
            listOf(locationSnapshot(0.0, 37.0, -122.0), locationSnapshot(60.0, 37.1, -122.1)),
            newPinId = pinIds,
        )
        assertEquals(2, path.points.size)
        assertEquals(0, path.points.first().hopIndex)
    }

    @Test
    fun latestFixScansFromNewestEnd() {
        val fix = LocationPathMapBuilder.latestFix(
            listOf(
                locationSnapshot(0.0, 37.0, -122.0),
                locationSnapshot(1.0, 38.0, -121.0),
                locationSnapshot(2.0, null, null),
            ),
        )
        assertEquals(38.0, fix?.latitude)
        assertNull(LocationPathMapBuilder.latestFix(listOf(locationSnapshot(0.0, 0.0, 0.0))))
    }

    @Test
    fun latestFixPreviewIsOneHeroPinWithNoTrail() {
        val snapshots = listOf(
            locationSnapshot(0.0, 37.0, -122.0),
            locationSnapshot(1.0, 38.0, -121.0),
            locationSnapshot(2.0, null, null),
        )
        val preview = LocationPathMapBuilder.preview(snapshots, showsFullPath = false, newPinId = pinIds)
        assertEquals(1, preview.points.size)
        assertEquals(PinStyle.LOCATION_FIX_LATEST, preview.points.single().pinStyle)
        assertEquals(38.0, preview.points.single().coordinate.latitude)
        assertTrue(preview.lines.isEmpty())

        val full = LocationPathMapBuilder.preview(snapshots, showsFullPath = true, newPinId = pinIds)
        assertEquals(2, full.points.size)
        assertEquals(1, full.lines.size)
    }
}
