// AndroidOnly: WP-315 native tests for operator-workflow routing and derived state lifted from the LOS views (no source test file exists).
package com.meshcoreone.android.feature.tools.los

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class LineOfSightWorkflowTest {
    private suspend fun LosScenario.readyHolder(): LineOfSightStateHolder {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        viewModel.setPointB(OAKLAND)
        waitUntil("both point elevations should load") { viewModel.state.value.canAnalyze }
        return viewModel
    }

    @Test
    fun `long press selects points and relocation consumes the next tap`() = losScenario {
        val viewModel = holder()
        viewModel.handleMapTap(SAN_FRANCISCO)
        assertNull(viewModel.state.value.pointA, "a plain tap never selects")
        viewModel.handleMapLongPress(SAN_FRANCISCO)
        viewModel.handleMapLongPress(OAKLAND)
        assertEquals(OAKLAND, viewModel.state.value.pointB?.coordinate)

        assertTrue(viewModel.toggleRelocation(PointID.POINT_A))
        assertFalse(viewModel.state.value.isRelocateEnabled(PointID.POINT_B))
        assertTrue(viewModel.state.value.isRelocateEnabled(PointID.POINT_A))
        viewModel.handleMapTap(BERKELEY)
        assertEquals(BERKELEY, viewModel.state.value.pointA?.coordinate)
        assertNull(viewModel.state.value.pointA?.contact)
        assertNull(viewModel.state.value.relocatingPoint)

        assertTrue(viewModel.toggleRelocation(PointID.POINT_B))
        assertFalse(viewModel.toggleRelocation(PointID.POINT_B), "second press cancels")
        assertNull(viewModel.state.value.relocatingPoint)
    }

    @Test
    fun `relocating the repeater moves it off-path and clears results`() = losScenario {
        val viewModel = readyHolder()
        viewModel.setElevationProfileForTesting(flatProfile101())
        viewModel.setRepeaterPoint(RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = 0.5, additionalHeight = 22.0))
        viewModel.analyzeWithRepeater()
        assertIs<AnalysisStatus.RelayResult>(viewModel.state.value.analysisStatus)
        viewModel.setRelocatingPoint(PointID.REPEATER)

        val target = Coordinate(37.79, -122.35)
        viewModel.handleMapLongPress(target)

        val repeater = assertNotNull(viewModel.state.value.repeaterPoint)
        assertFalse(repeater.isOnPath)
        assertEquals(target, repeater.coordinate)
        assertEquals(22.0, repeater.additionalHeight, "height survives relocation")
        assertEquals(AnalysisStatus.Idle, viewModel.state.value.analysisStatus)
        assertNull(viewModel.state.value.relocatingPoint)
        assertFalse(viewModel.state.value.isRepeaterDragEnabled)
    }

    @Test
    fun `requestAnalysis routes by repeater presence and auto-zoom is consumed once`() = losScenario {
        val viewModel = readyHolder()
        viewModel.requestAnalysis()
        assertTrue(viewModel.state.value.shouldAutoZoomOnNextResult)
        waitUntil("direct result") { viewModel.state.value.analysisStatus is AnalysisStatus.Result }
        assertEquals(listOf(SAN_FRANCISCO, OAKLAND), viewModel.onAnalysisStatusChanged())
        assertNull(viewModel.onAnalysisStatusChanged(), "auto-zoom request is consumed")

        viewModel.setElevationProfileForTesting(flatProfile101())
        viewModel.setRepeaterPoint(RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = 0.4))
        viewModel.requestAnalysis()
        assertIs<AnalysisStatus.RelayResult>(viewModel.state.value.analysisStatus)
        assertNotNull(viewModel.onAnalysisStatusChanged())
    }

    @Test
    fun `error status keeps the auto-zoom request and retry re-runs analysis`() = losScenario {
        val viewModel = readyHolder()
        elevation.shouldFail = true
        viewModel.requestAnalysis()
        waitUntil("error") { viewModel.state.value.analysisStatus is AnalysisStatus.Error }
        assertNull(viewModel.onAnalysisStatusChanged())
        assertTrue(viewModel.state.value.shouldAutoZoomOnNextResult)

        elevation.shouldFail = false
        viewModel.retryAnalysis()
        waitUntil("retry result") { viewModel.state.value.analysisStatus is AnalysisStatus.Result }
    }

    @Test
    fun `placeholder adds a repeater at the worst obstruction and analyzes the relay`() = losScenario {
        val viewModel = readyHolder()
        viewModel.setElevationProfileForTesting(flatProfile101())
        viewModel.setAnalysisStatusForTesting(
            PathAnalysisResult(10000.0, 110.0, 15.0, 125.0, ClearanceStatus.MARGINAL, 65.0,
                listOf(ObstructionPoint(6000.0, 1.0, 65.0)), 906.0, 1.0),
        )
        assertTrue(viewModel.state.value.shouldShowRepeaterPlaceholder)
        assertTrue(viewModel.state.value.shouldShowRepeaterRow)

        viewModel.addRepeaterAndAnalyze()

        assertEquals(0.6, viewModel.state.value.repeaterPoint?.pathFraction)
        assertIs<AnalysisStatus.RelayResult>(viewModel.state.value.analysisStatus)
        assertFalse(viewModel.state.value.shouldShowRepeaterPlaceholder)
        assertTrue(viewModel.state.value.shouldShowRepeaterRow)
        assertEquals(0.6, viewModel.state.value.terrainChartInput.repeaterPathFraction)
    }

    @Test
    fun `clear results without obstructions show no placeholder`() = losScenario {
        val viewModel = holder()
        viewModel.setAnalysisStatusForTesting(
            PathAnalysisResult(10000.0, 110.0, 0.0, 110.0, ClearanceStatus.BLOCKED, -5.0, emptyList(), 906.0, 1.0),
        )
        assertFalse(viewModel.state.value.shouldShowRepeaterPlaceholder)
        viewModel.addRepeater()
        assertNull(viewModel.state.value.repeaterPoint)
    }

    @Test
    fun `dragging moves only on-path repeaters and re-analyzes`() = losScenario {
        val viewModel = readyHolder()
        viewModel.setElevationProfileForTesting(flatProfile101())
        viewModel.setRepeaterPoint(RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = 0.5))
        viewModel.dragRepeater(0.2)
        val repeater = assertNotNull(viewModel.state.value.repeaterPoint)
        assertEquals(0.2, repeater.pathFraction)
        assertEquals(flatProfile101()[20].coordinate, repeater.coordinate)
        assertEquals(2000.0, assertIs<AnalysisStatus.RelayResult>(viewModel.state.value.analysisStatus).result.segmentAR.distanceMeters)

        viewModel.setRepeaterPoint(repeater.copy(isOnPath = false))
        viewModel.dragRepeater(0.7)
        assertEquals(0.2, viewModel.state.value.repeaterPoint?.pathFraction)
    }

    @Test
    fun `repeater height edits re-run the relay with the new height`() = losScenario {
        val viewModel = readyHolder()
        viewModel.setElevationProfileForTesting(flatProfile101())
        viewModel.setRepeaterPoint(RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = 0.5))
        viewModel.editRepeaterHeight(30.0)
        assertEquals(30.0, analyzer.calls.last().startHeight)
        viewModel.updateAdditionalHeight(PointID.REPEATER, -3.0)
        assertEquals(0.0, viewModel.state.value.repeaterPoint?.additionalHeight)
        assertIs<AnalysisStatus.RelayResult>(viewModel.state.value.analysisStatus, "repeater height does not invalidate")
    }

    @Test
    fun `frequency commit stores valid text and restores invalid text`() = losScenario {
        val viewModel = readyHolder()
        viewModel.analyze()
        waitUntil("result") { viewModel.state.value.analysisStatus is AnalysisStatus.Result }
        assertNull(viewModel.commitFrequencyText("868,5"))
        assertEquals(868.5, viewModel.state.value.frequencyMHz)
        waitUntil("re-analysis") { !viewModel.state.value.isAnalyzing }
        assertEquals(868.5, analyzer.calls.last().frequencyMHz)
        assertEquals("868.5", viewModel.commitFrequencyText("-1"))
        assertEquals(868.5, viewModel.state.value.frequencyMHz)
    }

    @Test
    fun `configure seeds frequency from device kHz once`() = losScenario {
        val viewModel = holder()
        viewModel.configure(contactSource = { null }, radioId = { null }, deviceFrequencyKHz = 869_525u)
        assertEquals(869.525, viewModel.state.value.frequencyMHz)
        viewModel.setFrequencyMHz(915.0)
        viewModel.configure(contactSource = { null }, radioId = { null })
        assertEquals(915.0, viewModel.state.value.frequencyMHz)
    }

    @Test
    fun `repeater selection roles and fit coordinates follow the points`() = losScenario {
        val radioId = RadioId(UUID.randomUUID())
        val store = FakeContactSource()
        val first = createTestContact("R1", 37.8, -122.4, ContactType.REPEATER, radioId)
        val second = createTestContact("R2", 37.7, -122.3, ContactType.REPEATER, radioId)
        val third = createTestContact("R3", 37.6, -122.2, ContactType.REPEATER, radioId)
        listOf(first, second, third).forEach(store::add)
        val viewModel = holder()
        viewModel.configure(contactSource = { store }, radioId = { radioId })
        viewModel.loadRepeaters()
        viewModel.toggleContact(first)
        viewModel.toggleContact(second)

        val roles = viewModel.state.value.selectionState
        assertEquals(PointID.POINT_A, roles[first.id]?.selectedAs)
        assertEquals(PointID.POINT_B, roles[second.id]?.selectedAs)
        assertNull(roles[third.id]?.selectedAs)
        assertEquals(3, roles.size)
        assertEquals(listOf(Coordinate(37.8, -122.4), Coordinate(37.7, -122.3), Coordinate(37.6, -122.2)), viewModel.state.value.repeaterCoordinates)
    }

    @Test
    fun `failed point elevation falls back to sea level and is reported`() = losScenario {
        val reported = mutableListOf<String>()
        val viewModel = LineOfSightStateHolder(scope, elevation, analyzer, kotlin.coroutines.EmptyCoroutineContext, { op, _ -> reported += op })
        elevation.shouldFail = true
        viewModel.setPointA(SAN_FRANCISCO)
        waitUntil("fallback elevation") { viewModel.state.value.pointA?.groundElevation != null }
        assertEquals(0.0, viewModel.state.value.pointA?.groundElevation)
        assertTrue(viewModel.state.value.elevationFetchFailed)
        assertEquals(listOf("pointElevation"), reported)
        viewModel.setPointB(OAKLAND)
        assertFalse(viewModel.state.value.elevationFetchFailed, "point changes reset the fallback flag")
    }

    @Test
    fun `failed repeater load is reported and keeps the previous list`() = losScenario {
        val reported = mutableListOf<String>()
        val viewModel = LineOfSightStateHolder(scope, elevation, analyzer, kotlin.coroutines.EmptyCoroutineContext, { op, _ -> reported += op })
        val store = FakeContactSource().apply { failure = FakeElevationError("db") }
        viewModel.configure(contactSource = { store }, radioId = { RadioId(UUID.randomUUID()) })
        viewModel.loadRepeaters()
        assertTrue(viewModel.state.value.repeatersWithLocation.isEmpty())
        assertEquals(listOf("loadRepeaters"), reported)
    }

    @Test
    fun `off-path relay offsets R-B distances and exposes segment distances`() = losScenario {
        val viewModel = readyHolder()
        val repeaterCoordinate = Coordinate(37.79, -122.35)
        viewModel.setRepeaterOffPath(repeaterCoordinate)
        waitUntil("repeater elevation") { viewModel.state.value.repeaterPoint?.groundElevation != null }
        viewModel.analyzeWithRepeater()
        waitUntil("relay") { viewModel.state.value.analysisStatus is AnalysisStatus.RelayResult }

        val state = viewModel.state.value
        val arLast = state.elevationProfileAR.last().distanceFromAMeters
        assertEquals(
            haversineMeters(SAN_FRANCISCO, repeaterCoordinate), state.elevationProfileRB.first().distanceFromAMeters,
            "R->B continues from the A->R distance",
        )
        assertEquals(repeaterCoordinate, elevation.requestedPaths.last().first(), "second fetch starts at R")
        assertEquals(state.elevationProfileAR.size + state.elevationProfileRB.size - 1, state.terrainElevationProfile.size)
        assertEquals(arLast, state.segmentARDistanceMeters)
        assertEquals(arLast / state.elevationProfileRB.last().distanceFromAMeters, state.repeaterVisualizationPathFraction)
        assertTrue(state.terrainChartInput.isOffPath)
        assertEquals(100.0, state.repeaterGroundElevation)
    }

    @Test
    fun `dispose cancels in-flight work`() = losScenario {
        val viewModel = holder()
        elevation.fetchDelayMillis = 300
        viewModel.setPointA(SAN_FRANCISCO)
        settle()
        assertEquals(1, clock.pendingSleepers)
        viewModel.dispose()
        settle()
        assertEquals(0, clock.pendingSleepers)
        clock.advanceBy(500)
        assertNull(viewModel.state.value.pointA?.groundElevation)
    }
}
