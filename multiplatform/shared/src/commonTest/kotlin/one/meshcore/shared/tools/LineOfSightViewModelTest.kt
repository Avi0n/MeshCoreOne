package one.meshcore.shared.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.map.ElevationService
import one.meshcore.shared.map.FakeGeoHttpClient
import one.meshcore.shared.map.GeoHttpResponse
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LineStyle
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.rf.ClearanceStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Camera framing, endpoints, and edits that must not radio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LineOfSightViewModelTest {
    @Test
    fun closeCancelsTheOwnedElevationRequest() = runTest {
        var cancelled = false
        val vm = LineOfSightViewModel(
            scope = this,
            elevationService = ElevationService(
                FakeGeoHttpClient {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                },
            ),
        )
        vm.setPointA(LatLng(37.33, -122.03))
        runCurrent()

        vm.close()
        runCurrent()

        assertTrue(cancelled)
        assertNull(vm.pointA?.groundElevation)
    }

    @Test
    fun frameOnUserSetsATightCameraRegion() = runTest(UnconfinedTestDispatcher()) {
        val vm = LineOfSightViewModel(scope = this, elevationService = seaLevelService())
        val coordinate = LatLng(37.33, -122.03)

        vm.frameOnUser(coordinate)

        val target = assertNotNull(vm.cameraTarget)
        assertEquals(coordinate, target.center)
        assertEquals(0.01, target.spanLatDeg)
        assertEquals(0.01, target.spanLonDeg)
    }

    @Test
    fun selectPointFillsAThenBAndBuildsLosLine() = runTest(UnconfinedTestDispatcher()) {
        val vm = LineOfSightViewModel(scope = this, elevationService = seaLevelService())
        vm.selectPoint(LatLng(37.0, -122.0))
        advanceUntilIdle()
        assertNotNull(vm.pointA)
        assertNull(vm.pointB)
        assertTrue(vm.mapPoints.any { it.pinStyle == PinStyle.POINT_A })

        vm.selectPoint(LatLng(37.05, -122.05))
        advanceUntilIdle()
        assertNotNull(vm.pointB)
        assertEquals(1, vm.mapLines.size)
        assertEquals(LineStyle.LOS, vm.mapLines[0].style)
        assertTrue(vm.canAnalyze)
    }

    @Test
    fun clearingAnEndpointDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        var lookups = 0
        var contactReads = 0
        val vm = LineOfSightViewModel(
            scope = this,
            elevationService = ElevationService(
                FakeGeoHttpClient { url ->
                    lookups += 1
                    val latParam = url.substringAfter("latitude=").substringBefore("&")
                    val count = latParam.split(',').size.coerceAtLeast(1)
                    GeoHttpResponse(200, """{"elevation":[${List(count) { 0.0 }.joinToString(",")}]}""")
                },
            ),
            fetchContacts = {
                contactReads += 1
                emptyList()
            },
        )
        vm.setPointA(LatLng(37.0, -122.0))
        advanceUntilIdle()
        assertNotNull(vm.pointA?.groundElevation)
        val afterFetch = lookups

        vm.clearPointA()
        advanceUntilIdle()

        assertEquals(afterFetch, lookups)
        assertEquals(0, contactReads)
        assertNull(vm.pointA)
        assertNull(vm.repeaterPoint)
        assertTrue(vm.mapPoints.none { it.pinStyle == PinStyle.POINT_A })
    }

    @Test
    fun hidingLineOfSightLabelsDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        var lookups = 0
        var contactReads = 0
        val vm = LineOfSightViewModel(
            scope = this,
            elevationService = ElevationService(
                FakeGeoHttpClient { url ->
                    lookups += 1
                    val latParam = url.substringAfter("latitude=").substringBefore("&")
                    val count = latParam.split(',').size.coerceAtLeast(1)
                    GeoHttpResponse(200, """{"elevation":[${List(count) { 0.0 }.joinToString(",")}]}""")
                },
            ),
            fetchContacts = {
                contactReads += 1
                emptyList()
            },
        )
        assertTrue(vm.showLabels)

        vm.showLabels = false
        advanceUntilIdle()

        assertFalse(vm.showLabels)
        assertEquals(0, lookups)
        assertEquals(0, contactReads)
        assertTrue(vm.mapPoints.isEmpty())
        assertNull(vm.cameraTarget)
    }

    @Test
    fun clearingALineOfSightAnalysisDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        var lookups = 0
        var contactReads = 0
        val vm = LineOfSightViewModel(
            scope = this,
            elevationService = ElevationService(
                FakeGeoHttpClient { url ->
                    lookups += 1
                    val latParam = url.substringAfter("latitude=").substringBefore("&")
                    val count = latParam.split(',').size.coerceAtLeast(1)
                    GeoHttpResponse(200, """{"elevation":[${List(count) { 0.0 }.joinToString(",")}]}""")
                },
            ),
            fetchContacts = {
                contactReads += 1
                emptyList()
            },
        )
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)

        vm.clearAnalysisResults()
        advanceUntilIdle()

        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertEquals(0, lookups)
        assertEquals(0, contactReads)
        assertTrue(vm.mapPoints.isEmpty())
        assertNull(vm.cameraTarget)
        assertNull(vm.pointA)
        assertNull(vm.pointB)
    }

    @Test
    fun additionalHeightClampsToIosStepperRange() = runTest(UnconfinedTestDispatcher()) {
        val vm = LineOfSightViewModel(scope = this, elevationService = seaLevelService())
        vm.setPointA(LatLng(37.0, -122.0))
        advanceUntilIdle()

        vm.updateAdditionalHeight(LosPointId.POINT_A, 500.0)
        assertEquals(ADDITIONAL_HEIGHT_MAX_M, vm.pointA?.additionalHeight)
        vm.updateAdditionalHeight(LosPointId.POINT_A, -8.0)
        assertEquals(ADDITIONAL_HEIGHT_MIN_M, vm.pointA?.additionalHeight)
        assertEquals(200.0, clampAdditionalHeightMeters(999.0))
        assertEquals(0.0, clampAdditionalHeightMeters(-1.0))
        assertEquals(7.0, clampAdditionalHeightMeters(7.0))
    }

    @Test
    fun changingLineOfSightHeightWithNoEndpointDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)

        vm.updateAdditionalHeight(LosPointId.POINT_A, 12.0)
        advanceUntilIdle()

        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertTrue(vm.mapPoints.isEmpty())
        assertNull(vm.cameraTarget)
    }

    @Test
    fun keepingLineOfSightLabelsOnDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        assertTrue(vm.showLabels)
        assertEquals(0, vm.revision.value)
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.repeaterPoint)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)

        vm.showLabels = true
        advanceUntilIdle()

        assertTrue(vm.showLabels)
        assertEquals(1, vm.revision.value)
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.repeaterPoint)
        assertNull(vm.cameraTarget)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertTrue(vm.mapPoints.isEmpty())
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
    }

    @Test
    fun clearingAnAlreadyEmptyLineOfSightDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.repeaterPoint)
        assertEquals(DEFAULT_FREQUENCY_MHZ, vm.frequencyMHz)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertEquals(0, vm.revision.value)
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)

        vm.clear()
        advanceUntilIdle()

        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.repeaterPoint)
        assertNull(vm.cameraTarget)
        assertEquals(DEFAULT_FREQUENCY_MHZ, vm.frequencyMHz)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertFalse(vm.elevationFetchFailed)
        assertTrue(vm.elevationProfile.isEmpty())
        assertTrue(vm.mapPoints.isEmpty())
        assertTrue(vm.mapLines.isEmpty())
        assertEquals(1, vm.revision.value)
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
    }

    @Test
    fun keepingTheLineOfSightFrequencyDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertEquals(DEFAULT_FREQUENCY_MHZ, vm.frequencyMHz)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)

        vm.setFrequencyMHz(DEFAULT_FREQUENCY_MHZ)
        advanceUntilIdle()

        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
        assertEquals(DEFAULT_FREQUENCY_MHZ, vm.frequencyMHz)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertTrue(vm.mapPoints.isEmpty())
        assertNull(vm.cameraTarget)
    }

    @Test
    fun changingLineOfSightFrequencyWithNoEndpointDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertEquals(DEFAULT_FREQUENCY_MHZ, vm.frequencyMHz)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)

        vm.setFrequencyMHz(915.0)
        advanceUntilIdle()

        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
        assertEquals(915.0, vm.frequencyMHz)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertTrue(vm.mapPoints.isEmpty())
    }

    @Test
    fun changingLineOfSightRefractionWithNoEndpointDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertEquals(DEFAULT_REFRACTION_K, vm.refractionK)
        assertEquals(DEFAULT_FREQUENCY_MHZ, vm.frequencyMHz)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)

        vm.setRefractionK(1.33)
        advanceUntilIdle()

        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
        assertEquals(1.33, vm.refractionK)
        assertEquals(DEFAULT_FREQUENCY_MHZ, vm.frequencyMHz)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertTrue(vm.mapPoints.isEmpty())
    }

    @Test
    fun keepingAnUnchangedRefractionDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.repeaterPoint)
        assertEquals(DEFAULT_REFRACTION_K, vm.refractionK)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertEquals(0, vm.revision.value)
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)

        vm.setRefractionK(DEFAULT_REFRACTION_K)
        advanceUntilIdle()

        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.repeaterPoint)
        assertNull(vm.cameraTarget)
        assertEquals(DEFAULT_REFRACTION_K, vm.refractionK)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertEquals(0, vm.revision.value)
        assertTrue(vm.mapPoints.isEmpty())
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
    }
}

