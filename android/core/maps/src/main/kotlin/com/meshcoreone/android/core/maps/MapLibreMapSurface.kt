// AndroidOnly: WP-312 Lifecycle-correct Compose host for the approved MapLibre Native provider.
package com.meshcoreone.android.core.maps

import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.gson.JsonObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

@Composable
fun MapLibreMapSurface(
    presentation: MapPresentationState,
    clusteringEnabled: Boolean,
    labelsEnabled: Boolean,
    accessibilityLabel: String,
    onCameraChanged: (MapCamera) -> Unit,
    onMarkerSelected: (MapMarker) -> Unit,
    modifier: Modifier = Modifier,
    projection: MapProjectionHandle? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        MapLibreSurfaceController(context.applicationContext, accessibilityLabel, onCameraChanged, onMarkerSelected)
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = {
                    controller.initialize()
                    controller.mapView
                },
                update = {
                    controller.callbacks = MapLibreSurfaceCallbacks(onCameraChanged, onMarkerSelected)
                    controller.mapView.contentDescription = listOf(accessibilityLabel)
                        .plus(presentation.points.map { it.semanticText }).filter(String::isNotBlank).joinToString("; ")
                    controller.bindProjection(projection)
                    controller.render(presentation, clusteringEnabled, labelsEnabled)
                },
                modifier = Modifier.fillMaxSize(),
            )
            overlay()
        }
        MapAttribution(presentation.attribution)
    }

    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.mapView.onStart()
                Lifecycle.Event.ON_RESUME -> controller.mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.mapView.onPause()
                Lifecycle.Event.ON_STOP -> controller.mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.mapView.onStop()
            controller.destroy()
        }
    }
}

private data class MapLibreSurfaceCallbacks(
    val camera: (MapCamera) -> Unit,
    val marker: (MapMarker) -> Unit,
)

