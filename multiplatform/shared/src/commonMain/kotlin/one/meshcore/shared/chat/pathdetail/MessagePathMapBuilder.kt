package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.MetricSnrBadgeFormatter
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.map.SnrBadgeFormatter
import one.meshcore.shared.map.boundingRegion
import one.meshcore.shared.map.lineStyleForSnr
import one.meshcore.shared.map.snrBadge
import one.meshcore.shared.map.totalDistanceMeters

/**
 * Pure builder for the message-path map overlays (iOS `MessagePathMapView`), mirroring
 * [one.meshcore.shared.trace.TracePathMapBuilder]: rows with a resolved [PathHopRow.location] become pins,
 * consecutive located rows become SNR-colored segments with midpoint badges. A row whose location is
 * unknown is skipped rather than breaking the line — the sender or an unresolved repeater commonly has no
 * stored position, so the plotted path may show fewer segments than hops.
 */
object MessagePathMapBuilder {
    data class Plot(
        val points: List<MapPoint>,
        val lines: List<MapLine>,
        val region: MapCameraRegion?,
        val totalPathDistanceMeters: Double? = null,
        val isDistanceIncomplete: Boolean = false,
    )

    private val empty = Plot(points = emptyList(), lines = emptyList(), region = null)

    fun build(
        display: MessagePathDisplay,
        formatter: SnrBadgeFormatter = MetricSnrBadgeFormatter(),
        lineOpacity: Double = DEFAULT_LINE_OPACITY,
    ): Plot {
        if (!display.available) return empty

        val located = display.rows.mapIndexedNotNull { index, row ->
            if (row.matchKind != NodeNameMatchKind.EXACT) return@mapIndexedNotNull null
            row.location?.let { LocatedRow(index = index, row = row, coordinate = it) }
        }
        if (located.isEmpty()) return empty

        val points = mutableListOf<MapPoint>()
        val lines = mutableListOf<MapLine>()
        val coordinates = mutableListOf<LatLng>()

        located.forEachIndexed { plotIndex, item ->
            points += MapPoint(
                id = "path-hop-${item.index}",
                coordinate = item.coordinate,
                pinStyle = pinStyle(item.row.type),
                label = item.row.nodeName,
                isClusterable = false,
                hopIndex = (item.row.type as? PathHopType.Intermediate)?.number,
            )
            coordinates += item.coordinate

            if (plotIndex == 0) return@forEachIndexed
            val previous = located[plotIndex - 1]
            val snr = item.row.snr
            val style = lineStyleForSnr(snr)
            lines += MapLine(
                id = "path-seg-${previous.index}-${item.index}",
                coordinates = listOf(previous.coordinate, item.coordinate),
                style = style,
                opacity = lineOpacity,
                pathIndex = previous.index,
            )
            if (snr != null) {
                points += snrBadge(
                    id = "path-badge-${item.index}",
                    from = previous.coordinate,
                    to = item.coordinate,
                    snr = snr,
                    formatter = formatter,
                )
            }
        }

        return Plot(
            points = points,
            lines = lines,
            region = coordinates.boundingRegion(paddingMultiplier = PATH_BOUNDS_PADDING_MULTIPLIER),
            totalPathDistanceMeters = totalDistanceMeters(coordinates),
            isDistanceIncomplete = located.size < display.rows.size,
        )
    }

    /** Endpoint pins alone are not a path. A single hop pin with skipped hops is a shortcut, not the path. */
    fun showsPathMap(plot: Plot, hopCount: Int): Boolean {
        val located = plot.points.filter { it.pinStyle != PinStyle.BADGE }
        val placedHopCount = located.count { it.pinStyle == PinStyle.REPEATER_HOP }
        val directContact = hopCount == 0 && located.isNotEmpty()
        val tracedHops = placedHopCount > 1 || (placedHopCount == 1 && !plot.isDistanceIncomplete)
        return directContact || (hopCount != 0 && tracedHops)
    }

    private fun pinStyle(type: PathHopType): PinStyle = when (type) {
        PathHopType.Sender -> PinStyle.POINT_A
        PathHopType.Receiver -> PinStyle.POINT_B
        is PathHopType.Intermediate -> PinStyle.REPEATER_HOP
    }

    private data class LocatedRow(val index: Int, val row: PathHopRow, val coordinate: LatLng)

    private const val DEFAULT_LINE_OPACITY = 1.0

    /** Wider than the default fit so a bottom-anchored pin at the edge stays in frame. */
    private const val PATH_BOUNDS_PADDING_MULTIPLIER = 2.5
}