/**
 * Analysis, repeater placement, and terrain-fetch failures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LineOfSightAnalysisTest {
    @Test
    fun changingRepeaterHeightWithNoRepeaterDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.repeaterPoint)
        assertEquals(DEFAULT_REFRACTION_K, vm.refractionK)
        assertEquals(DEFAULT_FREQUENCY_MHZ, vm.frequencyMHz)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)

        vm.updateRepeaterHeight(12.0)
        advanceUntilIdle()

        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
        assertNull(vm.repeaterPoint)
        assertEquals(DEFAULT_REFRACTION_K, vm.refractionK)
        assertEquals(DEFAULT_FREQUENCY_MHZ, vm.frequencyMHz)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertTrue(vm.mapPoints.isEmpty())
    }

    @Test
    fun startingALineOfSightRelocateDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        assertNull(vm.relocatingPoint)
        assertNull(vm.pointA)

        vm.relocatingPoint = LosPointId.POINT_A
        advanceUntilIdle()

        assertEquals(LosPointId.POINT_A, vm.relocatingPoint)
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertTrue(vm.mapPoints.isEmpty())
    }

    @Test
    fun consumingALineOfSightCameraTargetDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        val coordinate = LatLng(37.33, -122.03)
        vm.frameOnUser(coordinate)
        assertNotNull(vm.cameraTarget)
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)

        vm.consumeCameraTarget()
        advanceUntilIdle()

        assertNull(vm.cameraTarget)
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertNull(vm.pointA)
        assertNull(vm.pointB)
    }

    @Test
    fun centeringLineOfSightOnRepeatersDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        vm.frameOnUser(LatLng(37.33, -122.03))
        assertNotNull(vm.cameraTarget)
        assertTrue(vm.repeatersWithLocation.isEmpty())

        vm.centerOnAllRepeaters()
        advanceUntilIdle()

        assertEquals(LatLng(37.33, -122.03), vm.cameraTarget?.center)
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertNull(vm.pointA)
        assertNull(vm.pointB)
    }

    @Test
    fun analyzeProducesResultAndObstructionPinsWhenBlocked() = runTest(UnconfinedTestDispatcher()) {
        // Flat sea-level path with low antennas stays Clear; force a hill via custom elevations.
        val service = ElevationService(
            FakeGeoHttpClient { url ->
                val latParam = url.substringAfter("latitude=").substringBefore("&")
                val count = latParam.split(',').size.coerceAtLeast(2)
                // High terrain mid-path so Fresnel is obstructed.
                val elevations = List(count) { i ->
                    if (i == 0 || i == count - 1) 0.0 else 200.0
                }
                GeoHttpResponse(200, """{"elevation":[${elevations.joinToString(",")}]}""")
            },
        )
        val vm = LineOfSightViewModel(scope = this, elevationService = service)
        vm.setPointA(LatLng(37.0, -122.0))
        vm.setPointB(LatLng(37.1, -122.1))
        advanceUntilIdle()
        vm.analyze()
        advanceUntilIdle()
        val status = assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)
        assertTrue(status.value.distanceMeters > 0.0)
        assertTrue(vm.profileSamples.size >= 2)
        // With a 200 m ridge, path should not be Clear.
        assertTrue(status.value.clearanceStatus != ClearanceStatus.Clear)
    }

    @Test
    fun addRepeaterSplitsMapIntoTwoLosSegments() = runTest(UnconfinedTestDispatcher()) {
        val service = ElevationService(
            FakeGeoHttpClient { url ->
                val latParam = url.substringAfter("latitude=").substringBefore("&")
                val count = latParam.split(',').size.coerceAtLeast(2)
                val elevations = List(count) { i -> if (i == 0 || i == count - 1) 0.0 else 200.0 }
                GeoHttpResponse(200, """{"elevation":[${elevations.joinToString(",")}]}""")
            },
        )
        val vm = LineOfSightViewModel(scope = this, elevationService = service)
        vm.setPointA(LatLng(37.0, -122.0))
        vm.setPointB(LatLng(37.1, -122.1))
        advanceUntilIdle()
        vm.analyze()
        advanceUntilIdle()
        assertTrue(vm.shouldShowRepeaterPlaceholder)
        vm.addRepeater()
        assertNotNull(vm.repeaterPoint)
        assertEquals(2, vm.mapLines.size)
        assertTrue(vm.mapPoints.any { it.pinStyle == PinStyle.CROSSHAIR })

        vm.analyzeWithRepeater()
        advanceUntilIdle()
        assertIs<LosAnalysisStatus.RelayResult>(vm.analysisStatus)
    }

    @Test
    fun everyRepeaterMutationReplacesTheDirectPathVerdict() = runTest(UnconfinedTestDispatcher()) {
        val vm = obstructedViewModel(this)
        vm.setPointA(LatLng(37.0, -122.0))
        vm.setPointB(LatLng(37.1, -122.1))
        advanceUntilIdle()
        vm.analyze()
        advanceUntilIdle()
        assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)

        // Adding a repeater must not leave the direct-path verdict on screen beside a visible relay.
        vm.addRepeater()
        advanceUntilIdle()
        assertIs<LosAnalysisStatus.RelayResult>(vm.analysisStatus)

        vm.updateRepeaterPosition(0.1)
        advanceUntilIdle()
        val nearA = assertIs<LosAnalysisStatus.RelayResult>(vm.analysisStatus).value
        vm.updateRepeaterPosition(0.9)
        advanceUntilIdle()
        val afterMove = assertIs<LosAnalysisStatus.RelayResult>(vm.analysisStatus).value
        assertTrue(
            afterMove.segmentAR.distanceMeters > nearA.segmentAR.distanceMeters,
            "dragging the repeater toward B must lengthen the analyzed A→R leg",
        )

        vm.updateRepeaterHeight(150.0)
        advanceUntilIdle()
        val afterHeight = assertIs<LosAnalysisStatus.RelayResult>(vm.analysisStatus).value
        assertNotEquals(afterMove, afterHeight, "raising the mast must re-run the clearance analysis")
    }

    @Test
    fun clearResetsPointsAndGeometry() = runTest(UnconfinedTestDispatcher()) {
        val vm = LineOfSightViewModel(scope = this, elevationService = seaLevelService())
        vm.selectPoint(LatLng(1.0, 2.0))
        vm.selectPoint(LatLng(3.0, 4.0))
        advanceUntilIdle()
        vm.clear()
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertTrue(vm.mapLines.isEmpty())
        assertIs<LosAnalysisStatus.Idle>(vm.analysisStatus)
    }

    @Test
    fun terrainFetchFailureLeavesAnErrorAndNoVerdict() = runTest(UnconfinedTestDispatcher()) {
        // Endpoint elevations resolve; only the path profile fails, so the failure under test is the
        // terrain fetch alone.
        val vm = LineOfSightViewModel(scope = this, elevationService = pathFetchFailsService())
        vm.setPointA(LatLng(37.0, -122.0))
        vm.setPointB(LatLng(37.1, -122.1))
        advanceUntilIdle()
        assertFalse(vm.elevationFetchFailed, "endpoint elevations resolved, so the sea-level note is off")

        vm.analyze()
        advanceUntilIdle()

        val status = vm.analysisStatus
        assertIs<LosAnalysisStatus.Error>(status)
        assertIs<LosAnalysisFailure.ApiError>(status.failure)
        // The verdict itself must be absent, not merely flagged.
        assertNull(
            vm.analysisStatus as? LosAnalysisStatus.Result,
            "a failed terrain fetch must publish no clearance verdict",
        )
        assertNull(vm.analysisStatus as? LosAnalysisStatus.RelayResult)
        assertTrue(vm.elevationProfile.isEmpty(), "no fabricated terrain may be kept")
        assertTrue(vm.profileSamples.isEmpty(), "no Fresnel chart may be drawn from terrain never fetched")
        assertFalse(vm.shouldShowRepeaterPlaceholder)
        assertTrue(vm.mapPoints.none { it.pinStyle == PinStyle.OBSTRUCTION })
        assertFalse(vm.isAnalyzing)
    }

    @Test
    fun offlineAnalysisReportsTheNetworkFailureAndNoVerdict() = runTest(UnconfinedTestDispatcher()) {
        val vm = LineOfSightViewModel(
            scope = this,
            elevationService = ElevationService(FakeGeoHttpClient { error("offline") }),
        )
        vm.setPointA(LatLng(37.0, -122.0))
        vm.setPointB(LatLng(37.1, -122.1))
        advanceUntilIdle()
        // Endpoints fall back to sea level, which is the milder approximation and keeps Analyze usable.
        assertTrue(vm.elevationFetchFailed)
        assertTrue(vm.canAnalyze)

        vm.analyze()
        advanceUntilIdle()

        val status = assertIs<LosAnalysisStatus.Error>(vm.analysisStatus)
        assertIs<LosAnalysisFailure.NetworkError>(status.failure)
        assertTrue(vm.elevationProfile.isEmpty())
    }

    @Test
    fun aFailedFetchLeavesNoProfileForALaterAnalysis() = runTest(UnconfinedTestDispatcher()) {
        var offline = false
        val service = ElevationService(
            FakeGeoHttpClient { url ->
                if (offline) error("offline")
                val count = url.substringAfter("latitude=").substringBefore("&").split(',').size
                GeoHttpResponse(200, """{"elevation":[${List(count) { 200.0 }.joinToString(",")}]}""")
            },
        )
        val vm = LineOfSightViewModel(scope = this, elevationService = service)
        vm.setPointA(LatLng(37.0, -122.0))
        vm.setPointB(LatLng(37.1, -122.1))
        advanceUntilIdle()
        vm.analyze()
        advanceUntilIdle()
        assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)
        assertTrue(vm.elevationProfile.isNotEmpty())

        offline = true
        vm.analyze()
        advanceUntilIdle()
        assertIs<LosAnalysisStatus.Error>(vm.analysisStatus)
        assertTrue(vm.elevationProfile.isEmpty(), "the failed run must not leave the old profile cached")

        // An RF-setting change re-analyzes from the cached profile; with none, it must not mint a verdict.
        vm.setFrequencyMHz(915.0)
        advanceUntilIdle()
        assertIs<LosAnalysisStatus.Error>(vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
    }
}

/**
 * Reanalysis, zoom, and endpoint elevation fallbacks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LineOfSightEndpointTest {
    @Test
    fun frequencyChangeReanalyzesWithoutAnotherTerrainFetch() = runTest(UnconfinedTestDispatcher()) {
        var fetches = 0
        val service = ElevationService(
            FakeGeoHttpClient { url ->
                fetches += 1
                val latParam = url.substringAfter("latitude=").substringBefore("&")
                val count = latParam.split(',').size.coerceAtLeast(1)
                GeoHttpResponse(200, """{"elevation":[${List(count) { 0.0 }.joinToString(",")}]}""")
            },
        )
        val vm = LineOfSightViewModel(scope = this, elevationService = service)
        vm.setPointA(LatLng(37.0, -122.0))
        vm.setPointB(LatLng(37.1, -122.1))
        advanceUntilIdle()
        vm.analyze()
        advanceUntilIdle()
        val before = assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)
        assertEquals(DEFAULT_FREQUENCY_MHZ, before.value.frequencyMHz)
        val fetchesAfterAnalyze = fetches
        assertTrue(fetchesAfterAnalyze > 0)

        vm.setFrequencyMHz(915.0)
        advanceUntilIdle()

        assertEquals(fetchesAfterAnalyze, fetches)
        val after = assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)
        assertEquals(915.0, after.value.frequencyMHz)
        assertEquals(915.0, vm.frequencyMHz)
        assertFalse(vm.isAnalyzing)
    }

    @Test
    fun refractionChangeReanalyzesWithoutAnotherTerrainFetch() = runTest(UnconfinedTestDispatcher()) {
        var fetches = 0
        val service = ElevationService(
            FakeGeoHttpClient { url ->
                fetches += 1
                val latParam = url.substringAfter("latitude=").substringBefore("&")
                val count = latParam.split(',').size.coerceAtLeast(1)
                GeoHttpResponse(200, """{"elevation":[${List(count) { 0.0 }.joinToString(",")}]}""")
            },
        )
        val vm = LineOfSightViewModel(scope = this, elevationService = service)
        vm.setPointA(LatLng(37.0, -122.0))
        vm.setPointB(LatLng(37.1, -122.1))
        advanceUntilIdle()
        vm.analyze()
        advanceUntilIdle()
        val before = assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)
        assertEquals(DEFAULT_REFRACTION_K, before.value.refractionK)
        val fetchesAfterAnalyze = fetches
        assertTrue(fetchesAfterAnalyze > 0)

        vm.setRefractionK(1.33)
        advanceUntilIdle()

        assertEquals(fetchesAfterAnalyze, fetches)
        val after = assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)
        assertEquals(1.33, after.value.refractionK)
        assertEquals(1.33, vm.refractionK)
        assertFalse(vm.isAnalyzing)
    }

    @Test
    fun nonPositiveFrequencyLeavesTheVerdict() = runTest(UnconfinedTestDispatcher()) {
        var fetches = 0
        val service = ElevationService(
            FakeGeoHttpClient { url ->
                fetches += 1
                val latParam = url.substringAfter("latitude=").substringBefore("&")
                val count = latParam.split(',').size.coerceAtLeast(1)
                GeoHttpResponse(200, """{"elevation":[${List(count) { 0.0 }.joinToString(",")}]}""")
            },
        )
        val vm = LineOfSightViewModel(scope = this, elevationService = service)
        vm.setPointA(LatLng(37.0, -122.0))
        vm.setPointB(LatLng(37.1, -122.1))
        advanceUntilIdle()
        vm.analyze()
        advanceUntilIdle()
        val before = assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)
        assertEquals(DEFAULT_FREQUENCY_MHZ, before.value.frequencyMHz)
        val fetchesAfterAnalyze = fetches
        assertTrue(fetchesAfterAnalyze > 0)

        vm.setFrequencyMHz(0.0)
        advanceUntilIdle()
        vm.setFrequencyMHz(-1.0)
        advanceUntilIdle()

        assertEquals(fetchesAfterAnalyze, fetches)
        assertEquals(DEFAULT_FREQUENCY_MHZ, vm.frequencyMHz)
        val after = assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)
        assertEquals(DEFAULT_FREQUENCY_MHZ, after.value.frequencyMHz)
        assertFalse(vm.isAnalyzing)
    }

    @Test
    fun zoomToBothPointsDoesNotFetchTerrain() = runTest(UnconfinedTestDispatcher()) {
        var fetches = 0
        val service = ElevationService(
            FakeGeoHttpClient { url ->
                fetches += 1
                val latParam = url.substringAfter("latitude=").substringBefore("&")
                val count = latParam.split(',').size.coerceAtLeast(1)
                GeoHttpResponse(200, """{"elevation":[${List(count) { 0.0 }.joinToString(",")}]}""")
            },
        )
        val vm = LineOfSightViewModel(scope = this, elevationService = service)
        vm.setPointA(LatLng(37.0, -122.0))
        vm.setPointB(LatLng(37.1, -122.1))
        advanceUntilIdle()
        val fetchesAfterPoints = fetches
        assertTrue(fetchesAfterPoints > 0)
        assertNull(vm.cameraTarget)

        vm.zoomToShowBothPoints()
        advanceUntilIdle()

        assertEquals(fetchesAfterPoints, fetches)
        assertNotNull(vm.cameraTarget)
        assertFalse(vm.isAnalyzing)
    }

    @Test
    fun zoomingLineOfSightWithNoEndpointsDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.cameraTarget)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)

        vm.zoomToShowBothPoints()
        advanceUntilIdle()

        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.cameraTarget)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
    }

    @Test
    fun clearingAnUnsetFarEndpointDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.repeaterPoint)
        assertNull(vm.cameraTarget)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertEquals(0, vm.revision.value)
        assertTrue(vm.mapPoints.isEmpty())
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)

        vm.clearPointB()
        advanceUntilIdle()

        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.repeaterPoint)
        assertNull(vm.cameraTarget)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertEquals(1, vm.revision.value)
        assertTrue(vm.mapPoints.isEmpty())
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
    }

    @Test
    fun clearingAnUnsetRepeaterDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.repeaterPoint)
        assertNull(vm.cameraTarget)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertEquals(0, vm.revision.value)
        assertTrue(vm.mapPoints.isEmpty())
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)

        vm.clearRepeater()
        advanceUntilIdle()

        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.repeaterPoint)
        assertNull(vm.cameraTarget)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertEquals(1, vm.revision.value)
        assertTrue(vm.mapPoints.isEmpty())
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
    }

    @Test
    fun loadingRepeatersWithNoRadioDoesNotFetch() = runTest(UnconfinedTestDispatcher()) {
        val los = quietLineOfSight()
        val vm = los.vm
        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.repeaterPoint)
        assertNull(vm.cameraTarget)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertEquals(0, vm.revision.value)
        assertTrue(vm.repeatersWithLocation.isEmpty())
        assertTrue(vm.mapPoints.isEmpty())
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)

        vm.loadRepeaters()
        advanceUntilIdle()

        assertNull(vm.pointA)
        assertNull(vm.pointB)
        assertNull(vm.repeaterPoint)
        assertNull(vm.cameraTarget)
        assertEquals(LosAnalysisStatus.Idle, vm.analysisStatus)
        assertFalse(vm.isAnalyzing)
        assertEquals(0, vm.revision.value)
        assertTrue(vm.repeatersWithLocation.isEmpty())
        assertTrue(vm.mapPoints.isEmpty())
        assertEquals(0, los.lookups)
        assertEquals(0, los.contactReads)
    }

    @Test
    fun aFailedEndpointFetchStillProducesAVerdictWithTheSeaLevelNote() = runTest(UnconfinedTestDispatcher()) {
        // The mild fallback: single-coordinate lookups fail, the path profile succeeds.
        val service = ElevationService(
            FakeGeoHttpClient { url ->
                val latParam = url.substringAfter("latitude=").substringBefore("&")
                val count = latParam.split(',').size
                if (count == 1) error("point lookup down")
                val elevations = List(count) { i -> if (i == 0 || i == count - 1) 0.0 else 200.0 }
                GeoHttpResponse(200, """{"elevation":[${elevations.joinToString(",")}]}""")
            },
        )
        val vm = LineOfSightViewModel(scope = this, elevationService = service)
        vm.setPointA(LatLng(37.0, -122.0))
        vm.setPointB(LatLng(37.1, -122.1))
        advanceUntilIdle()
        assertTrue(vm.elevationFetchFailed, "the endpoint fallback raises its own note")

        vm.analyze()
        advanceUntilIdle()

        // Distinguishable from a terrain-fetch failure: a verdict over real terrain, plus the note.
        val status = assertIs<LosAnalysisStatus.Result>(vm.analysisStatus)
        assertTrue(status.value.clearanceStatus != ClearanceStatus.Clear)
        assertTrue(vm.elevationFetchFailed)
    }

    @Test
    fun anOffPathRepeaterKeepsLoadingWhenItsElevationFetchFails() = runTest(UnconfinedTestDispatcher()) {
        val vm = LineOfSightViewModel(
            scope = this,
            elevationService = ElevationService(FakeGeoHttpClient { error("offline") }),
        )
        vm.setRepeaterOffPath(LatLng(37.05, -122.05))
        advanceUntilIdle()
        assertNull(vm.repeaterPoint?.groundElevation, "a failed repeater lookup must not claim sea level")
    }

    @Test
    fun aDroppedPinUsesTheProvidedLabel() {
        val point = LosSelectedPoint(coordinate = LatLng(37.0, -122.0))
        assertEquals("Gesetzte Stecknadel", point.displayName("Gesetzte Stecknadel"))
    }
}

/** A line-of-sight sheet with sea-level elevation and no contacts, so an edit can prove it did not fetch. */
private fun TestScope.quietLineOfSight(): QuietLineOfSight {
    var lookups = 0
    var contactReads = 0
    val vm = LineOfSightViewModel(
        scope = this,
        elevationService = ElevationService(
            FakeGeoHttpClient {
                lookups += 1
                GeoHttpResponse(200, """{"elevation":[0.0]}""")
            },
        ),
        fetchContacts = {
            contactReads += 1
            emptyList()
        },
    )
    return QuietLineOfSight(vm, { lookups }, { contactReads })
}

