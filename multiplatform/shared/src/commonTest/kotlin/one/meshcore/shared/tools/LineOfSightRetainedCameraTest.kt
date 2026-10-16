package one.meshcore.shared.tools

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.rf.ElevationSample
import one.meshcore.shared.rf.GeoCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class LineOfSightRetainedCameraTest {
    @Test
    fun idleStoresFullRegionWithoutIssuingCameraCommand() = runTest {
        val model = LineOfSightViewModel(backgroundScope)
        model.acceptUserCameraRegion(region)
        assertEquals(region, model.cameraRegion)
        assertEquals(0, model.cameraTargetVersion)
        assertNull(model.cameraTarget)
    }

    @Test
    fun recreationReplaysRetainedUserRegion() = runTest {
        val model = LineOfSightViewModel(backgroundScope)
        model.acceptUserCameraRegion(region)
        model.restoreCameraAfterMapRecreation()
        assertEquals(region, model.cameraTarget)
        assertEquals(1, model.cameraTargetVersion)
        model.consumeCameraTarget()
        assertEquals(region, model.cameraRegion)
        model.restoreCameraAfterMapRecreation()
        assertEquals(region, model.cameraTarget)
        assertEquals(2, model.cameraTargetVersion)
    }

    @Test
    fun programmaticFramingRetainsRegionAfterCommandConsumption() = runTest {
        val model = LineOfSightViewModel(backgroundScope)
        model.frameOnUser(region.center)
        val framed = model.cameraTarget
        model.consumeCameraTarget()
        assertEquals(framed, model.cameraRegion)
        assertEquals(1, model.cameraTargetVersion)
        assertNull(model.cameraTarget)
    }

    @Test
    fun emptyInitialFitRemainsAvailableAndUserPanConsumesIt() = runTest {
        val model = LineOfSightViewModel(backgroundScope)
        model.applyInitialRepeaterCameraFitIfNeeded()
        assertFalse(model.hasAppliedInitialCameraFit)
        model.acceptUserCameraRegion(region)
        model.applyInitialRepeaterCameraFitIfNeeded()
        assertEquals(region, model.cameraRegion)
        assertEquals(0, model.cameraTargetVersion)
        assertEquals(true, model.hasAppliedInitialCameraFit)
    }

    @Test
    fun explicitOfflineCommitEvenAtDefaultPreventsDeviceReseeding() = runTest {
        val model = LineOfSightViewModel(backgroundScope)
        val default = model.frequencyMHz
        model.setFrequencyMHz(default)
        model.configure({ null }, 868_000)
        assertEquals(default, model.frequencyMHz)
    }

    @Test
    fun staleConsumptionCannotClearNewFramingCommand() = runTest {
        val model = LineOfSightViewModel(backgroundScope)
        model.frameOnUser(LatLng(1.0, 2.0))
        val oldVersion = model.cameraTargetVersion
        model.frameOnUser(region.center)
        model.consumeCameraTarget(oldVersion)
        assertEquals(region.center, model.cameraTarget?.center)
    }

    @Test
    fun firstDeviceSeedPublishesWithoutRepeaterLoad() = runTest {
        val model = LineOfSightViewModel(backgroundScope)
        val revision = model.revision.value
        model.configure({ null }, 868_000)
        assertEquals(868.0, model.frequencyMHz)
        assertEquals(revision + 1, model.revision.value)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun firstDeviceSeedRecomputesRetainedCachedVerdict() = runTest {
        val model = LineOfSightViewModel(backgroundScope)
        val a = LatLng(38.0, -121.0)
        val b = LatLng(38.01, -121.01)
        model.pointA = LosSelectedPoint(a, groundElevation = 0.0)
        model.pointB = LosSelectedPoint(b, groundElevation = 0.0)
        model.elevationProfile = listOf(
            ElevationSample(GeoCoordinate(a.latitude, a.longitude), 0.0, 0.0),
            ElevationSample(GeoCoordinate(b.latitude, b.longitude), 0.0, 1000.0),
        )
        model.reanalyzeWithCachedProfileIfNeeded()
        runCurrent()
        val old = assertIs<LosAnalysisStatus.Result>(model.analysisStatus).value.freeSpacePathLoss
        model.configure({ null }, 868_000)
        runCurrent()
        val fresh = assertIs<LosAnalysisStatus.Result>(model.analysisStatus).value.freeSpacePathLoss
        assertNotEquals(old, fresh)
        assertEquals(868.0, model.frequencyMHz)
    }

    private companion object {
        val region = MapCameraRegion(LatLng(38.0, -121.0), 0.3, 0.7)
    }
}