private class MapLibreSurfaceController(
    context: android.content.Context,
    accessibilityLabel: String,
    camera: (MapCamera) -> Unit,
    marker: (MapMarker) -> Unit,
) {
    val mapView = MapView(context).apply {
        contentDescription = accessibilityLabel
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    var callbacks = MapLibreSurfaceCallbacks(camera, marker)
    private var map: MapLibreMap? = null
    private var loadedStyle: Style? = null
    private var pending: Triple<MapPresentationState, Boolean, Boolean>? = null
    private var pointsById = emptyMap<String, MapMarker>()
    private var lastReportedCamera: MapCamera? = null
    private var lastAppliedCamera: MapCamera? = null
    private var initialized = false
    private var destroyed = false
    private var projectionHandle: MapProjectionHandle? = null
    private val nativeProjection = object : MapCoordinateProjection {
        override fun coordinateAt(point: MapScreenPoint): MapProjectionResult<GeoPoint> {
            val ready = map ?: return MapProjectionResult.NotReady
            if (mapView.width <= 0 || mapView.height <= 0) return MapProjectionResult.NotReady
            if (point.x < 0 || point.y < 0 || point.x > mapView.width || point.y > mapView.height) {
                return MapProjectionResult.OutsideViewport
            }
            val coordinate = ready.projection.fromScreenLocation(PointF(point.x, point.y))
            if (!coordinate.latitude.isFinite() || !coordinate.longitude.isFinite() || coordinate.latitude !in -90.0..90.0) {
                return MapProjectionResult.InvalidCoordinate
            }
            return MapProjectionResult.Value(GeoPoint(coordinate.latitude, wrapLongitude(coordinate.longitude)))
        }

        override fun screenPointFor(coordinate: GeoPoint): MapProjectionResult<MapScreenPoint> {
            val ready = map ?: return MapProjectionResult.NotReady
            if (mapView.width <= 0 || mapView.height <= 0) return MapProjectionResult.NotReady
            val point = ready.projection.toScreenLocation(LatLng(coordinate.latitude, coordinate.longitude))
            if (!point.x.isFinite() || !point.y.isFinite()) return MapProjectionResult.InvalidCoordinate
            return if (point.x < 0 || point.y < 0 || point.x > mapView.width || point.y > mapView.height) {
                MapProjectionResult.OutsideViewport
            } else MapProjectionResult.Value(MapScreenPoint(point.x, point.y))
        }
    }

    fun bindProjection(handle: MapProjectionHandle?) {
        if (projectionHandle !== handle) projectionHandle?.unbind(nativeProjection)
        projectionHandle = handle
        handle?.bind(nativeProjection)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        projectionHandle?.unbind(nativeProjection)
        projectionHandle = null
        map = null
        loadedStyle = null
        mapView.onDestroy()
    }

    fun initialize() {
        if (initialized) return
        initialized = true
        mapView.onCreate(Bundle())
        mapView.getMapAsync { ready ->
            if (destroyed) return@getMapAsync
            map = ready
            ready.uiSettings.isLogoEnabled = false
            ready.uiSettings.isAttributionEnabled = false
            ready.addOnCameraIdleListener {
                val bounds = ready.projection.visibleRegion.latLngBounds
                val center = ready.cameraPosition.target ?: return@addOnCameraIdleListener
                val position = ready.cameraPosition
                MapCamera(
                        center = GeoPoint(center.latitude, wrapLongitude(center.longitude)),
                        latitudeSpan = bounds.latitudeSpan.coerceAtLeast(0.000001),
                        longitudeSpan = bounds.longitudeSpan.coerceAtLeast(0.000001),
                        pose = MapCameraPose(position.zoom, position.bearing, position.tilt),
                    )
                .let {
                    lastReportedCamera = it
                    callbacks.camera(it)
                }
            }
            ready.addOnMapClickListener { coordinate ->
                if (pending?.first?.isInteractive == false) return@addOnMapClickListener false
                val screen = ready.projection.toScreenLocation(coordinate)
                if (ready.queryRenderedFeatures(screen, CLUSTERS_LAYER).isNotEmpty()) {
                    ready.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(coordinate, (ready.cameraPosition.zoom + 2.0).coerceAtMost(18.0)),
                        250,
                    )
                    return@addOnMapClickListener true
                }
                val selected = ready.queryRenderedFeatures(screen, POINTS_LAYER, FIXED_LAYER, SYMBOLS_LAYER, FIXED_SYMBOLS_LAYER, BADGES_LAYER, FIXED_BADGES_LAYER)
                    .firstNotNullOfOrNull { feature -> feature.id()?.let(pointsById::get) }
                selected?.let(callbacks.marker)
                selected != null
            }
            ready.setStyle(MapLibreOpenFreeMap.STYLE_URI) { style ->
                if (destroyed) return@setStyle
                loadedStyle = style
                installLayers(style)
                pending?.let { (state, clusters, labels) -> applyState(state, clusters, labels) }
            }
        }
    }

    fun render(state: MapPresentationState, clusters: Boolean, labels: Boolean) {
        pending = Triple(state, clusters, labels)
        if (state.style != MapStyle.STANDARD) return
        loadedStyle?.let { applyState(state, clusters, labels) }
    }

    private fun installLayers(style: Style) {
        val clusterOptions = GeoJsonOptions().withCluster(true).withClusterRadius(44).withClusterMaxZoom(13)
        style.addSource(GeoJsonSource(CLUSTERED_SOURCE, emptyFeatures(), clusterOptions))
        style.addSource(GeoJsonSource(FIXED_SOURCE, emptyFeatures()))
        style.addSource(GeoJsonSource(LINES_SOURCE, emptyFeatures()))
        style.addLayer(
            CircleLayer(CLUSTERS_LAYER, CLUSTERED_SOURCE)
                .withFilter(org.maplibre.android.style.expressions.Expression.has("point_count"))
                .withProperties(circleColor("#2255A4"), circleRadius(20f), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f)),
        )
        style.addLayer(
            SymbolLayer(CLUSTER_COUNT_LAYER, CLUSTERED_SOURCE)
                .withFilter(org.maplibre.android.style.expressions.Expression.has("point_count"))
                .withProperties(textField(get("point_count_abbreviated")), textFont(arrayOf("Noto Sans Regular")), textColor(Color.WHITE), textSize(13f)),
        )
        style.addLayer(
            CircleLayer(POINTS_LAYER, CLUSTERED_SOURCE)
                .withFilter(org.maplibre.android.style.expressions.Expression.not(org.maplibre.android.style.expressions.Expression.has("point_count")))
                .withProperties(circleColor(get("color")), circleRadius(8f), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f)),
        )
        style.addLayer(
            SymbolLayer(CLUSTER_LABELS_LAYER, CLUSTERED_SOURCE)
                .withFilter(org.maplibre.android.style.expressions.Expression.not(org.maplibre.android.style.expressions.Expression.has("point_count")))
                .withProperties(
                    textField(get("label")),
                    textColor(Color.BLACK),
                    textHaloColor(Color.WHITE),
                    textHaloWidth(2f),
                    textSize(12f),
                    textFont(arrayOf("Noto Sans Regular")),
                    textOffset(arrayOf(0f, -1.8f)),
                ),
        )
        style.addLayer(
            CircleLayer(FIXED_LAYER, FIXED_SOURCE)
                .withProperties(circleColor(get("color")), circleRadius(9f), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f)),
        )
        style.addLayer(
            LineLayer(LINES_LAYER, LINES_SOURCE)
                .withFilter(org.maplibre.android.style.expressions.Expression.neq(get("lineStyle"), org.maplibre.android.style.expressions.Expression.literal(MapLineStyle.TRACE_UNTRACED.name)))
                .withProperties(lineColor(get("color")), lineWidth(3f), lineOpacity(get("opacity"))),
        )
        style.addLayer(
            LineLayer(UNTRACED_LAYER, LINES_SOURCE)
                .withFilter(org.maplibre.android.style.expressions.Expression.eq(get("lineStyle"), org.maplibre.android.style.expressions.Expression.literal(MapLineStyle.TRACE_UNTRACED.name)))
                .withProperties(lineColor(get("color")), lineWidth(2f), lineDasharray(arrayOf(1.75f, 3.25f)), lineOpacity(get("opacity"))),
        )
        style.addLayer(
            SymbolLayer(LABELS_LAYER, FIXED_SOURCE)
                .withProperties(
                    textField(get("label")),
                    textColor(Color.BLACK),
                    textHaloColor(Color.WHITE),
                    textHaloWidth(2f),
                    textSize(12f),
                    textFont(arrayOf("Noto Sans Regular")),
                    textOffset(arrayOf(0f, -1.8f)),
                ),
        )
        listOf(CLUSTERED_SOURCE to SYMBOLS_LAYER, FIXED_SOURCE to FIXED_SYMBOLS_LAYER).forEach { (source, layer) ->
            style.addLayer(
                SymbolLayer(layer, source)
                    .withFilter(org.maplibre.android.style.expressions.Expression.not(org.maplibre.android.style.expressions.Expression.has("point_count")))
                    .withProperties(
                        textField(get("symbol")), textFont(arrayOf("Noto Sans Regular")),
                        textColor(Color.WHITE), textSize(12f), textAllowOverlap(true),
                    ),
            )
        }
        listOf(CLUSTERED_SOURCE to BADGES_LAYER, FIXED_SOURCE to FIXED_BADGES_LAYER).forEach { (source, layer) ->
            style.addLayer(
                SymbolLayer(layer, source)
                    .withFilter(org.maplibre.android.style.expressions.Expression.not(org.maplibre.android.style.expressions.Expression.has("point_count")))
                    .withProperties(
                        textField(get("badgeText")), textFont(arrayOf("Noto Sans Regular")),
                        textColor(Color.BLACK), textHaloColor(Color.WHITE), textHaloWidth(3f),
                        textSize(13f), textOffset(arrayOf(0f, 1.8f)), textAllowOverlap(true),
                    ),
            )
        }
    }

    private fun applyState(state: MapPresentationState, clusters: Boolean, labels: Boolean) {
        val style = loadedStyle ?: return
        map?.uiSettings?.let {
            it.isScrollGesturesEnabled = state.isInteractive
            it.isZoomGesturesEnabled = state.isInteractive
            it.isRotateGesturesEnabled = state.isInteractive
            it.isTiltGesturesEnabled = state.isInteractive
        }
        val partition = state.points.partitionForClustering(clusters)
        val clustered = if (clusters) partition.clusterable else emptyList()
        val fixed = if (clusters) partition.fixed else state.points
        pointsById = state.points.associateBy { it.id.toString() }
        style.getSourceAs<GeoJsonSource>(CLUSTERED_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(clustered.map(::pointFeature)))
        style.getSourceAs<GeoJsonSource>(FIXED_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(fixed.map(::pointFeature)))
        style.getSourceAs<GeoJsonSource>(LINES_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(state.lines.map(::lineFeature)))
        style.getLayerAs<SymbolLayer>(LABELS_LAYER)?.setProperties(textSize(if (labels) 12f else 0f))
        style.getLayerAs<SymbolLayer>(CLUSTER_LABELS_LAYER)?.setProperties(textSize(if (labels) 12f else 0f))
        state.camera?.takeIf { it != lastReportedCamera && it != lastAppliedCamera }?.let { camera ->
                lastAppliedCamera = camera
                val pose = camera.pose
                if (pose != null) {
                    map?.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder().target(LatLng(camera.center.latitude, camera.center.longitude))
                                .zoom(pose.zoom).bearing(pose.bearing).tilt(pose.tilt).build(),
                        ),
                    )
                } else {
                val halfLat = camera.latitudeSpan / 2.0
                val halfLon = camera.longitudeSpan / 2.0
                map?.moveCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        LatLngBounds.from(
                            (camera.center.latitude + halfLat).coerceAtMost(90.0),
                            camera.center.longitude + halfLon,
                            (camera.center.latitude - halfLat).coerceAtLeast(-90.0),
                            camera.center.longitude - halfLon,
                        ),
                        24,
                    ),
                )
                }
        }
    }

    private fun pointFeature(marker: MapMarker): Feature {
        val properties = JsonObject().apply {
            addProperty("label", marker.label.orEmpty())
            addProperty("color", marker.style.color)
            addProperty("symbol", marker.symbolText)
            addProperty("badgeText", marker.badgeText.orEmpty())
            marker.hopIndex?.let { addProperty("hopIndex", it) }
        }
        return Feature.fromGeometry(
            Point.fromLngLat(marker.position.longitude, marker.position.latitude),
            properties,
            marker.id.toString(),
        )
    }

    private fun lineFeature(line: MapLine): Feature {
        val properties = JsonObject().apply {
            addProperty("color", line.style.color)
            addProperty("opacity", line.opacity)
            addProperty("lineStyle", line.style.name)
        }
        return Feature.fromGeometry(
            LineString.fromLngLats(line.points.map { Point.fromLngLat(it.longitude, it.latitude) }),
            properties,
            line.id,
        )
    }

    private val MapPinStyle.color: String
        get() = when (this) {
            MapPinStyle.CONTACT_CHAT -> "#1677FF"
            MapPinStyle.CONTACT_REPEATER, MapPinStyle.REPEATER, MapPinStyle.REPEATER_HOP -> "#1B8A5A"
            MapPinStyle.CONTACT_ROOM -> "#8A3FFC"
            MapPinStyle.REPEATER_RING_BLUE -> "#2255A4"
            MapPinStyle.REPEATER_RING_GREEN -> "#1B8A5A"
            MapPinStyle.REPEATER_RING_WHITE -> "#6B7280"
            MapPinStyle.POINT_A, MapPinStyle.POINT_B, MapPinStyle.CROSSHAIR -> "#D97706"
            MapPinStyle.OBSTRUCTION -> "#B91C1C"
            MapPinStyle.BADGE, MapPinStyle.DROPPED_PIN -> "#C2410C"
            MapPinStyle.LOCATION_FIX, MapPinStyle.LOCATION_FIX_LATEST -> "#374151"
        }

    private val MapLineStyle.color: String
        get() = when (this) {
            MapLineStyle.TRACE_GOOD -> "#168A50"
            MapLineStyle.TRACE_MEDIUM -> "#D97706"
            MapLineStyle.TRACE_WEAK -> "#B91C1C"
            MapLineStyle.TRACE_UNTRACED -> "#6B7280"
            MapLineStyle.LOS, MapLineStyle.MESSAGE_PATH -> "#2255A4"
            MapLineStyle.LOCATION_TRAIL -> "#6B7280"
        }

    companion object {
        private const val CLUSTERED_SOURCE = "mesh-clustered"
        private const val FIXED_SOURCE = "mesh-fixed"
        private const val LINES_SOURCE = "mesh-lines"
        private const val CLUSTERS_LAYER = "mesh-clusters"
        private const val CLUSTER_COUNT_LAYER = "mesh-cluster-count"
        private const val POINTS_LAYER = "mesh-points"
        private const val CLUSTER_LABELS_LAYER = "mesh-cluster-labels"
        private const val FIXED_LAYER = "mesh-fixed-points"
        private const val LINES_LAYER = "mesh-lines-layer"
        private const val UNTRACED_LAYER = "mesh-untraced-lines"
        private const val LABELS_LAYER = "mesh-labels"
        private const val SYMBOLS_LAYER = "mesh-symbols"
        private const val FIXED_SYMBOLS_LAYER = "mesh-fixed-symbols"
        private const val BADGES_LAYER = "mesh-badges"
        private const val FIXED_BADGES_LAYER = "mesh-fixed-badges"
        private fun emptyFeatures(): FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    }
}
