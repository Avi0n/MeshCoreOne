// PortedFrom: MC1/Views/Tools/LineOfSight/LineOfSightViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/LineOfSight/LineOfSightView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.los

import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.MapAvailability
import com.meshcoreone.android.core.maps.MapCamera
import com.meshcoreone.android.core.maps.MapLibreOpenFreeMap
import com.meshcoreone.android.core.maps.MapLine
import com.meshcoreone.android.core.maps.MapLineStyle
import com.meshcoreone.android.core.maps.MapMarker
import com.meshcoreone.android.core.maps.MapPinStyle
import com.meshcoreone.android.core.maps.MapPresentationState
import com.meshcoreone.android.core.maps.MapProjectionResult
import com.meshcoreone.android.core.maps.MapStyle
import com.meshcoreone.android.core.model.Coordinate
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stable map identities and source-equivalent line/marker rebuilding. Camera is deliberately an
 * input so incoming elevation and analysis state cannot reset an operator-selected viewport.
 */
class LineOfSightMapPresentation(
    private val pointAMapId: UUID = UUID.randomUUID(),
    private val pointBMapId: UUID = UUID.randomUUID(),
    private val repeaterTargetMapId: UUID = UUID.randomUUID(),
) {
    fun build(state: LineOfSightState, camera: MapCamera?, labelsEnabled: Boolean): MapPresentationState {
        val markers = buildList {
            state.repeatersWithLocation.forEach { repeater ->
                val selectedAs = state.selectionState[repeater.id]?.selectedAs
                add(
                    MapMarker(
                        id = repeater.id,
                        position = GeoPoint(repeater.latitude, repeater.longitude),
                        style = when (selectedAs) {
                            PointID.POINT_A -> MapPinStyle.REPEATER_RING_BLUE
                            PointID.POINT_B -> MapPinStyle.REPEATER_RING_GREEN
                            PointID.REPEATER, null -> MapPinStyle.REPEATER
                        },
                        label = repeater.displayName.takeIf { labelsEnabled },
                        clusterable = selectedAs == null,
                    ),
                )
            }
            state.pointA?.takeIf { it.contact == null }?.let {
                add(MapMarker(pointAMapId, it.coordinate.toGeoPoint(), MapPinStyle.POINT_A, clusterable = false))
            }
            state.pointB?.takeIf { it.contact == null }?.let {
                add(MapMarker(pointBMapId, it.coordinate.toGeoPoint(), MapPinStyle.POINT_B, clusterable = false))
            }
            state.repeaterPoint?.let {
                add(MapMarker(repeaterTargetMapId, it.coordinate.toGeoPoint(), MapPinStyle.CROSSHAIR, clusterable = false))
            }
            val direct = (state.analysisStatus as? AnalysisStatus.Result)?.result
            if (state.repeaterPoint == null && direct?.clearanceStatus != null &&
                direct.clearanceStatus != ClearanceStatus.CLEAR
            ) {
                direct.obstructionPoints.forEach { obstruction ->
                    val fraction = obstruction.distanceFromAMeters / direct.distanceMeters
                    state.coordinateAt(fraction)?.let { coordinate ->
                        add(
                            MapMarker(
                                obstruction.id,
                                coordinate.toGeoPoint(),
                                MapPinStyle.OBSTRUCTION,
                                clusterable = false,
                            ),
                        )
                    }
                }
            }
        }
        val lines = buildLines(state)
        return MapPresentationState(
            availability = MapAvailability.Available(MapLibreOpenFreeMap.catalog),
            style = MapStyle.STANDARD,
            camera = camera,
            points = markers,
            lines = lines,
            isInteractive = true,
        )
    }

    private fun buildLines(state: LineOfSightState): List<MapLine> {
        val a = state.pointA?.coordinate?.toGeoPoint() ?: return emptyList()
        val b = state.pointB?.coordinate?.toGeoPoint() ?: return emptyList()
        val dim = 0.3f
        val active = 0.7f
        val repeater = state.repeaterPoint?.coordinate?.toGeoPoint()
        if (repeater == null) {
            return listOf(MapLine("los-ab", listOf(a, b), MapLineStyle.LOS, if (state.isRelocating) dim else active))
        }
        val relocating = state.relocatingPoint
        val ar = if (relocating == PointID.REPEATER || relocating == PointID.POINT_A) dim else active
        val rb = if (relocating == PointID.REPEATER || relocating == PointID.POINT_B) dim else active
        return listOf(
            MapLine("los-ar", listOf(a, repeater), MapLineStyle.LOS, ar),
            MapLine("los-rb", listOf(repeater, b), MapLineStyle.LOS, rb),
        )
    }

    companion object {
        val WORLD_CAMERA = MapCamera(GeoPoint(0.0, 0.0), latitudeSpan = 170.0, longitudeSpan = 350.0)

        fun fit(coordinates: List<Coordinate>): MapCamera? {
            if (coordinates.isEmpty()) return null
            val minLat = coordinates.minOf { it.latitude }
            val maxLat = coordinates.maxOf { it.latitude }
            val minLon = coordinates.minOf { it.longitude }
            val maxLon = coordinates.maxOf { it.longitude }
            val center = GeoPoint((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)
            val rawLatSpan = max(MIN_SPAN, (maxLat - minLat) * FIT_PADDING)
            val rawLonSpan = max(MIN_SPAN, (maxLon - minLon) * FIT_PADDING)
            return runCatching {
                MapCamera(
                    center = center,
                    latitudeSpan = min(rawLatSpan, (90.0 - abs(center.latitude)) * 2.0),
                    longitudeSpan = min(360.0, rawLonSpan),
                )
            }.getOrNull()
        }

        /**
         * Maps a live native-viewport projection result to a coordinate. Not-ready, outside-viewport and
         * invalid results select nothing; no coordinate is ever estimated from the reported camera.
         */
        fun coordinateFrom(result: MapProjectionResult<GeoPoint>): Coordinate? =
            (result as? MapProjectionResult.Value)?.let { runCatching { Coordinate(it.value.latitude, it.value.longitude) }.getOrNull() }

        private fun Coordinate.toGeoPoint() = GeoPoint(latitude, longitude)

        private const val FIT_PADDING = 1.5
        private const val MIN_SPAN = 0.01
    }
}
