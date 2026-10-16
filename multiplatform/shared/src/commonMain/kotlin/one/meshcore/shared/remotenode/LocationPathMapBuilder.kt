package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LineStyle
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.MapPinIcon
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.model.Uuid
import kotlin.math.roundToInt

/**
 * The plotted GPS position of a node: its pin coordinate plus the source snapshot's report detail.
 * `snapshotId` lets a report-row tap resolve which plotted point to auto-select.
 */
data class NodeLocationReport(
    val pinId: String,
    val snapshotId: Uuid,
    val timestampSeconds: Double,
    val coordinate: LatLng,
    val altitude: Double?,
)

/** Pins, the dashed trail, and the per-pin reports for a node's location-history map. */
data class PlottedNodePath(
    val points: List<MapPoint>,
    val lines: List<MapLine>,
    val reports: Map<String, NodeLocationReport>,
)

/**
 * Turns time-filtered snapshots into the pins and trail for a node's location-history map, mirroring
 * the iOS `LocationPathMapBuilder`. Pure: a function of the snapshots passed in.
 *
 * Precondition: [snapshots] are in ascending timestamp order (the fetch and history filter both
 * preserve it), so the builder never re-sorts. The trail always uses every valid fix; only pins are
 * decimated for the inline preview (the full-screen map keeps every report tappable). Two fixes more
 * than [MAX_CONNECTED_INTERVAL_SECONDS] apart are not joined — a node silent that long didn't travel a
 * straight line, so bridging them would draw a route that never happened.
 *
 * Every sampled fix is a neutral dot graded by recency; only the latest is the emphasized hero. The
 * trail is [LineStyle.LOCATION_TRAIL], never the message-path style: a track of observations must not
 * read as a route a packet actually took.
 */
object LocationPathMapBuilder {
    /** Cap on plotted pins for the inline preview, where dense sprites are noise. */
    const val MAX_PINS = 60

    /** ~4× the nominal capture cadence: reports farther apart than this start a new trail segment. */
    const val MAX_CONNECTED_INTERVAL_SECONDS = 60.0 * 60.0

    private const val TRAIL_OPACITY = 1.0
    private val DOT_PIN_STYLE = PinStyle.LOCATION_FIX
    private val HERO_PIN_STYLE = PinStyle.LOCATION_FIX_LATEST
    private val TRAIL_LINE_STYLE = LineStyle.LOCATION_TRAIL

    private data class Fix(val snapshot: NodeStatusSnapshotDto, val coordinate: LatLng)

    fun build(
        snapshots: List<NodeStatusSnapshotDto>,
        decimatePins: Boolean = true,
        newPinId: () -> String = { Uuid.random().value },
    ): PlottedNodePath {
        val fixes = snapshots.mapNotNull { snapshot ->
            snapshot.validCoordinate()?.let { Fix(snapshot, it) }
        }
        val reports = LinkedHashMap<String, NodeLocationReport>()
        fun makePin(fix: Fix, style: PinStyle, recencyBucket: Int? = null): MapPoint {
            val id = newPinId()
            reports[id] = NodeLocationReport(
                pinId = id,
                snapshotId = fix.snapshot.id,
                timestampSeconds = fix.snapshot.timestamp,
                coordinate = fix.coordinate,
                altitude = fix.snapshot.altitude,
            )
            return MapPoint(
                id = id,
                coordinate = fix.coordinate,
                pinStyle = style,
                label = null,
                isClusterable = false,
                // The bucket rides in the point's generic integer channel, as the hop styles use it.
                hopIndex = recencyBucket,
            )
        }

        return when {
            fixes.isEmpty() -> PlottedNodePath(emptyList(), emptyList(), emptyMap())
            // One fix: a lone hero pin, no degenerate one-length trail.
            fixes.size < 2 -> PlottedNodePath(listOf(makePin(fixes[0], HERO_PIN_STYLE)), emptyList(), reports)
            else -> PlottedNodePath(pins(fixes, decimatePins, ::makePin), segments(fixes), reports)
        }
    }

