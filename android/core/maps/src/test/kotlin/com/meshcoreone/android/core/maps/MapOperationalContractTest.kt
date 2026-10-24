// AndroidOnly: WP-312 Camera/projection lifecycle and semantic marker regressions, not native renderer execution.
package com.meshcoreone.android.core.maps

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class MapOperationalContractTest {
    @Test fun cameraPoseRoundTripsWithoutReplacingRotationTiltOrZoomWithSpans() {
        for (pose in listOf(MapCameraPose(3.0, 0.0, 0.0), MapCameraPose(18.0, 137.5, 60.0))) {
            val camera = MapCamera(GeoPoint(12.0, 179.9), 0.01, 0.02, pose)
            assertEquals(camera, MapCamera.decode(camera.encode()))
        }
        assertEquals(MapCamera(GeoPoint(1.0, 2.0), 3.0, 4.0), MapCamera.decode("1,2,3,4"))
        assertNull(MapCamera.decode("1,2,3,4,NaN,0,0"))
        assertNull(MapCamera.decode("1,2,3,4,10,0,90"))
    }

    @Test fun antimeridianFramingUsesTheShortArcAndPolarPointsKeepAPositiveSpan() {
        val camera = listOf(GeoPoint(10.0, 179.0), GeoPoint(10.0, -179.0)).boundingCamera()!!
        assertEquals(-180.0, camera.center.longitude, 0.00001)
        assertEquals(3.0, camera.longitudeSpan, 0.00001)
        assertTrue(listOf(GeoPoint(90.0, 0.0)).boundingCamera()!!.latitudeSpan > 0)
        assertEquals(-179.5, wrapLongitude(180.5), 0.0)
        assertEquals(179.5, wrapLongitude(-180.5), 0.0)
    }

    @Test fun projectionHandleDelegatesExactLocalPixelsAndCoordinatesAndDetachesOnUnmount() {
        val handle = MapProjectionHandle()
        val pixel = MapScreenPoint(123.25f, 405.5f)
        val coordinate = GeoPoint(10.1, -179.9)
        assertEquals(MapProjectionResult.NotReady, handle.coordinateAt(pixel))
        var picked: MapScreenPoint? = null
        var projected: GeoPoint? = null
        handle.bind(object : MapCoordinateProjection {
            override fun coordinateAt(point: MapScreenPoint): MapProjectionResult<GeoPoint> {
                picked = point
                return MapProjectionResult.Value(coordinate)
            }
            override fun screenPointFor(coordinate: GeoPoint): MapProjectionResult<MapScreenPoint> {
                projected = coordinate
                return MapProjectionResult.Value(pixel)
            }
        })
        assertEquals(MapProjectionResult.Value(coordinate), handle.coordinateAt(pixel))
        assertEquals(pixel, picked)
        assertEquals(MapProjectionResult.Value(pixel), handle.screenPointFor(coordinate))
        assertEquals(coordinate, projected)
        handle.bind(null)
        assertEquals(MapProjectionResult.NotReady, handle.coordinateAt(pixel))
        assertEquals(MapProjectionResult.NotReady, handle.screenPointFor(coordinate))
    }

    @Test fun badgeHopAndEndpointsRemainIndependentOfNameVisibilityAndClustering() {
        fun marker(style: MapPinStyle, hop: Int? = null, badge: String? = null) =
            MapMarker(UUID.randomUUID(), GeoPoint(1.0, 2.0), style, "Node", false, hop, badge)
        val hop = marker(MapPinStyle.REPEATER_HOP, 12, "-8 dB")
        assertEquals("12", hop.symbolText)
        assertEquals("Node, 12, -8 dB", hop.semanticText)
        assertEquals("A", marker(MapPinStyle.POINT_A).symbolText)
        assertEquals("B", marker(MapPinStyle.POINT_B).symbolText)
        val endpoints = listOf(marker(MapPinStyle.POINT_A), hop, marker(MapPinStyle.POINT_B))
        assertEquals(endpoints, endpoints.partitionForClustering(true).fixed)
        assertEquals("Node", marker(MapPinStyle.CONTACT_CHAT).semanticText)
    }
}
