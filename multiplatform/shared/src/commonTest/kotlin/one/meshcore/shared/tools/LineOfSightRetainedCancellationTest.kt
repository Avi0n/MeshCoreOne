package one.meshcore.shared.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.map.ElevationService
import one.meshcore.shared.map.FakeGeoHttpClient
import one.meshcore.shared.map.GeoHttpResponse
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LineOfSightRetainedCancellationTest {
    @Test fun cancelledEndpointSuccessCannotFillReplacement() = runTest { cancelledEndpoint(false) }

    @Test fun cancelledEndpointFailureCannotPublishSeaLevelWarning() = runTest { cancelledEndpoint(true) }

    private suspend fun TestScope.cancelledEndpoint(fail: Boolean) {
        val late = CompletableDeferred<Unit>()
        val current = CompletableDeferred<Unit>()
        var calls = 0
        val model = LineOfSightViewModel(
            backgroundScope,
            ElevationService(
                FakeGeoHttpClient {
                    val wait = if (++calls == 1) late else current
                    withContext(NonCancellable) { wait.await() }
                    if (fail) error("retired endpoint") else GeoHttpResponse(200, "{\"elevation\":[123]}")
                },
            ),
        )
        model.setPointA(pointA)
        runCurrent()
        model.setPointA(pointA)
        runCurrent()
        late.complete(Unit)
        runCurrent()
        assertNull(model.pointA?.groundElevation)
        assertFalse(model.elevationFetchFailed)
        model.close()
        current.complete(Unit)
        runCurrent()
    }

    @Test fun clearedAnalysisRejectsLateSuccess() = runTest { clearedAnalysis(false, false) }

    @Test fun clearedAnalysisRejectsLateFailure() = runTest { clearedAnalysis(true, false) }

    @Test fun clearedRelayAnalysisRejectsLateSuccess() = runTest { clearedAnalysis(false, true) }

    @Test fun clearedRelayAnalysisRejectsLateFailure() = runTest { clearedAnalysis(true, true) }

    private suspend fun TestScope.clearedAnalysis(fail: Boolean, relay: Boolean) {
        val late = CompletableDeferred<Unit>()
        val model = LineOfSightViewModel(
            backgroundScope,
            ElevationService(
                FakeGeoHttpClient { url ->
                    withContext(NonCancellable) { late.await() }
                    if (fail) error("retired terrain")
                    val count = url.substringAfter("latitude=").substringBefore('&').split(',').size
                    GeoHttpResponse(200, "{\"elevation\":[${List(count) { "0" }.joinToString(",")}]}")
                },
            ),
        )
        model.pointA = LosSelectedPoint(pointA, groundElevation = 0.0)
        model.pointB = LosSelectedPoint(pointB, groundElevation = 0.0)
        if (relay) {
            model.repeaterPoint = LosRepeaterPoint(LatLng(38.05, -121.05), 0.0, isOnPath = false)
            model.analyzeWithRepeater()
        } else {
            model.analyze()
        }
        runCurrent()
        model.clearAnalysisResults()
        late.complete(Unit)
        runCurrent()
        assertIs<LosAnalysisStatus.Idle>(model.analysisStatus)
        assertFalse(model.isAnalyzing)
        assertEquals(0, model.elevationProfile.size)
        assertEquals(0, model.elevationProfileAr.size)
        assertEquals(0, model.profileSamples.size)
    }

    @Test
    fun missingRadioRetiresPendingRepeaterLoad() = runTest {
        var radio: Uuid? = Uuid.random()
        val pending = CompletableDeferred<Unit>()
        val model = LineOfSightViewModel(backgroundScope, fetchContacts = {
            withContext(NonCancellable) { pending.await() }
            emptyList()
        })
        model.configure({ radio })
        model.loadRepeaters()
        runCurrent()
        val revision = model.revision.value
        radio = null
        model.loadRepeaters()
        pending.complete(Unit)
        runCurrent()
        assertEquals(revision, model.revision.value)
    }

    @Test
    fun frequencyEditCancelsUncachedPendingAnalysis() = runTest {
        val late = CompletableDeferred<Unit>()
        val model = LineOfSightViewModel(
            backgroundScope,
            ElevationService(
                FakeGeoHttpClient { url ->
                    withContext(NonCancellable) { late.await() }
                    val count = url.substringAfter("latitude=").substringBefore('&').split(',').size
                    GeoHttpResponse(200, "{\"elevation\":[${List(count) { "0" }.joinToString(",")}]}")
                },
            ),
        )
        model.pointA = LosSelectedPoint(pointA, groundElevation = 0.0)
        model.pointB = LosSelectedPoint(pointB, groundElevation = 0.0)
        model.analyze()
        runCurrent()
        model.setFrequencyMHz(868.0)
        late.complete(Unit)
        runCurrent()
        assertIs<LosAnalysisStatus.Idle>(model.analysisStatus)
        assertFalse(model.isAnalyzing)
    }

    private companion object {
        val pointA = LatLng(38.0, -121.0)
        val pointB = LatLng(38.1, -121.1)
    }
}
