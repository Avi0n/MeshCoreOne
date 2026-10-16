package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.ElevationService
import one.meshcore.shared.map.FakeGeoHttpClient
import one.meshcore.shared.map.GeoHttpResponse
import one.meshcore.shared.map.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Terrain analysis failures. A failed profile still reports the analysis error and clears the cached
 * terrain. Cancellation and an error while rebuilding the map propagate and do not replace the verdict.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LineOfSightAnalysisFaultTest {
    @Test
    fun aCancelledPathFetchKeepsThePreviousVerdict() = runTest {
        val stopped = CancellationException("stopped")
        var cancelPath = false
        val vm = viewModel(analysisScope(testScheduler)) { url ->
            val latitudes = latitudesOf(url)
            if (cancelPath && latitudes.contains(',')) throw stopped
            seaLevelResponse(latitudes)
        }
        vm.placeEnds()
        advanceUntilIdle()
        vm.analyze()
        advanceUntilIdle()
        val profile = vm.elevationProfile
        assertTrue(profile.isNotEmpty())
        assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)

        cancelPath = true
        vm.analyze()
        advanceUntilIdle()

        assertSame(stopped, vm.analysisJob?.completionCause())
        assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)
        assertEquals(profile, vm.elevationProfile)
        assertTrue(vm.isAnalyzing)
    }

    @Test
    fun aMapRebuildErrorKeepsTheVerdict() = runTest {
        val vm = viewModel(analysisScope(testScheduler)) { url -> seaLevelResponse(latitudesOf(url)) }
        vm.placeEnds()
        advanceUntilIdle()
        vm.repeatersWithLocation = ThrowingRepeaterList()

        vm.analyze()
        advanceUntilIdle()

        assertIs<LosAnalysisProbeError>(vm.analysisJob?.completionCause())
        assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)
        assertTrue(vm.elevationProfile.isNotEmpty())
        assertTrue(vm.profileSamples.isNotEmpty())
    }

    @Test
    fun aCancelledOffPathFetchKeepsThePreviousVerdict() = runTest {
        val stopped = CancellationException("stopped")
        var cancelPath = false
        val vm = viewModel(analysisScope(testScheduler)) { url ->
            val latitudes = latitudesOf(url)
            if (cancelPath && latitudes.contains(',')) throw stopped
            seaLevelResponse(latitudes)
        }
        vm.placeEnds()
        advanceUntilIdle()
        vm.analyze()
        advanceUntilIdle()
        val profile = vm.elevationProfile
        assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)

        vm.setRepeaterOffPath(LatLng(37.05, -122.05))
        advanceUntilIdle()
        cancelPath = true
        vm.analyzeWithRepeater()
        advanceUntilIdle()

        assertSame(stopped, vm.analysisJob?.completionCause())
        assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)
        assertEquals(profile, vm.elevationProfile)
        assertTrue(vm.elevationProfileAr.isEmpty())
        assertTrue(vm.isAnalyzing)
    }

    @Test
    fun anOffPathMapRebuildErrorKeepsTheRelayVerdict() = runTest {
        val vm = viewModel(analysisScope(testScheduler)) { url -> seaLevelResponse(latitudesOf(url)) }
        vm.placeEnds()
        advanceUntilIdle()
        vm.setRepeaterOffPath(LatLng(37.05, -122.05))
        advanceUntilIdle()
        vm.repeatersWithLocation = ThrowingRepeaterList()

        vm.analyzeWithRepeater()
        advanceUntilIdle()

        assertIs<LosAnalysisProbeError>(vm.analysisJob?.completionCause())
        assertIs<LosAnalysisStatus.RelayResult>(vm.analysisStatus)
        assertTrue(vm.elevationProfileAr.isNotEmpty())
        assertTrue(vm.elevationProfileRb.isNotEmpty())
        assertTrue(vm.isAnalyzing)
    }

    @Test
    fun anOffPathTerrainFailureClearsTheCachedProfile() = runTest {
        var failPath = false
        val vm = viewModel(analysisScope(testScheduler)) { url ->
            val latitudes = latitudesOf(url)
            if (failPath && latitudes.contains(',')) {
                GeoHttpResponse(500, "")
            } else {
                seaLevelResponse(latitudes)
            }
        }
        vm.placeEnds()
        advanceUntilIdle()
        vm.analyze()
        advanceUntilIdle()
        assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)
        assertTrue(vm.elevationProfile.isNotEmpty())

        vm.setRepeaterOffPath(LatLng(37.05, -122.05))
        advanceUntilIdle()
        failPath = true
        vm.analyzeWithRepeater()
        advanceUntilIdle()

        val status = assertIs<LosAnalysisStatus.Error>(vm.analysisStatus)
        assertIs<LosAnalysisFailure.ApiError>(status.failure)
        assertTrue(vm.elevationProfile.isEmpty())
        assertTrue(vm.elevationProfileAr.isEmpty())
        assertTrue(vm.elevationProfileRb.isEmpty())
        assertTrue(vm.profileSamples.isEmpty())
        assertFalse(vm.isAnalyzing)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun analysisScope(scheduler: TestCoroutineScheduler): CoroutineScope = CoroutineScope(
    SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, _ -> },
)

private fun viewModel(scope: CoroutineScope, responder: suspend (String) -> GeoHttpResponse): LineOfSightViewModel =
    LineOfSightViewModel(
        scope = scope,
        elevationService = ElevationService(FakeGeoHttpClient(responder)),
    )

private fun LineOfSightViewModel.placeEnds() {
    setPointA(LatLng(37.0, -122.0))
    setPointB(LatLng(37.1, -122.1))
}

private fun latitudesOf(url: String): String = url.substringAfter("latitude=").substringBefore("&")

private fun seaLevelResponse(latitudes: String): GeoHttpResponse {
    val count = latitudes.split(',').size.coerceAtLeast(1)
    val elevations = List(count) { 0.0 }.joinToString(",")
    return GeoHttpResponse(200, """{"elevation":[$elevations]}""")
}

private class ThrowingRepeaterList : AbstractList<ContactDto>() {
    override val size: Int
        get() = throw LosAnalysisProbeError()

    override fun get(index: Int): ContactDto = throw LosAnalysisProbeError()
}

private class LosAnalysisProbeError : Error("probe")
