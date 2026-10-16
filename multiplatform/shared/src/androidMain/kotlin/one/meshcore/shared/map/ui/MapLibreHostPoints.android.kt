package one.meshcore.shared.map.ui

import androidx.compose.runtime.getValue
import one.meshcore.shared.map.MapClustering
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.featureProperties
import one.meshcore.shared.map.partitionByClusterable
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Splits the pins across a clustered source and a fixed one, mirroring iOS. Node pins merge into
 * count bubbles at low zoom; dropped pins, path hops and tool markers stay put, so toggling one of
 * them never re-clusters the rest.
 */
internal fun MapLibreHostState.applyPoints(style: Style, points: List<MapPoint>) {
    ensurePinIcons(style)
    ensureHopIcons(style, points)
    val partition = points.partitionByClusterable()
    applyClusterablePoints(style, partition.clusterable)
    applyFixedPoints(style, partition.fixed)
}

private fun MapLibreHostState.applyClusterablePoints(style: Style, points: List<MapPoint>) {
    val collection = pointFeatures(points)
    val existing = style.getSource(SOURCE_ID) as? GeoJsonSource
    if (existing != null) {
        existing.setGeoJson(collection)
        return
    }
    style.addSource(
        GeoJsonSource(
            SOURCE_ID,
            collection,
            GeoJsonOptions()
                .withCluster(true)
                .withClusterRadius(MapClustering.CLUSTER_RADIUS_PX)
                .withClusterMaxZoom(MapClustering.MAX_ZOOM_FOR_CLUSTERING),
        ),
    )
    MapPinLayers.addClusterLayers(style)
    MapPinLayers.addPinLayers(
        style = style,
        ids = PinLayerIds(
            sourceId = SOURCE_ID,
            circleLayerId = LAYER_ID,
            iconLayerId = ICON_LAYER_ID,
            labelLayerId = LABEL_LAYER_ID,
            badgeLayerId = BADGE_LAYER_ID,
        ),
        filter = Expression.not(Expression.has(PROP_POINT_COUNT)),
    )
}

private fun MapLibreHostState.applyFixedPoints(style: Style, points: List<MapPoint>) {
    val collection = pointFeatures(points)
    val existing = style.getSource(FIXED_SOURCE_ID) as? GeoJsonSource
    if (existing != null) {
        existing.setGeoJson(collection)
        return
    }
    style.addSource(GeoJsonSource(FIXED_SOURCE_ID, collection))
    MapPinLayers.addPinLayers(
        style = style,
        ids = PinLayerIds(
            sourceId = FIXED_SOURCE_ID,
            circleLayerId = FIXED_LAYER_ID,
            iconLayerId = FIXED_ICON_LAYER_ID,
            labelLayerId = FIXED_LABEL_LAYER_ID,
            badgeLayerId = FIXED_BADGE_LAYER_ID,
        ),
        filter = null,
    )
}

private fun MapLibreHostState.pointFeatures(points: List<MapPoint>): FeatureCollection {
    val features = points.map { point ->
        val properties = point.featureProperties()
        Feature.fromGeometry(
            Point.fromLngLat(point.coordinate.longitude, point.coordinate.latitude),
        ).also { feature ->
            feature.addStringProperty(PROP_ID, properties.id)
            feature.addStringProperty(PROP_PIN_STYLE, properties.pinStyle)
            feature.addStringProperty(PROP_ICON, properties.icon)
            feature.addStringProperty(PROP_ANCHOR, properties.anchor)
            feature.addStringProperty(PROP_LABEL, properties.label)
            // Both stay absent when the point carries none: the badge layer filters on the attribute
            // and the label sort key falls back, as the iOS attribute dictionary does.
            properties.hopIndex?.let { feature.addNumberProperty(PROP_HOP_INDEX, it) }
            properties.badgeText?.let { feature.addStringProperty(PROP_BADGE_TEXT, it) }
        }
    }
    return FeatureCollection.fromFeatures(features)
}
