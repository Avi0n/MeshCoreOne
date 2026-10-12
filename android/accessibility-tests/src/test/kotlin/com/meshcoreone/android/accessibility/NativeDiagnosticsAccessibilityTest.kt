// AndroidOnly: WP-503 Actual diagnostics graph/value/status alternatives with deterministic radio data.
package com.meshcoreone.android.accessibility

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings as T
import com.meshcoreone.android.core.protocol.event.RadioStats
import com.meshcoreone.android.feature.tools.diagnostics.ToolsDiagnosticsDependencies
import com.meshcoreone.android.feature.tools.diagnostics.ToolsDiagnosticsScreen
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliToolFeatureDependencies
import com.meshcoreone.android.feature.tools.diagnostics.noisefloor.RadioStatsSource
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogFeatureDependencies
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogRouteFilter
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
        verifyPopulatedNoiseFloor(requireScrollMovement = false)
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h600dp-mdpi")
    fun shorterWindowRevealsEveryNumericAlternativeByActualNativeScrollAtFont200() {
        verifyPopulatedNoiseFloor(requireScrollMovement = true)
    }

    private fun verifyPopulatedNoiseFloor(requireScrollMovement: Boolean) {
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
        val values = listOf(T.toolsNoiseFloorMinimum, T.toolsNoiseFloorMaximum, T.toolsNoiseFloorAverage).map { id ->
            val label = host.get().getString(id)
            val node = compose.onNode(hasText(label, substring = true) and
                SemanticsMatcher("Numeric data is present") { it.config[SemanticsProperties.Text].any { text -> text.text.contains("-110") } })
            val measured = node.fetchSemanticsNode()
            println("WP503_GRAPH_VALUE|label=$label|bounds=${measured.boundsInRoot}|size=${measured.size}" +
                "|viewport=${compose.onNodeWithTag("audit-root").fetchSemanticsNode().boundsInRoot}")
            node
        }
        var moved = false
        for (node in values) {
            val before = node.fetchSemanticsNode()
            val beforePosition = before.positionInRoot
            val beforeBounds = before.boundsInRoot
            val ancestors = generateSequence(node.fetchSemanticsNode()) { it.parent }
            if (ancestors.any {
                it.config.contains(SemanticsActions.ScrollBy) &&
                    it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
            }) {
                node.performScrollTo()
            }
            node.assertIsDisplayed()
            assertFont200Unclipped(node)
            val after = node.fetchSemanticsNode()
            val root = compose.onNodeWithTag("audit-root").fetchSemanticsNode().boundsInRoot
            val bounds = after.boundsInRoot
            println("WP503_GRAPH_REACHABLE|before=$beforeBounds|after=$bounds|position=$beforePosition->${after.positionInRoot}")
            assertTrue("The full numeric text must be visible after its native scroll action",
                bounds.left >= root.left && bounds.top >= root.top &&
                    bounds.right <= root.right && bounds.bottom <= root.bottom &&
                    bounds.width == after.size.width.toFloat() && bounds.height == after.size.height.toFloat())
            moved = moved || beforePosition != after.positionInRoot
        }
        if (requireScrollMovement) assertTrue("The smaller real window must exercise actual scroll movement", moved)
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

    @Test fun disconnectedCliExplainsUnavailableCommandsInsteadOfAnActiveTerminal() {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface { ToolsDiagnosticsScreen(dependencies(false, null), ToolSelection.CLI) }
            }
        }
        compose.onNodeWithTag("diagnostics-page:CLI").assertIsSelected()
        val inputs = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).fetchSemanticsNodes()
        println("WP503_CLI_OFFLINE|editableInputs=${inputs.size}|disabled=${inputs.map { it.config.contains(SemanticsProperties.Disabled) }}")
        val description = compose.onNodeWithText(host.get().getString(T.toolsCliNotConnectedDescription)).assertIsDisplayed()
        assertFont200Unclipped(description)
        compose.onNodeWithText(host.get().getString(T.toolsCliNotConnected)).assertIsDisplayed()
        assertVisibleClickTargetsAtLeast48Dp()
    }

    @Test fun disconnectedRxLogRetainsTextualStatusRecoveryAndReachableFilterSelection() {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface { ToolsDiagnosticsScreen(dependencies(false, null), ToolSelection.RX_LOG) }
            }
        }
        compose.onNodeWithTag("diagnostics-page:RX_LOG").assertIsSelected()
        val status = compose.onNodeWithText(host.get().getString(T.toolsRxLogOffline)).assertIsDisplayed()
        assertFont200Unclipped(status)
        val description = compose.onNodeWithText(host.get().getString(T.toolsRxLogNotConnectedDescription)).assertIsDisplayed()
        assertFont200Unclipped(description)
        val filter = compose.onNodeWithText(host.get().getString(RxLogRouteFilter.FLOOD_ONLY.labelId)).performScrollTo()
        filter.performClick().assertIsSelected()
        assertFont200Unclipped(filter)
        assertVisibleClickTargetsAtLeast48Dp()
        description.assertIsDisplayed()
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
