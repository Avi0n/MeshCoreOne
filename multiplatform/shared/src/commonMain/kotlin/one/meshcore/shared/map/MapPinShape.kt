package one.meshcore.shared.map

/**
 * Which node-kind sprite a [PinStyle] maps to. iOS `PinSpriteRenderer` draws every kind as the same
 * teardrop and tells them apart with color plus a glyph; Android does the same. The enum names are
 * historical (diamond/hexagon) and stay as the sprite-id keys.
 */
enum class MapPinShape {
    /** A rounded teardrop pin — chat contacts and discovered chat nodes. */
    CHAT_TEARDROP,

    /** Repeater contacts and standalone repeaters. */
    REPEATER_DIAMOND,

    /** Room-server contacts. */
    ROOM_HEXAGON,

    /** A thin dropped-location pin — long-press and deep-link drops. */
    DROPPED_PIN,
}

/**
 * Which node-kind sprite a [PinStyle] maps to, or `null` for a dedicated overlay (line-of-sight
 * A/B/R/X, selection rings). The Android host rasterizes each as a teardrop with a Material Symbol.
 */
fun PinStyle.mapPinShape(): MapPinShape? = when (this) {
    PinStyle.CONTACT_CHAT -> MapPinShape.CHAT_TEARDROP
    PinStyle.CONTACT_REPEATER, PinStyle.REPEATER -> MapPinShape.REPEATER_DIAMOND
    PinStyle.CONTACT_ROOM -> MapPinShape.ROOM_HEXAGON
    PinStyle.DROPPED_PIN -> MapPinShape.DROPPED_PIN
    else -> null
}
