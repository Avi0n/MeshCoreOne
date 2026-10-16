package one.meshcore.shared.map

/**
 * A live map scale: [label] is the rounded distance and [fractionOfViewport] is how much of the
 * visible width that distance covers. Mirrors MapKit `showsScale` using the camera span rather than
 * a MapLibre plugin.
 */
data class MapScaleBarModel(val label: String, val fractionOfViewport: Float)
