package one.meshcore.shared.trace

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LineStyle
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.pathedit.DiscoveredNode
import one.meshcore.shared.pathedit.PathHop

/**
 * Path membership for a single repeater against the current outbound hop list, keyed by repeater id.
 * Mirrors iOS `TracePathMapViewModel.RepeaterPathInfo`. [hopIndex] is 1-based (matching the iOS pin
 * badge, which counts the first hop as 1, not 0).
 */
data class RepeaterPathInfo(val inPath: Boolean, val hopIndex: Int?, val isLastHop: Boolean)

/** The effect of tapping a repeater marker on the trace map. Mirrors iOS `RepeaterTapResult`. */
enum class RepeaterTapOutcome { ADDED, REMOVED, REJECTED_MIDDLE_HOP }

/**
 * Pure trace-map tap and path-membership logic, mirroring iOS `TracePathMapViewModel.rebuildPathState`
 * and `handleRepeaterTap`. A tap always names a concrete [ContactDto] (the marker carries its own
 * identity), so membership resolves by exact public-key match — the fuzzy hash-prefix resolution used
 * for pasted hex text never applies here.
 */
object TracePathHopSelection {
    /** Path membership for every repeater in [repeaters], keyed by its id. In-path discovered hops are included. */
    fun pathState(
        hops: List<PathHop>,
        repeaters: List<ContactDto>,
        discovered: List<DiscoveredNode> = emptyList(),
    ): Map<Uuid, RepeaterPathInfo> {
        val indexByKey = mutableMapOf<List<Byte>, Int>()
        hops.forEachIndexed { index, hop ->
            val key = hop.publicKey?.toList() ?: return@forEachIndexed
            indexByKey[key] = index
        }
        fun infoFor(publicKey: ByteArray): RepeaterPathInfo {
            val index = indexByKey[publicKey.toList()]
            return if (index != null) {
                RepeaterPathInfo(inPath = true, hopIndex = index + 1, isLastHop = index == hops.size - 1)
            } else {
                RepeaterPathInfo(inPath = false, hopIndex = null, isLastHop = false)
            }
        }
        val state = LinkedHashMap<Uuid, RepeaterPathInfo>(repeaters.size + discovered.size)
        repeaters.forEach { repeater -> state[repeater.id] = infoFor(repeater.publicKey) }
        discovered.forEach { node ->
            val info = infoFor(node.publicKey)
            if (info.inPath) state[node.id] = info
        }
        return state
    }

    /**
     * Decide the effect of tapping [repeater]: undo by removing the last hop when it is the path's
     * current end, append a new hop when it is absent from the path, or reject when it is a middle
     * hop (a path can only grow or shrink from its end, matching the pin's a11y hint on iOS).
     */
    fun handleTap(repeater: ContactDto, pathState: Map<Uuid, RepeaterPathInfo>): RepeaterTapOutcome =
        handleTap(repeater.id, pathState)

    fun handleTap(id: Uuid, pathState: Map<Uuid, RepeaterPathInfo>): RepeaterTapOutcome {
        val info = pathState[id]
        return when {
            info?.isLastHop == true -> RepeaterTapOutcome.REMOVED
            info?.inPath != true -> RepeaterTapOutcome.ADDED
            else -> RepeaterTapOutcome.REJECTED_MIDDLE_HOP
        }
    }
}

/**
 * Pure builder for the trace map's pre-run editing surface: candidate repeater/room pins (colored by
 * path membership) and the untraced connecting lines between the ordered hops. Distinct from
 * [TracePathMapBuilder], which renders the post-run SNR-colored overlay from a completed [TraceResult].
 */
object TracePathCandidateMapBuilder {
    /** One pin per repeater in [repeaters], ringed when it is in the current path. */
    fun points(
        repeaters: List<ContactDto>,
        pathState: Map<Uuid, RepeaterPathInfo>,
        showLabels: Boolean,
    ): List<MapPoint> = repeaters.map { repeater ->
        pin(
            id = repeater.id,
            latitude = repeater.latitude,
            longitude = repeater.longitude,
            label = if (showLabels) repeater.displayName else null,
            info = pathState[repeater.id],
        )
    }

    /** One pin per located discovered repeater, ringed when it is in the current path. */
    fun discoveredPoints(
        nodes: List<DiscoveredNode>,
        pathState: Map<Uuid, RepeaterPathInfo>,
        showLabels: Boolean,
    ): List<MapPoint> = nodes.mapNotNull { node ->
        val loc = node.location ?: return@mapNotNull null
        pin(
            id = node.id,
            latitude = loc.latitude,
            longitude = loc.longitude,
            label = if (showLabels) node.name else null,
            info = pathState[node.id],
        )
    }

    /**
     * Coordinates of [hops], in path order, resolved against contacts then discovered by exact key.
     * [start] is the phone/radio fix and, when present, becomes the first vertex so the first hop
     * draws a segment from the user (iOS `rebuildOverlays`).
     */
    fun orderedHopCoordinates(
        hops: List<PathHop>,
        repeaters: List<ContactDto>,
        discovered: List<DiscoveredNode> = emptyList(),
        start: LatLng? = null,
    ): List<LatLng> {
        val hopsCoords = hops.mapNotNull { hop ->
            val key = hop.publicKey ?: return@mapNotNull null
            val repeater = repeaters.firstOrNull { it.publicKey.contentEquals(key) }
            if (repeater != null) {
                return@mapNotNull if (repeater.hasLocation) LatLng(repeater.latitude, repeater.longitude) else null
            }
            val node = discovered.firstOrNull { it.publicKey.contentEquals(key) } ?: return@mapNotNull null
            val loc = node.location ?: return@mapNotNull null
            LatLng(loc.latitude, loc.longitude)
        }
        return if (start != null) listOf(start) + hopsCoords else hopsCoords
    }

    private fun pin(id: Uuid, latitude: Double, longitude: Double, label: String?, info: RepeaterPathInfo?): MapPoint =
        MapPoint(
            id = id.value,
            coordinate = LatLng(latitude, longitude),
            pinStyle = if (info?.inPath == true) PinStyle.REPEATER_RING_WHITE else PinStyle.REPEATER,
            label = label,
            isClusterable = true,
            hopIndex = info?.hopIndex,
        )

    /** Untraced (gray) segments connecting consecutive [hopCoordinates]. */
    fun lines(hopCoordinates: List<LatLng>): List<MapLine> = hopCoordinates.zipWithNext { from, to -> from to to }
        .mapIndexed { index, (from, to) ->
            MapLine(
                id = "trace-edit-$index",
                coordinates = listOf(from, to),
                style = LineStyle.TRACE_UNTRACED,
                opacity = DEFAULT_LINE_OPACITY,
                pathIndex = index,
            )
        }

    private const val DEFAULT_LINE_OPACITY = 1.0
}
