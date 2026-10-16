package one.meshcore.shared.tools

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.tools.ui.losAnalysisPanelWidthDp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LineOfSightWorkspaceRegressionTest {
    @Test
    fun rebindingKeepsEditedFrequencyAfterDeviceSeed() = runTest {
        val model = LineOfSightViewModel(backgroundScope)
        val radio = Uuid.random()
        model.configure({ radio }, 906_000)
        model.setFrequencyMHz(915.0)
        model.configure({ radio }, 868_000)
        assertEquals(915.0, model.frequencyMHz)
    }

    @Test
    fun lateRepeaterRefreshKeepsProgrammaticCamera() = runTest {
        val model = LineOfSightViewModel(backgroundScope)
        model.configure({ Uuid.random() })
        model.frameOnUser(LatLng(38.0, -121.0))
        model.loadRepeaters()
        runCurrent()
        assertEquals(LatLng(38.0, -121.0), assertNotNull(model.cameraTarget).center)
    }

    @Test
    fun analysisLeavesMinimumMapWidthAtPairBoundary() {
        assertEquals(320, losAnalysisPanelWidthDp(640f))
    }
}