    /**
     * Inline location-history preview: the full trail when [showsFullPath] is true, otherwise a
     * single latest-fix pin (iOS `LocationHistorySection` telemetry vs overview).
     */
    fun preview(
        snapshots: List<NodeStatusSnapshotDto>,
        showsFullPath: Boolean,
        newPinId: () -> String = { Uuid.random().value },
    ): PlottedNodePath {
        if (showsFullPath) return build(snapshots, decimatePins = true, newPinId = newPinId)
        val latest = snapshots.lastOrNull { it.validCoordinate() != null }
            ?: return PlottedNodePath(emptyList(), emptyList(), emptyMap())
        return build(listOf(latest), decimatePins = false, newPinId = newPinId)
    }

    /** The most recent valid fix, scanning from the newest end; null when no snapshot carries one. */
    fun latestFix(snapshots: List<NodeStatusSnapshotDto>): LatLng? {
        for (index in snapshots.indices.reversed()) {
            snapshots[index].validCoordinate()?.let { return it }
        }
        return null
    }

    private fun pins(fixes: List<Fix>, decimate: Boolean, makePin: (Fix, PinStyle, Int?) -> MapPoint): List<MapPoint> {
        val dotFixes = mutableListOf(fixes.first())
        val interior = fixes.subList(1, fixes.size - 1)
        if (interior.isNotEmpty()) {
            val step = if (decimate) {
                val interiorBudget = MAX_PINS - 2
                maxOf(1, (interior.size + interiorBudget - 1) / interiorBudget)
            } else {
                1
            }
            var index = 0
            while (index < interior.size) {
                dotFixes.add(interior[index])
                index += step
            }
        }
        // Recency is bucketed by rank among the dots actually plotted, so every trail spans the full
        // ramp whatever its length or decimation; bucketing on absolute time collapses short tracks.
        val lastDot = dotFixes.size - 1
        val points = dotFixes
            .mapIndexed { position, fix -> makePin(fix, DOT_PIN_STYLE, recencyBucket(position, lastDot)) }
            .toMutableList()
        points.add(makePin(fixes.last(), HERO_PIN_STYLE, null))
        return points
    }

    /**
     * Maps a dot's rank (0 = oldest) onto the recency ramp, so the oldest dot lands on bucket 0 and the
     * newest on the last one. [lastDot] is the highest rank, and 0 when a single dot is plotted.
     */
    private fun recencyBucket(position: Int, lastDot: Int): Int {
        if (lastDot <= 0) return 0
        val recency = position.toDouble() / lastDot.toDouble()
        return (recency * (MapPinIcon.RECENCY_BUCKET_COUNT - 1)).roundToInt()
    }

    private fun segments(fixes: List<Fix>): List<MapLine> {
        val lines = mutableListOf<MapLine>()
        var run = mutableListOf(fixes.first().coordinate)

        fun closeRun() {
            if (run.size < 2) return
            lines.add(
                MapLine(
                    id = "location-trail-${lines.size}",
                    coordinates = run.toList(),
                    style = TRAIL_LINE_STYLE,
                    opacity = TRAIL_OPACITY,
                ),
            )
        }

        for (index in 1 until fixes.size) {
            val gap = fixes[index].snapshot.timestamp - fixes[index - 1].snapshot.timestamp
            if (gap > MAX_CONNECTED_INTERVAL_SECONDS) {
                closeRun()
                run = mutableListOf(fixes[index].coordinate)
            } else {
                run.add(fixes[index].coordinate)
            }
        }
        closeRun()
        return lines
    }
}

/** The snapshot's plottable coordinate, or null when it holds no valid fix. */
fun NodeStatusSnapshotDto.validCoordinate(): LatLng? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    if (!NodeLocationFix.isValidFix(lat, lon)) return null
    return LatLng(lat, lon)
}
