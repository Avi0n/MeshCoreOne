// AndroidOnly: WP-503 Calibrate original onboarding scroll observations against actual native IME and whole-action allocations.
package com.meshcoreone.android.accessibility

import android.content.Context
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.onboarding.OnboardingConnectOutcome
import com.meshcoreone.android.feature.onboarding.OnboardingWiFiPort
import com.meshcoreone.android.feature.onboarding.WiFiConnectionCoordinator
import com.meshcoreone.android.feature.onboarding.ui.WiFiConnectionSheetContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeOnboardingImeAccessibilityTest : DeviceAccessibilityHarness() {
    @Test fun nativeImeKeepsPendingAndFailedWifiActionsReachableThroughWholeActionScroll() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val outcome = CompletableDeferred<OnboardingConnectOutcome>()
        var attempts = 0
        var cancelled = 0
        val coordinator = WiFiConnectionCoordinator(scope, object : OnboardingWiFiPort {
            override suspend fun connect(host: String, port: Int): OnboardingConnectOutcome {
                assertEquals("radio.local", host)
                assertEquals(5000, port)
                attempts++
                return outcome.await()
            }
        }, onConnected = { error("Failure control must not complete onboarding") })
        coordinator.setAddress("radio.local")
        try {
            content {
                val state by coordinator.state.collectAsState()
                MeshCoreTheme(motionScale = 0f) {
                    AuditSurface {
                        WiFiConnectionSheetContent(state, coordinator::setAddress, coordinator::setPort,
                            coordinator::connect, { cancelled++ })
                    }
                }
            }
            val address = compose.onNode(hasSetTextAction() and
                hasText(activity.getString(O.wifiConnectionIpAddressPlaceholder)))
            address.performScrollTo().performClick().assertIsFocused()
            val input = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            compose.waitUntil {
                var accepting = false
                compose.runOnUiThread { accepting = input.isAcceptingText }
                accepting
            }
            compose.waitUntil { imeVisible() }
            println("WP503_WIFI_IME_CONTROL|nativeInputFocus=true|acceptingText=true|imeVisible=true")
            compose.onNodeWithText(activity.getString(O.wifiConnectionConnect)).performScrollTo().performClick()
            val progress = compose.onNodeWithText(activity.getString(O.wifiConnectionConnecting))
                .performScrollTo().assertIsNotEnabled()
            recordAllocation(progress, "pending-first-scroll")
            scrollActionBy(progress, -2f)
            val clipped = progress.fetchSemanticsNode()
            assertEquals("Calibrate the retained original two-pixel clipped-allocation signature",
                2f, clipped.size.height - clipped.boundsInRoot.height, 0f)
            assertThrows(AssertionError::class.java) { assertWholeAction(progress) }
            recordAllocation(progress, "deliberate-two-pixel-counterexample")
            scrollWholeAllocationIntoView(progress)
            assertWholeAction(progress)
            compose.onNodeWithText(activity.getString(L.commonCancel)).performScrollTo().assertIsNotEnabled()
            assertEquals(1, attempts)
            assertEquals(0, cancelled)
            outcome.complete(OnboardingConnectOutcome.Failed(UiText.Verbatim("Radio unavailable")))
            val error = compose.onNodeWithText("Radio unavailable").performScrollTo().assertIsDisplayed()
            assertFont200Unclipped(error)
            val connect = compose.onNodeWithText(activity.getString(O.wifiConnectionConnect))
            recordAllocation(connect, "after-error-scroll")
            connect.performScrollTo().assertIsEnabled()
            scrollWholeAllocationIntoView(connect)
            assertWholeAction(connect)
            assertEquals(1, attempts)
            println("WP503_WIFI_IME_CONTROL|complete=true|attempts=1|cancelled=0|realRadio=false|physicalTalkBack=false")
        } finally {
            compose.runOnUiThread {
                requireNotNull(activity.window.decorView.windowInsetsController).hide(WindowInsets.Type.ime())
            }
            scope.cancel()
        }
    }

    private fun imeVisible(): Boolean {
        var visible = false
        compose.runOnUiThread {
            visible = requireNotNull(activity.window.decorView.rootWindowInsets).isVisible(WindowInsets.Type.ime())
        }
        return visible
    }

    private fun recordAllocation(action: SemanticsNodeInteraction, phase: String) {
        val node = action.fetchSemanticsNode()
        val root = compose.onNodeWithTag("audit-root").fetchSemanticsNode().boundsInRoot
        println("WP503_WIFI_IME_ALLOCATION|phase=$phase|position=${node.positionInRoot}|bounds=${node.boundsInRoot}" +
            "|size=${node.size}|viewport=$root|imeVisible=${imeVisible()}")
    }

    private fun scrollActionBy(action: SemanticsNodeInteraction, delta: Float) {
        val scroll = generateSequence(action.fetchSemanticsNode().parent) { it.parent }.first {
            it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }
        compose.onNode(SemanticsMatcher("Actual action scroll viewport") { it.id == scroll.id }, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, delta)) }
    }

    private fun assertWholeAction(action: SemanticsNodeInteraction) {
        action.assertIsDisplayed()
        assertFont200Unclipped(action)
        val node = action.fetchSemanticsNode()
        val root = compose.onNodeWithTag("audit-root").fetchSemanticsNode().boundsInRoot
        val bounds = node.boundsInRoot
        val density = activity.resources.displayMetrics.density
        assertTrue("The complete actual action must fit without lowering the original visibility/48dp floor",
            bounds.left >= root.left && bounds.top >= root.top && bounds.right <= root.right && bounds.bottom <= root.bottom &&
                bounds.width == node.size.width.toFloat() && bounds.height == node.size.height.toFloat() &&
                bounds.width / density >= 48f && bounds.height / density >= 48f)
        recordAllocation(action, "whole-action")
    }
}