private class QuietLineOfSight(
    val vm: LineOfSightViewModel,
    private val lookupCount: () -> Int,
    private val contactReadCount: () -> Int,
) {
    val lookups: Int get() = lookupCount()
    val contactReads: Int get() = contactReadCount()
}

/** Endpoint (single-coordinate) lookups succeed; the multi-coordinate path profile returns HTTP 500. */
private fun pathFetchFailsService(): ElevationService = ElevationService(
    FakeGeoHttpClient { url ->
        val latParam = url.substringAfter("latitude=").substringBefore("&")
        if (latParam.contains(',')) {
            GeoHttpResponse(500, "")
        } else {
            GeoHttpResponse(200, """{"elevation":[12.0]}""")
        }
    },
)

/** A 200 m mid-path ridge, so the direct path is never Clear and a repeater can be placed. */
private fun obstructedViewModel(scope: CoroutineScope): LineOfSightViewModel = LineOfSightViewModel(
    scope = scope,
    elevationService = ElevationService(
        FakeGeoHttpClient { url ->
            val latParam = url.substringAfter("latitude=").substringBefore("&")
            val count = latParam.split(',').size.coerceAtLeast(2)
            val elevations = List(count) { i -> if (i == 0 || i == count - 1) 0.0 else 200.0 }
            GeoHttpResponse(200, """{"elevation":[${elevations.joinToString(",")}]}""")
        },
    ),
)

private fun seaLevelService(): ElevationService = ElevationService(
    FakeGeoHttpClient { url ->
        val latParam = url.substringAfter("latitude=").substringBefore("&")
        val count = latParam.split(',').size.coerceAtLeast(1)
        GeoHttpResponse(200, """{"elevation":[${List(count) { 0.0 }.joinToString(",")}]}""")
    },
)
