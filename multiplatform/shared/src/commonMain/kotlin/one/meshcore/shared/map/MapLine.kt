package one.meshcore.shared.map

/**
 * A polyline on the map, rendered as a colored line over a white casing. Mirrors iOS `MapLine`.
 *
 * Equality deliberately excludes [pathIndex] (id + style + opacity + coordinates only): the renderer
 * diffs line sets to decide whether to rebuild the source, and the draw-order index churning must not
 * force a rebuild. A plain class with explicit equality expresses that; a data class would fold
 * [pathIndex] into `equals`.
 */
class MapLine(
    val id: String,
    val coordinates: List<LatLng>,
    val style: LineStyle,
    val opacity: Double,
    val pathIndex: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MapLine) return false
        return id == other.id &&
            style == other.style &&
            opacity == other.opacity &&
            coordinates == other.coordinates
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + style.hashCode()
        result = 31 * result + opacity.hashCode()
        result = 31 * result + coordinates.hashCode()
        return result
    }
}

/** The visual style of a [MapLine]. Raw values are pinned to the iOS case names (the GeoJSON `lineStyle` attribute). */
enum class LineStyle(val rawValue: String) {
    LOS("los"),
    TRACE_UNTRACED("traceUntraced"),
    TRACE_WEAK("traceWeak"),
    TRACE_MEDIUM("traceMedium"),
    TRACE_GOOD("traceGood"),
    MESSAGE_PATH("messagePath"),

    /**
     * A faint dashed connector threading location reports in time order. Not a proven route: it joins
     * sampled fixes, so it must never be drawn in the solid language [MESSAGE_PATH] reserves for a real
     * node-to-node path, or a reader cannot tell where a node has been from how a message travelled.
     */
    LOCATION_TRAIL("locationTrail"),
}
