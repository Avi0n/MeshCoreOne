package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.map.haversineMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagePathMapBuilderTest {
    @Test
    fun drawnPathDistanceSumsLocatedNodesOnly() {
        val sender = LatLng(0.0, 0.0)
        val hop = LatLng(0.0, 1.0)
        val receiver = LatLng(0.0, 2.0)
        val display = MessagePathDisplay(
            available = true,
            rows = listOf(
                PathHopRow(PathHopType.Sender, "A", "01", null, sender),
                PathHopRow(PathHopType.Intermediate(1), "R", "A3", null, hop),
                PathHopRow(PathHopType.Receiver, "Me", null, 5.0, receiver),
            ),
            hasIntermediateHops = true,
            pathString = "A3",
            pathStringForClipboard = "A3",
        )
        assertEquals(1, display.hopCount)
        val plot = MessagePathMapBuilder.build(display)
        val expected = haversineMeters(sender, hop) + haversineMeters(hop, receiver)
        assertEquals(expected, assertNotNull(plot.totalPathDistanceMeters), absoluteTolerance = 0.001)
        assertTrue(plot.points.none { it.isClusterable })
        assertEquals(false, plot.isDistanceIncomplete)
        assertEquals(5.0, assertNotNull(plot.region).spanLonDeg, absoluteTolerance = 0.001)
    }

    @Test
    fun singleLocatedNodeHasNoDistance() {
        val display = MessagePathDisplay(
            available = true,
            rows = listOf(
                PathHopRow(PathHopType.Sender, "A", "01", null, location = null),
                PathHopRow(PathHopType.Receiver, "Me", null, null, LatLng(1.0, 2.0)),
            ),
            hasIntermediateHops = false,
            pathString = "",
            pathStringForClipboard = "",
        )
        assertEquals(0, display.hopCount)
        val plot = MessagePathMapBuilder.build(display)
        assertNull(plot.totalPathDistanceMeters)
        assertTrue(plot.isDistanceIncomplete)
    }

    @Test
    fun fallbackHopIsNotPlotted() {
        val hop = LatLng(0.0, 1.0)
        val display = MessagePathDisplay(
            available = true,
            rows = listOf(
                PathHopRow(PathHopType.Sender, "A", "01", null, LatLng(0.0, 0.0)),
                PathHopRow(
                    PathHopType.Intermediate(1),
                    "Maybe",
                    "A3",
                    null,
                    hop,
                    matchKind = NodeNameMatchKind.FALLBACK,
                ),
                PathHopRow(PathHopType.Receiver, "Me", null, 5.0, LatLng(0.0, 2.0)),
            ),
            hasIntermediateHops = true,
            pathString = "A3",
            pathStringForClipboard = "A3",
        )
        val plot = MessagePathMapBuilder.build(display)
        assertTrue(plot.points.none { it.id.contains("hop-1") })
        assertTrue(plot.isDistanceIncomplete)
        assertFalse(MessagePathMapBuilder.showsPathMap(plot, display.hopCount))
    }

    @Test
    fun previewHiddenWhenPathCannotBeDrawn() {
        val empty = MessagePathMapBuilder.Plot(emptyList(), emptyList(), null)
        assertFalse(MessagePathMapBuilder.showsPathMap(empty, hopCount = 0))
        assertFalse(MessagePathMapBuilder.showsPathMap(empty, hopCount = 2))
        assertTrue(MessagePathMapBuilder.showsPathMap(plotOf(PinStyle.POINT_A), hopCount = 0))
        assertFalse(
            MessagePathMapBuilder.showsPathMap(plotOf(PinStyle.POINT_A, PinStyle.POINT_B), hopCount = 2),
        )
        assertFalse(
            MessagePathMapBuilder.showsPathMap(
                plotOf(PinStyle.REPEATER_HOP, incomplete = true),
                hopCount = 2,
            ),
        )
        assertTrue(MessagePathMapBuilder.showsPathMap(plotOf(PinStyle.REPEATER_HOP), hopCount = 1))
        assertTrue(
            MessagePathMapBuilder.showsPathMap(
                plotOf(PinStyle.REPEATER_HOP, PinStyle.REPEATER_HOP, incomplete = true),
                hopCount = 3,
            ),
        )
        assertFalse(MessagePathMapBuilder.showsPathMap(plotOf(PinStyle.BADGE), hopCount = 0))
    }

    private fun plotOf(vararg styles: PinStyle, incomplete: Boolean = false) = MessagePathMapBuilder.Plot(
        points = styles.mapIndexed { index, style ->
            MapPoint(
                id = "$index",
                coordinate = LatLng(0.0, index.toDouble()),
                pinStyle = style,
                label = null,
                isClusterable = false,
            )
        },
        lines = emptyList(),
        region = null,
        isDistanceIncomplete = incomplete,
    )
}
