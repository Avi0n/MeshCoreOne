// AndroidOnly: WP-503 Preserve original diagnostics cases, SDK variants and600dp window qualifier.
package com.meshcoreone.android.accessibility

import java.util.function.Supplier
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeDiagnosticsAccessibilityTest : NativeAccessibilityHarness() {
    private val audit by lazy {
        DiagnosticsAccessibilityAudit(compose, Supplier { activity }, ::content, { AuditSurface(it) })
    }

    @Test fun noiseFloorHasDataBoundSummaryAndVisibleNumericAlternativesAtFont200() =
        audit.noiseFloorHasDataBoundSummaryAndVisibleNumericAlternativesAtFont200()

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h600dp-mdpi")
    fun shorterWindowRevealsEveryNumericAlternativeByActualNativeScrollAtFont200() =
        audit.shorterWindowRevealsEveryNumericAlternativeByActualNativeScrollAtFont200()

    @Test fun disconnectedNoiseFloorExplainsUnavailableDataWithoutAnEmptyGraph() =
        audit.disconnectedNoiseFloorExplainsUnavailableDataWithoutAnEmptyGraph()

    @Test fun disconnectedCliExplainsUnavailableCommandsInsteadOfAnActiveTerminal() =
        audit.disconnectedCliExplainsUnavailableCommandsInsteadOfAnActiveTerminal()

    @Test fun disconnectedRxLogRetainsTextualStatusRecoveryAndReachableFilterSelection() =
        audit.disconnectedRxLogRetainsTextualStatusRecoveryAndReachableFilterSelection()
}
