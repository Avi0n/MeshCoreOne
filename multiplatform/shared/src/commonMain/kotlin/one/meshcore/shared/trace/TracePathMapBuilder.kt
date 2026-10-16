package one.meshcore.shared.trace

import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LineStyle
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.MetricSnrBadgeFormatter
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.map.SnrBadgeFormatter
import one.meshcore.shared.map.lineStyleForSnr
import one.meshcore.shared.map.snrBadge

/**
 * Pure builder for the Trace Path map overlays from a completed [TraceResult]. Mirrors the
 * post-result branch of iOS `TracePathMapViewModel.updateOverlaysWithResults`: consecutive hops with
 * usable coordinates become SNR-colored segments with midpoint badges.
 */
object TracePathMapBuilder {
    data class Plot(val points: List<MapPoint>, val lines: List<MapLine>)

    private val empty = Plot(points = emptyList(), lines = emptyList())

    fun buildFromResult(
        result: TraceResult,
        formatter: SnrBadgeFormatter = MetricSnrBadgeFormatter(),
        lineOpacity: Double = DEFAULT_LINE_OPACITY,
    ): Plot {
        if (!result.success) return empty

        val located = result.hops.mapIndexedNotNull { index, hop ->
            if (!hop.hasLocation) return@mapIndexedNotNull null
            val lat = hop.latitude ?: return@mapIndexedNotNull null
            val lon = hop.longitude ?: return@mapIndexedNotNull null
            LocatedHop(index = index, hop = hop, coordinate = LatLng(lat, lon))
        }
        if (located.isEmpty()) return empty

        val points = mutableListOf<MapPoint>()
        val lines = mutableListOf<MapLine>()

        located.forEachIndexed { plotIndex, item ->
            points += MapPoint(
                id = "trace-hop-${item.index}",
                coordinate = item.coordinate,
                pinStyle = hopPinStyle(item.hop),
                label = item.hop.resolvedName,
                isClusterable = false,
                hopIndex = if (item.hop.isStartNode) null else plotIndex,
            )

            if (plotIndex == 0) return@forEachIndexed
            val previous = located[plotIndex - 1]
            val style = lineStyleForSnr(item.hop.snr)
            lines += MapLine(
                id = "trace-seg-${previous.index}-${item.index}",
                coordinates = listOf(previous.coordinate, item.coordinate),
                style = style,
                opacity = lineOpacity,
                pathIndex = previous.index,
            )
            if (style != LineStyle.TRACE_UNTRACED) {
                points += snrBadge(
                    id = "trace-badge-${item.index}",
                    from = previous.coordinate,
                    to = item.coordinate,
                    snr = item.hop.snr,
                    formatter = formatter,
                )
            }
        }

        return Plot(
            points = points,
            lines = lines,
        )
    }

    private fun hopPinStyle(hop: TraceHop): PinStyle = when {
        hop.isStartNode || hop.isEndNode -> PinStyle.REPEATER_RING_WHITE
        else -> PinStyle.REPEATER_HOP
    }

    private data class LocatedHop(val index: Int, val hop: TraceHop, val coordinate: LatLng)

    private const val DEFAULT_LINE_OPACITY = 1.0
}
