// AndroidOnly: WP-312 Actual mounted native MapLibre projection matrix; source is not evidence of execution.
package com.meshcoreone.android.app.map

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.maps.*
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapProjectionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativePickingTracksPanZoomRotationTiltResizeAndAntimeridianWithoutSpanInterpolation() {
        assertTrue("Native MapLibre requires a supported 64-bit process", MapLibreRuntime.isSupported)
        val handle = MapProjectionHandle()
        val reported = AtomicReference<MapCamera?>()
        val first = Frame(GeoPoint(20.0, 10.0), MapCameraPose(12.0, 0.0, 0.0), 320, 500)
        val frame = mutableStateOf(first)
        compose.runOnUiThread {
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    val current = frame.value
                    Box(Modifier.width(current.width.dp).height(current.height.dp)) {
                        MapLibreMapSurface(
                            MapPresentationState(
                                availability = MapAvailability.Available(MapLibreOpenFreeMap.catalog),
                                camera = MapCamera(current.center, 1.0, 1.0, current.pose),
                            ),
                            false, false, "WP312 native projection",
                            { reported.set(it) }, { error("No marker selection requested") },
                            projection = handle,
                        )
                    }
                }
            }
        }
        val frames = listOf(
            first,
            first.copy(center = GeoPoint(21.0, 11.0)),
            first.copy(pose = MapCameraPose(15.0, 0.0, 0.0)),
            first.copy(pose = MapCameraPose(15.0, 180.0, 0.0)),
            first.copy(pose = MapCameraPose(15.0, 137.0, 55.0), width = 280, height = 400),
            first.copy(center = GeoPoint(20.0, 179.9995), pose = MapCameraPose(14.0, 37.0, 45.0)),
            first.copy(center = GeoPoint(20.0, -179.9995), pose = MapCameraPose(14.0, 217.0, 45.0), width = 280, height = 400),
        )
        frames.forEachIndexed { index, current ->
            compose.runOnUiThread { frame.value = current }
            // This waits for the real provider/style and native camera, never a synthetic readiness delay.
            compose.waitUntil(30_000) {
                val camera = reported.get()
                camera != null && camera.center.distanceMetersTo(current.center) < 1.0 &&
                    camera.pose?.let {
                        kotlin.math.abs(it.zoom - current.pose.zoom) < 0.001 &&
                            kotlin.math.abs(it.bearing - current.pose.bearing) < 0.001 &&
                            kotlin.math.abs(it.tilt - current.pose.tilt) < 0.001
                    } == true
            }
            compose.runOnUiThread {
                val origin = value(handle.screenPointFor(current.center))
                val pickedCenter = value(handle.coordinateAt(origin))
                assertTrue("frame $index center coordinate", current.center.distanceMetersTo(pickedCenter) < 1.0)
                val pixels = listOf(origin, MapScreenPoint(origin.x + 20f, origin.y), MapScreenPoint(origin.x, origin.y + 20f))
                pixels.forEach { pixel ->
                    val coordinate = value(handle.coordinateAt(pixel))
                    val restored = value(handle.screenPointFor(coordinate))
                    assertEquals("frame $index x round-trip", pixel.x, restored.x, 1f)
                    assertEquals("frame $index y round-trip", pixel.y, restored.y, 1f)
                }
                if (current.pose.bearing == 0.0 || current.pose.bearing == 180.0) {
                    val east = value(handle.screenPointFor(GeoPoint(current.center.latitude, current.center.longitude + 0.0001)))
                    assertEquals("rotation must change the eastward screen direction", current.pose.bearing == 0.0, east.x > origin.x)
                }
                if (kotlin.math.abs(current.center.longitude) > 179.0) {
                    val across = GeoPoint(20.0, -current.center.longitude)
                    val acrossPixel = value(handle.screenPointFor(across))
                    assertTrue("frame $index across antimeridian", across.distanceMetersTo(value(handle.coordinateAt(acrossPixel))) < 1.0)
                }
                assertEquals(MapProjectionResult.OutsideViewport, handle.coordinateAt(MapScreenPoint(-1f, -1f)))
            }
        }
        compose.runOnUiThread { compose.activity.setContent { } }
        compose.waitForIdle()
        compose.runOnUiThread {
            assertEquals(MapProjectionResult.NotReady, handle.coordinateAt(MapScreenPoint(1f, 1f)))
        }
    }

    private fun <T> value(result: MapProjectionResult<T>): T {
        assertTrue("Expected native projection value, got $result", result is MapProjectionResult.Value)
        return (result as MapProjectionResult.Value<T>).value
    }

    private data class Frame(val center: GeoPoint, val pose: MapCameraPose, val width: Int, val height: Int)
}
