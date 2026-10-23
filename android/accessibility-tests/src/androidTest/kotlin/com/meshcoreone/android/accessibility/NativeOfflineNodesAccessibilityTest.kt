// AndroidOnly: WP-503 Execute the unchanged offline Nodes feature assertions on actual selected Android APIs.
package com.meshcoreone.android.accessibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeOfflineNodesAccessibilityTest : DeviceAccessibilityHarness() {
    private val audit by lazy {
        OfflineNodesAudit(compose, activity, ::content, { body -> AuditSurface(body) },
            ::assertFont200Unclipped, ::assertVisibleClickTargetsAtLeast48Dp)
    }

    @Test fun offlinePathAndTransportActionsKeepDisabledStateAndFullLabelsAtFont200() =
        audit.offlinePathAndTransportActionsKeepDisabledStateAndFullLabelsAtFont200()

    @Test fun offlineDiscoveryHasATextualCachedOutcomeInsteadOfAnUnownedIndefiniteSpinner() =
        audit.offlineDiscoveryHasATextualCachedOutcomeInsteadOfAnUnownedIndefiniteSpinner()
}
