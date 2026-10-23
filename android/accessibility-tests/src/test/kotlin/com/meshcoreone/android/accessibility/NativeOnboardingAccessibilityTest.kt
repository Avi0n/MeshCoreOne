// AndroidOnly: WP-503 Preserve both original JVM variants of the shared permission/WiFi accessibility assertions.
package com.meshcoreone.android.accessibility

import java.util.function.Supplier
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeOnboardingAccessibilityTest : NativeAccessibilityHarness() {
    private val audit by lazy {
        OnboardingAccessibilityAudit(
            compose, Supplier { activity }, ::content, { AuditSurface(it) },
            ::assertFont200Unclipped, ::assertScrollableFont200Unclipped,
            ::scrollWholeAllocationIntoView,
            { assertVisibleClickTargetsAtLeast48Dp() }, ::narrative,
            prepareWifiIme = {}, // JVM windows do not provide native IME animation observations.
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
