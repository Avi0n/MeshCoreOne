package one.meshcore.shared.map

/**
 * Where a pin sprite sits relative to its coordinate. Mirrors the iOS `anchorType` feature attribute
 * (`MC1MapView.Coordinator.iconAnchor(for:)`) and carries the same two values MapLibre understands.
 */
enum class MapPinAnchor(val rawValue: String) {
    /** The sprite's centre sits on the coordinate: dots and the flat tool markers. */
    CENTER("center"),

    /** The sprite's bottom edge sits on the coordinate: every teardrop, so its tip marks the spot. */
    BOTTOM("bottom"),
}

/**
 * The anchor a [PinStyle] draws with. A teardrop centred on its coordinate points at a spot half an
 * icon away from the node it marks, so only the styles drawn as a symmetric mark stay centred.
 */
fun PinStyle.mapPinAnchor(): MapPinAnchor = when (this) {
    PinStyle.CROSSHAIR, PinStyle.OBSTRUCTION, PinStyle.LOCATION_FIX -> MapPinAnchor.CENTER
    else -> MapPinAnchor.BOTTOM
}
