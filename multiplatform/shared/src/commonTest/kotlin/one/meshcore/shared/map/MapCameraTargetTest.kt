package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Camera framing re-fires on every request, mirroring iOS `cameraRegionVersion`. Region equality alone
 * would make "Center all" a one-shot control once the user has panned away.
 */
class MapCameraTargetTest {
    private val region = MapCameraRegion(center = LatLng(37.33, -122.03), spanLatDeg = 0.01, spanLonDeg = 0.01)
    private val other = MapCameraRegion(center = LatLng(51.5, -0.12), spanLatDeg = 0.02, spanLonDeg = 0.02)

    @Test
    fun requestingTheSameRegionTwiceBumpsTheVersionEachTime() {
        val first = MapCameraTarget().requesting(region)
        val second = first.requesting(region)
        assertEquals(region, second.region)
        assertEquals(1, first.version)
        assertEquals(2, second.version)
    }

    @Test
    fun repeatedRequestForAnUnchangedRegionStillApplies() {
        val first = MapCameraTarget().requesting(region)
        val second = first.requesting(region)
        assertTrue(
            shouldApplyCameraTarget(
                target = second.region,
                version = second.version,
                lastTarget = first.region,
                lastVersion = first.version,
            ),
        )
    }

    @Test
    fun recompositionWithNothingNewDoesNotReapply() {
        val target = MapCameraTarget().requesting(region)
        assertFalse(
            shouldApplyCameraTarget(
                target = target.region,
                version = target.version,
                lastTarget = target.region,
                lastVersion = target.version,
            ),
        )
    }

    @Test
    fun unversionedCallersKeepTheRegionEqualityDedupe() {
        assertTrue(shouldApplyCameraTarget(target = region, version = 0, lastTarget = other, lastVersion = 0))
        assertFalse(shouldApplyCameraTarget(target = region, version = 0, lastTarget = region, lastVersion = 0))
    }

    @Test
    fun noTargetNeverApplies() {
        assertFalse(shouldApplyCameraTarget(target = null, version = 7, lastTarget = region, lastVersion = 0))
    }
}
