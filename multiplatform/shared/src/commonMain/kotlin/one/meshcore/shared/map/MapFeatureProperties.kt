package one.meshcore.shared.map

/**
 * The attributes one [MapPoint] contributes to a GeoJSON source. A host writes these verbatim, so a
 * value dropped here never reaches a layer: the hop ordinal drives the numbered sprite and the label
 * draw order, and [badgeText] is what the SNR/distance pill reads. Mirrors the iOS
 * `MC1MapView.Coordinator.pointFeature(for:)` attribute set.
 */
data class MapPointFeatureProperties(
    val id: String,
    val pinStyle: String,
    val icon: String,
    /** Where the sprite sits on the coordinate; a dropped anchor points every teardrop half an icon off. */
    val anchor: String,
    val label: String,
    val hopIndex: Int?,
    val badgeText: String?,
)

fun MapPoint.featureProperties(): MapPointFeatureProperties = MapPointFeatureProperties(
    id = id,
    pinStyle = pinStyle.rawValue,
    icon = mapPinIconName(pinStyle, hopIndex),
    anchor = pinStyle.mapPinAnchor().rawValue,
    label = label.orEmpty(),
    hopIndex = hopIndex,
    badgeText = badgeText,
)

/**
 * The attributes one [MapLine] contributes to a GeoJSON source. [opacity] is the per-segment value the
 * line layer reads, so the "dim the other links while relocating a point" cue survives the boundary.
 */
data class MapLineFeatureProperties(val id: String, val lineStyle: String, val opacity: Double)

/**
 * A non-finite or out-of-range opacity is normalized before it reaches a native renderer that cannot
 * defend itself, the same trust boundary [validatedBounds] holds for camera targets.
 */
fun MapLine.featureProperties(): MapLineFeatureProperties = MapLineFeatureProperties(
    id = id,
    lineStyle = style.rawValue,
    opacity = if (opacity.isFinite()) opacity.coerceIn(0.0, FULLY_OPAQUE) else FULLY_OPAQUE,
)

private const val FULLY_OPAQUE = 1.0
