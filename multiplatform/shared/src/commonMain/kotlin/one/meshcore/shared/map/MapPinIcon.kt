package one.meshcore.shared.map

/**
 * The sprite ids a map host registers, and the rule that picks one for a [MapPoint]. Mirrors iOS
 * `MC1MapView.Coordinator.spriteName(for:)`, including the hop-numbered variants iOS renders on demand.
 */
object MapPinIcon {
    const val CHAT = "mc1-pin-chat"
    const val REPEATER = "mc1-pin-repeater"
    const val ROOM = "mc1-pin-room"
    const val DROPPED = "mc1-pin-dropped"
    const val REPEATER_RING_BLUE = "mc1-pin-repeater-ring-blue"
    const val REPEATER_RING_GREEN = "mc1-pin-repeater-ring-green"
    const val REPEATER_RING_WHITE = "mc1-pin-repeater-ring-white"
    const val POINT_A = "mc1-pin-point-a"
    const val POINT_B = "mc1-pin-point-b"
    const val CROSSHAIR = "mc1-pin-crosshair"
    const val OBSTRUCTION = "mc1-pin-obstruction"

    /** A hop pin on a trace or message path; the badge number completes the id. */
    const val REPEATER_HOP_PREFIX = "mc1-pin-repeater-hop-"

    /** A path endpoint carrying a hop number; the badge number completes the id. */
    const val REPEATER_RING_WHITE_HOP_PREFIX = "mc1-pin-repeater-ring-white-hop-"

    /**
     * A fully transparent sprite. A badge point carries its text on the badge layer and must draw no
     * marker of its own; with no sprite it falls through to the plain circle and reads as a phantom pin.
     */
    const val BADGE = "mc1-pin-badge"

    /** A sampled location-fix dot; the recency bucket completes the id. */
    const val LOCATION_FIX_PREFIX = "mc1-pin-location-fix-"

    /** The hero teardrop capping a location trail at the node's latest report. */
    const val LOCATION_LATEST = "mc1-pin-location-latest"

    /** Empty icon name: the circle layer draws when no sprite is registered. */
    const val NONE = ""

    /** Largest hop number a badge renders; MeshCore paths top out at 64 hops (iOS `maxHopBadge`). */
    const val MAX_HOP_BADGE = 64

    /**
     * Recency grades a location trail's dots are drawn in, oldest to newest. Five reads as a clear
     * directional gradient without banding into noise at dot size (iOS `recencyBucketCount`).
     */
    const val RECENCY_BUCKET_COUNT = 5
}

/**
 * The recency bucket a location-fix dot draws in, clamped into range. A value outside the ramp would
 * name a sprite no host registered, and MapLibre draws nothing at all for a missing image.
 */
fun mapLocationFixBucket(hopIndex: Int?): Int = (hopIndex ?: 0).coerceIn(0, MapPinIcon.RECENCY_BUCKET_COUNT - 1)

/**
 * The number a hop pin's badge draws, or null when this point carries none. Hops beyond
 * [MapPinIcon.MAX_HOP_BADGE] clamp to the cap rather than minting an unbounded sprite set, and a
 * non-positive index carries no badge at all, so an unnumbered hop still draws its base pin.
 */
fun mapPinHopBadge(pinStyle: PinStyle, hopIndex: Int?): Int? {
    val badged = pinStyle == PinStyle.REPEATER_HOP || pinStyle == PinStyle.REPEATER_RING_WHITE
    val hop = hopIndex ?: 0
    return if (badged && hop >= 1) hop.coerceAtMost(MapPinIcon.MAX_HOP_BADGE) else null
}

/**
 * The sprite a point draws: node kinds and the dropped pin get their silhouette, a hop pin gets its
 * numbered variant, a badge point gets the transparent sprite, and line-of-sight overlays get the
 * lettered/ringed markers iOS `PinSpriteRenderer` paints (A/B, R, X, blue/green rings).
 */
fun mapPinIconName(pinStyle: PinStyle, hopIndex: Int? = null): String =
    numberedHopIconName(pinStyle, hopIndex) ?: when (pinStyle) {
        PinStyle.REPEATER_RING_BLUE -> MapPinIcon.REPEATER_RING_BLUE
        PinStyle.REPEATER_RING_GREEN -> MapPinIcon.REPEATER_RING_GREEN
        PinStyle.POINT_A -> MapPinIcon.POINT_A
        PinStyle.POINT_B -> MapPinIcon.POINT_B
        PinStyle.CROSSHAIR -> MapPinIcon.CROSSHAIR
        PinStyle.OBSTRUCTION -> MapPinIcon.OBSTRUCTION
        PinStyle.BADGE -> MapPinIcon.BADGE
        PinStyle.LOCATION_FIX -> "${MapPinIcon.LOCATION_FIX_PREFIX}${mapLocationFixBucket(hopIndex)}"
        PinStyle.LOCATION_FIX_LATEST -> MapPinIcon.LOCATION_LATEST
        else -> shapeIconName(pinStyle)
    }

/** Hop pins keep a numbered sprite, and fall back to the plain marker when the hop has no badge. */
private fun numberedHopIconName(pinStyle: PinStyle, hopIndex: Int?): String? {
    val badge = mapPinHopBadge(pinStyle, hopIndex)
    return when (pinStyle) {
        PinStyle.REPEATER_HOP -> badge?.let { "${MapPinIcon.REPEATER_HOP_PREFIX}$it" } ?: MapPinIcon.REPEATER
        PinStyle.REPEATER_RING_WHITE ->
            badge?.let { "${MapPinIcon.REPEATER_RING_WHITE_HOP_PREFIX}$it" } ?: MapPinIcon.REPEATER_RING_WHITE
        else -> null
    }
}

private fun shapeIconName(pinStyle: PinStyle): String = when (pinStyle.mapPinShape()) {
    MapPinShape.CHAT_TEARDROP -> MapPinIcon.CHAT
    MapPinShape.REPEATER_DIAMOND -> MapPinIcon.REPEATER
    MapPinShape.ROOM_HEXAGON -> MapPinIcon.ROOM
    MapPinShape.DROPPED_PIN -> MapPinIcon.DROPPED
    null -> MapPinIcon.NONE
}
