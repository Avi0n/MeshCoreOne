package one.meshcore.shared.map

/**
 * A single pin on the map, built from plain data and rendered onto a GeoJSON source. Mirrors iOS
 * `MapPoint`. Equality is field-by-field (a data class over the value-typed [LatLng]) so the renderer
 * can diff point sets and avoid rebuilding sources needlessly; toggling one pin must not churn the rest.
 */
data class MapPoint(
    val id: String,
    val coordinate: LatLng,
    val pinStyle: PinStyle,
    val label: String?,
    val isClusterable: Boolean,
    val hopIndex: Int? = null,
    val badgeText: String? = null,
)

/** The visual style of a [MapPoint]. Raw values are pinned to the iOS case names (feature-attribute strings). */
enum class PinStyle(val rawValue: String) {
    CONTACT_CHAT("contactChat"),
    CONTACT_REPEATER("contactRepeater"),
    CONTACT_ROOM("contactRoom"),
    REPEATER("repeater"),
    REPEATER_RING_BLUE("repeaterRingBlue"),
    REPEATER_RING_GREEN("repeaterRingGreen"),
    REPEATER_RING_WHITE("repeaterRingWhite"),
    REPEATER_HOP("repeaterHop"),
    POINT_A("pointA"),
    POINT_B("pointB"),
    CROSSHAIR("crosshair"),
    OBSTRUCTION("obstruction"),
    BADGE("badge"),
    DROPPED_PIN("droppedPin"),

    /**
     * A sampled node location report: a small neutral dot threaded onto the history trail, graded by
     * recency through [MapPoint.hopIndex]. Center-anchored, unlike the bottom-anchored teardrops.
     */
    LOCATION_FIX("locationFix"),

    /** The node's most recent location report: the emphasized hero teardrop that caps the trail. */
    LOCATION_FIX_LATEST("locationFixLatest"),
}
