// AndroidOnly: WP-503 Execute original permission/WiFi feature assertions on the actual selected Android API.
package com.meshcoreone.android.accessibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.function.Supplier
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeOnboardingFlowAccessibilityTest : DeviceAccessibilityHarness() {
    private val audit by lazy {
        OnboardingAccessibilityAudit(
            compose, Supplier { activity }, ::content, { AuditSurface(it) },
            ::assertFont200Unclipped, ::assertScrollableFont200Unclipped,
            { assertVisibleClickTargetsAtLeast48Dp() }, ::narrative,
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
