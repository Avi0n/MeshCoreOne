// AndroidOnly: WP-503 Execute original diagnostics assertions with an actual600dp short-window control.
package com.meshcoreone.android.accessibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.util.function.Supplier
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class NativeDiagnosticsFlowAccessibilityTest : DeviceAccessibilityHarness() {
    private val audit by lazy {
        DiagnosticsAccessibilityAudit(compose, Supplier { activity }, ::content, { AuditSurface(it) })
    }

    @get:Rule(order = -2)
    val actualShortWindow = TestRule { base, description ->
        if (description.methodName != "shorterWindowRevealsEveryNumericAlternativeByActualNativeScrollAtFont200") base
        else object : Statement() {
            override fun evaluate() {
                resize(600)
                try {
                    base.evaluate()
                } finally {
                    resize(900)
                }
            }
        }
    }

    private fun resize(height: Int) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("wm size 360x$height").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).reader().use {
                val output = it.readText()
                assertTrue("Actual window resize shell command failed: $output", output.isEmpty())
            }
        }
        val actual = automation.executeShellCommand("wm size").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).reader().use { it.readText() }
        }
        assertTrue("Actual OS viewport must match the original fixture qualifier", actual.contains("Override size: 360x$height"))
        println("WP503_DIAGNOSTICS_WINDOW|actualOverride=360x$height|beforeActivityOrAfterTeardown=true")
    }

    @Test fun noiseFloorHasDataBoundSummaryAndVisibleNumericAlternativesAtFont200() =
        audit.noiseFloorHasDataBoundSummaryAndVisibleNumericAlternativesAtFont200()

    @Test fun shorterWindowRevealsEveryNumericAlternativeByActualNativeScrollAtFont200() =
        audit.shorterWindowRevealsEveryNumericAlternativeByActualNativeScrollAtFont200()

    @Test fun disconnectedNoiseFloorExplainsUnavailableDataWithoutAnEmptyGraph() =
        audit.disconnectedNoiseFloorExplainsUnavailableDataWithoutAnEmptyGraph()

    @Test fun disconnectedCliExplainsUnavailableCommandsInsteadOfAnActiveTerminal() =
        audit.disconnectedCliExplainsUnavailableCommandsInsteadOfAnActiveTerminal()

    @Test fun disconnectedRxLogRetainsTextualStatusRecoveryAndReachableFilterSelection() =
        audit.disconnectedRxLogRetainsTextualStatusRecoveryAndReachableFilterSelection()
}
