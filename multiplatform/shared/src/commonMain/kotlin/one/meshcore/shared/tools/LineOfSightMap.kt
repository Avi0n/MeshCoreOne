package one.meshcore.shared.tools

import one.meshcore.shared.contact.displayName
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LineStyle
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.PinStyle

fun LineOfSightViewModel.elevationAt(pathFraction: Double): Double? {
    val indices = interpolationIndices(pathFraction) ?: return null
    val lower = elevationProfile[indices.lower].elevation
    val upper = elevationProfile[indices.upper].elevation
    return lower + indices.t * (upper - lower)
}

fun LineOfSightViewModel.coordinateAt(pathFraction: Double): LatLng? {
    val indices = interpolationIndices(pathFraction) ?: return null
    val lower = elevationProfile[indices.lower].coordinate
    val upper = elevationProfile[indices.upper].coordinate
    return LatLng(
        latitude = lower.latitude + indices.t * (upper.latitude - lower.latitude),
        longitude = lower.longitude + indices.t * (upper.longitude - lower.longitude),
    )
}

internal fun LineOfSightViewModel.rebuildMapPoints() {
    val points = mutableListOf<MapPoint>()
    points += buildRepeaterMapPoints()
    points += buildEndpointMapPoints()
    repeaterPoint?.let { target ->
        points += MapPoint(
            id = repeaterTargetMapId,
            coordinate = target.coordinate,
            pinStyle = PinStyle.CROSSHAIR,
            label = null,
            isClusterable = false,
        )
    }
    points += buildObstructionMapPoints()
    mapPoints = points
}

internal fun LineOfSightViewModel.buildRepeaterMapPoints(): List<MapPoint> {
    val aId = pointA?.contact?.id
    val bId = pointB?.contact?.id
    return repeatersWithLocation.map { repeater ->
        val selectedAs = when (repeater.id) {
            aId -> LosPointId.POINT_A
            bId -> LosPointId.POINT_B
            else -> null
        }
        val style = when (selectedAs) {
            LosPointId.POINT_A -> PinStyle.REPEATER_RING_BLUE
            LosPointId.POINT_B -> PinStyle.REPEATER_RING_GREEN
            else -> PinStyle.REPEATER
        }
        MapPoint(
            id = repeater.id.value,
            coordinate = LatLng(repeater.latitude, repeater.longitude),
            pinStyle = style,
            label = if (showLabels) repeater.displayName else null,
            isClusterable = selectedAs == null,
        )
    }
}

internal fun LineOfSightViewModel.buildEndpointMapPoints(): List<MapPoint> {
    val points = mutableListOf<MapPoint>()
    val a = pointA
    if (a != null && a.contact == null) {
        points += MapPoint(
            id = pointAMapId,
            coordinate = a.coordinate,
            pinStyle = PinStyle.POINT_A,
            label = null,
            isClusterable = false,
        )
    }
    val b = pointB
    if (b != null && b.contact == null) {
        points += MapPoint(
            id = pointBMapId,
            coordinate = b.coordinate,
            pinStyle = PinStyle.POINT_B,
            label = null,
            isClusterable = false,
        )
    }
    return points
}

internal fun LineOfSightViewModel.buildObstructionMapPoints(): List<MapPoint> {
    if (repeaterPoint != null) return emptyList()
    val result = (analysisStatus as? LosAnalysisStatus.Result)?.value ?: return emptyList()
    if (result.clearanceStatus == one.meshcore.shared.rf.ClearanceStatus.Clear) return emptyList()
    if (result.distanceMeters <= 0) return emptyList()
    return result.peakObstructionPerRegion.mapNotNull { obstruction ->
        val fraction = obstruction.distanceFromAMeters / result.distanceMeters
        val coordinate = coordinateAt(fraction) ?: return@mapNotNull null
        MapPoint(
            id = "obs-${obstruction.distanceFromAMeters}",
            coordinate = coordinate,
            pinStyle = PinStyle.OBSTRUCTION,
            label = null,
            isClusterable = false,
        )
    }
}

internal fun LineOfSightViewModel.rebuildMapLines() {
    val a = pointA?.coordinate
    val b = pointB?.coordinate
    if (a == null || b == null) {
        mapLines = emptyList()
        return
    }
    val activeOpacity = LineOfSightViewModel.LINE_ACTIVE_OPACITY
    val dimOpacity = LineOfSightViewModel.LINE_DIM_OPACITY
    val r = repeaterPoint?.coordinate
    mapLines = if (r != null) {
        val opacityAr = if (relocatingPoint == LosPointId.POINT_A) dimOpacity else activeOpacity
        val opacityRb = if (relocatingPoint == LosPointId.POINT_B) dimOpacity else activeOpacity
        val repDim = relocatingPoint == LosPointId.REPEATER
        listOf(
            MapLine(
                id = "los-ar",
                coordinates = listOf(a, r),
                style = LineStyle.LOS,
                opacity = if (repDim) dimOpacity else opacityAr,
            ),
            MapLine(
                id = "los-rb",
                coordinates = listOf(r, b),
                style = LineStyle.LOS,
                opacity = if (repDim) dimOpacity else opacityRb,
            ),
        )
    } else {
        val opacity = if (relocatingPoint != null) dimOpacity else activeOpacity
        listOf(
            MapLine(
                id = "los-ab",
                coordinates = listOf(a, b),
                style = LineStyle.LOS,
                opacity = opacity,
            ),
        )
    }
}
