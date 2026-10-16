package one.meshcore.shared.map.ui

import androidx.compose.runtime.getValue
import one.meshcore.shared.map.LineStyle
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.featureProperties
import one.meshcore.shared.map.mapLinePaint
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import android.graphics.Color as AndroidColor

internal fun MapLibreHostState.applyLines(style: Style, lines: List<MapLine>) {
    val features =
        lines.mapNotNull { line ->
            if (line.coordinates.size < 2) return@mapNotNull null
            val positions =
                line.coordinates.map { c ->
                    Point.fromLngLat(c.longitude, c.latitude)
                }
            val properties = line.featureProperties()
            Feature.fromGeometry(LineString.fromLngLats(positions)).also { feature ->
                feature.addStringProperty(PROP_ID, properties.id)
                feature.addStringProperty(PROP_LINE_STYLE, properties.lineStyle)
                feature.addNumberProperty(PROP_SEGMENT_OPACITY, properties.opacity)
            }
        }
    val collection = FeatureCollection.fromFeatures(features)
    val existing = style.getSource(LINE_SOURCE_ID) as? GeoJsonSource
    if (existing != null) {
        existing.setGeoJson(collection)
    } else {
        style.addSource(GeoJsonSource(LINE_SOURCE_ID, collection))
        addLineStyleLayers(style)
    }
}

/**
 * One casing + stroke pair per [LineStyle]. MapLibre cannot data-drive `line-dasharray`, so a
 * single layer would force every hop — sight line, untraced, measured — to the same solid stroke.
 */
private fun MapLibreHostState.addLineStyleLayers(style: Style) {
    val color = MapPinLayers.lineStyleColorExpression()
    val segmentOpacity = Expression.get(PROP_SEGMENT_OPACITY)
    for (lineStyle in LineStyle.entries) {
        val paint = lineStyle.mapLinePaint()
        val filter = Expression.eq(
            Expression.get(PROP_LINE_STYLE),
            Expression.literal(lineStyle.rawValue),
        )
        style.addLayer(
            LineLayer("$LINE_LAYER_PREFIX-${lineStyle.rawValue}-casing", LINE_SOURCE_ID)
                .withFilter(filter)
                .withLinePaint(
                    width = paint.casingWidth,
                    color = Expression.color(AndroidColor.WHITE),
                    opacity = Expression.literal(LINE_CASING_OPACITY),
                    dash = paint.casingDash,
                ),
        )
        style.addLayer(
            LineLayer("$LINE_LAYER_PREFIX-${lineStyle.rawValue}", LINE_SOURCE_ID)
                .withFilter(filter)
                .withLinePaint(
                    width = paint.width,
                    color = color,
                    opacity = segmentOpacity,
                    dash = paint.dash,
                ),
        )
    }
}

private fun LineLayer.withLinePaint(
    width: Float,
    color: Expression,
    opacity: Expression,
    dash: List<Float>?,
): LineLayer {
    val widthProp = PropertyFactory.lineWidth(width)
    val colorProp = PropertyFactory.lineColor(color)
    val opacityProp = PropertyFactory.lineOpacity(opacity)
    val join = PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
    val cap = PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
    return if (dash == null) {
        withProperties(widthProp, colorProp, opacityProp, join, cap)
    } else {
        withProperties(
            widthProp,
            colorProp,
            opacityProp,
            join,
            cap,
            PropertyFactory.lineDasharray(dash.toTypedArray()),
        )
    }
}
