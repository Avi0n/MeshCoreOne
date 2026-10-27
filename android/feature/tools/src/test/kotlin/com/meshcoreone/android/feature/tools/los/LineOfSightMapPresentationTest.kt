// AndroidOnly: WP-315 deterministic map presentation, viewport and pointer-coordinate acceptance tests.
package com.meshcoreone.android.feature.tools.los

import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.MapCamera
import com.meshcoreone.android.core.maps.MapPinStyle
import com.meshcoreone.android.core.maps.MapProjectionResult
import com.meshcoreone.android.core.model.Coordinate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class LineOfSightMapPresentationTest {
    @Test
    fun `presentation preserves operator camera while analysis content changes`() {
        val renderer = LineOfSightMapPresentation()
        val camera = MapCamera(GeoPoint(37.75, -122.35), 0.5, 0.7)
        val selected = LineOfSightState(
            pointA = SelectedPoint(Coordinate(37.7, -122.4), null, groundElevation = 10.0),
            pointB = SelectedPoint(Coordinate(37.8, -122.3), null, groundElevation = 20.0),
        )
        val analyzed = selected.copy(
            analysisStatus = AnalysisStatus.Result(
                PathAnalysisResult(10_000.0, 110.0, 0.0, 110.0, ClearanceStatus.CLEAR, 90.0, emptyList(), 906.0, 1.0),
            ),
        )

        assertEquals(camera, renderer.build(selected, camera, true).camera)
        assertEquals(camera, renderer.build(analyzed, camera, true).camera)
    }

    @Test
    fun `endpoint identities stay stable and relocation dims only affected relay segments`() {
        val renderer = LineOfSightMapPresentation()
        val direct = LineOfSightState(
            pointA = SelectedPoint(Coordinate(37.7, -122.4), null, groundElevation = 10.0),
            pointB = SelectedPoint(Coordinate(37.8, -122.3), null, groundElevation = 20.0),
        )
        val first = renderer.build(direct, null, true)
        val second = renderer.build(direct.copy(frequencyMHz = 915.0), null, true)
        assertEquals(first.points.map { it.id }, second.points.map { it.id })
        assertEquals(0.7f, first.lines.single().opacity)

        val relay = direct.copy(
            repeaterPoint = RepeaterPoint(Coordinate(37.75, -122.35)),
            relocatingPoint = PointID.POINT_A,
        )
        val lines = renderer.build(relay, null, true).lines.associateBy { it.id }
        assertEquals(0.3f, lines.getValue("los-ar").opacity)
        assertEquals(0.7f, lines.getValue("los-rb").opacity)
    }

    @Test
    fun `obstruction pins use analyzed terrain positions and disappear with a relay`() {
        val renderer = LineOfSightMapPresentation()
        val profile = listOf(
            ElevationSample(Coordinate(0.0, 0.0), 10.0, 0.0),
            ElevationSample(Coordinate(0.0, 1.0), 20.0, 1_000.0),
        )
        val obstruction = ObstructionPoint(500.0, 4.0, 20.0)
        val state = LineOfSightState(
            pointA = SelectedPoint(profile.first().coordinate, null, groundElevation = 10.0),
            pointB = SelectedPoint(profile.last().coordinate, null, groundElevation = 20.0),
            elevationProfile = profile,
            analysisStatus = AnalysisStatus.Result(
                PathAnalysisResult(
                    1_000.0, 90.0, 4.0, 94.0, ClearanceStatus.PARTIAL_OBSTRUCTION,
                    20.0, listOf(obstruction), 906.0, 1.0,
                ),
            ),
        )

        val marker = renderer.build(state, null, true).points.single { it.style == MapPinStyle.OBSTRUCTION }
        assertEquals(0.0, marker.position.latitude)
        assertEquals(0.5, marker.position.longitude)
        assertTrue(renderer.build(state.copy(repeaterPoint = RepeaterPoint(Coordinate(0.0, 0.5))), null, true)
            .points.none { it.style == MapPinStyle.OBSTRUCTION })
    }

    @Test
    fun `camera fit adds padding`() {
        val fitted = assertNotNull(
            LineOfSightMapPresentation.fit(listOf(Coordinate(40.0, -105.0), Coordinate(41.0, -103.0))),
        )
        assertEquals(1.5, fitted.latitudeSpan)
        assertEquals(3.0, fitted.longitudeSpan)
        assertNotEquals(LineOfSightMapPresentation.WORLD_CAMERA, fitted)
    }

    @Test
    fun `pointer coordinate comes only from a live projection value`() {
        val value = LineOfSightMapPresentation.coordinateFrom(MapProjectionResult.Value(GeoPoint(40.5, -104.0)))
        assertEquals(Coordinate(40.5, -104.0), value)
        listOf(MapProjectionResult.NotReady, MapProjectionResult.OutsideViewport, MapProjectionResult.InvalidCoordinate)
            .forEach { assertNull(LineOfSightMapPresentation.coordinateFrom(it)) }
    }
}
