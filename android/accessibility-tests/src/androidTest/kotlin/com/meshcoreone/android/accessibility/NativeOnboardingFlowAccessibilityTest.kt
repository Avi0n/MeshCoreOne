// AndroidOnly: WP-503 Execute original permission/WiFi feature assertions on the actual selected Android API.
package com.meshcoreone.android.accessibility

import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import java.util.function.Supplier
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeOnboardingFlowAccessibilityTest : DeviceAccessibilityHarness() {
    private lateinit var imeHost: View
    private val imeAnimations = mutableSetOf<WindowInsetsAnimation>()
    private var imePrepares = 0
    private var imeEnds = 0

    @Before fun observeActualImeAnimationsWithoutInterceptingChildInsets() {
        compose.runOnUiThread {
            imeHost = requireNotNull(activity.findViewById(android.R.id.content))
            imeHost.setWindowInsetsAnimationCallback(object :
                WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimation) {
                    if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                        imeAnimations.add(animation)
                        imePrepares++
                    }
                }

                override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets {
                    imeAnimations.clear()
                    imeAnimations.addAll(runningAnimations.filter { it.typeMask and WindowInsets.Type.ime() != 0 })
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimation) {
                    if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                        imeAnimations.remove(animation)
                        imeEnds++
                    }
                }
            })
        }
    }

    @After fun removeOwnedImeObserver() {
        if (::imeHost.isInitialized) compose.runOnUiThread { imeHost.setWindowInsetsAnimationCallback(null) }
    }

    private fun prepareActualWifiIme() {
        check(!Looper.getMainLooper().isCurrentThread) { "Native IME observation must run outside composition on the instrumentation thread" }
        compose.onNode(hasSetTextAction() and hasText(activity.getString(O.wifiConnectionIpAddressPlaceholder)))
            .performScrollTo().performClick().assertIsFocused()
        val input = requireNotNull(activity.getSystemService(InputMethodManager::class.java))
        var ready = false
        try {
            compose.waitUntil {
                compose.runOnUiThread {
                    val insets = requireNotNull(imeHost.rootWindowInsets)
                    ready = input.isAcceptingText && insets.isVisible(WindowInsets.Type.ime()) && imeAnimations.isEmpty()
                }
                ready
            }
        } finally {
            compose.runOnUiThread {
                val insets = requireNotNull(imeHost.rootWindowInsets)
                println("WP503_WIFI_IME_OBSERVATION|ready=$ready|acceptingText=${input.isAcceptingText}" +
                    "|imeVisible=${insets.isVisible(WindowInsets.Type.ime())}|activeAnimations=${imeAnimations.size}" +
                    "|imeBottom=${insets.getInsets(WindowInsets.Type.ime()).bottom}|prepares=$imePrepares|ends=$imeEnds" +
                    "|windowFocus=${imeHost.hasWindowFocus()}|focusedView=${activity.currentFocus}")
            }
        }
        compose.runOnUiThread {
            val insets = requireNotNull(imeHost.rootWindowInsets)
            assertTrue(input.isAcceptingText && insets.isVisible(WindowInsets.Type.ime()) && imeAnimations.isEmpty())
            println("WP503_WIFI_IME_READY|acceptingText=true|imeVisible=true|activeAnimations=0" +
                "|imeBottom=${insets.getInsets(WindowInsets.Type.ime()).bottom}|prepares=$imePrepares|ends=$imeEnds")
        }
    }

    private val audit by lazy {
        OnboardingAccessibilityAudit(
            compose, Supplier { activity }, ::content, { AuditSurface(it) },
            ::assertFont200Unclipped, ::assertScrollableFont200Unclipped,
            ::scrollWholeAllocationIntoView,
            { assertVisibleClickTargetsAtLeast48Dp() }, ::narrative,
            ::prepareActualWifiIme,
        )
    }

    @After fun cancelFixtureScope() = audit.cancelFixtureScope()

    @Test fun deniedAndUndeterminedPermissionsKeepRecoveryAndContinueReachableAtFont200() =
        audit.deniedAndUndeterminedPermissionsKeepRecoveryAndContinueReachableAtFont200()

    @Test fun wifiNativeNextDoneAndFailedRetryPreserveAddressAndExposeLocalError() =
        audit.wifiNativeNextDoneAndFailedRetryPreserveAddressAndExposeLocalError()

    @Test fun wifiInitialAddressAutofocusMatchesTheNativeFrameworkControl() =
        audit.wifiInitialAddressAutofocusMatchesTheNativeFrameworkControl()

    @Test fun wifiConnectingHasTextualProgressAndDisablesDuplicateConnectAndCancel() =
        audit.wifiConnectingHasTextualProgressAndDisablesDuplicateConnectAndCancel()
}
