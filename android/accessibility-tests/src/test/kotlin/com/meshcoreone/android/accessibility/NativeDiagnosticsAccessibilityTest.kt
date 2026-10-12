// AndroidOnly: WP-503 Actual diagnostics graph/value/status alternatives with deterministic radio data.
package com.meshcoreone.android.accessibility

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings as T
import com.meshcoreone.android.core.protocol.event.RadioStats
import com.meshcoreone.android.feature.tools.diagnostics.ToolsDiagnosticsDependencies
import com.meshcoreone.android.feature.tools.diagnostics.ToolsDiagnosticsScreen
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliToolFeatureDependencies
import com.meshcoreone.android.feature.tools.diagnostics.noisefloor.RadioStatsSource
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogFeatureDependencies
import com.meshcoreone.android.feature.tools.navigation.ToolSelection
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeDiagnosticsAccessibilityTest : NativeAccessibilityHarness() {
    @Test fun noiseFloorHasDataBoundSummaryAndVisibleNumericAlternativesAtFont200() {
        val source = RadioStatsSource { RadioStats(-110, -80, 5.5, 0u, 0u) }
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface { ToolsDiagnosticsScreen(dependencies(true, source), ToolSelection.NOISE_FLOOR) }
            }
        }
        val graph = compose.onNodeWithTag("noise-floor-chart").assertIsDisplayed().fetchSemanticsNode()
        val summary = graph.config[SemanticsProperties.ContentDescription]
        assertTrue("Summary must expose the actual reading count", summary.any { Regex("\\b1\\b").containsMatchIn(it) })
        assertTrue("Summary must expose actual minimum, maximum and average",
            summary.any { Regex("-110").findAll(it).count() == 3 })
        compose.onNodeWithTag("diagnostics-page:NOISE_FLOOR").assertIsSelected()
        compose.onNodeWithText("-110 dBm").assertIsDisplayed()
        for (id in listOf(T.toolsNoiseFloorMinimum, T.toolsNoiseFloorMaximum, T.toolsNoiseFloorAverage)) {
            val label = host.get().getString(id)
            val node = compose.onNode(hasText(label, substring = true) and
                SemanticsMatcher("Numeric data is present") { it.config[SemanticsProperties.Text].any { text -> text.text.contains("-110") } })
            println("WP503_GRAPH_VALUE|label=$label|bounds=${node.fetchSemanticsNode().boundsInRoot}" +
                "|viewport=${compose.onNodeWithTag("audit-root").fetchSemanticsNode().boundsInRoot}")
            node.assertIsDisplayed()
            assertFont200Unclipped(node)
        }
        assertVisibleClickTargetsAtLeast48Dp()
    }

    @Test fun disconnectedNoiseFloorExplainsUnavailableDataWithoutAnEmptyGraph() {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface { ToolsDiagnosticsScreen(dependencies(false, null), ToolSelection.NOISE_FLOOR) }
            }
        }
        val description = host.get().getString(T.toolsNoiseFloorNotConnectedDescription)
        compose.onNodeWithText(description).assertIsDisplayed()
        assertFont200Unclipped(compose.onNodeWithText(description))
        assertTrue(compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .fetchSemanticsNodes().none { it.config[SemanticsProperties.ContentDescription].any { label -> label.contains("-110") } })
        assertVisibleClickTargetsAtLeast48Dp()
    }

    private fun dependencies(connected: Boolean, source: RadioStatsSource?) = ToolsDiagnosticsDependencies(
        cli = CliToolFeatureDependencies(
            { null }, { null }, { null }, { null }, { null }, { null },
            { error("Noise-floor flow must never transmit self advertisements") },
        ),
        rxLog = RxLogFeatureDependencies({ null }, { null }, { null }),
        radioStats = { source }, isConnected = { connected }, localPublicKeyPrefix = { null },
        connectionVersion = MutableStateFlow(0),
    )
}
