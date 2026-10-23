// AndroidOnly: WP-503 Execute original mounted room/direct state and live-region assertions on the actual selected API.
package com.meshcoreone.android.accessibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.function.Supplier
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMessagingFlowAccessibilityTest : DeviceAccessibilityHarness() {
    private val audit by lazy {
        MessagingAccessibilityAudit(compose, Supplier { activity }, ::content, { AuditSurface(it) })
    }

    @Test fun roomFollowUpRetainsAuthorContextWithoutAnnouncingTheWholeChat() =
        audit.roomFollowUpRetainsAuthorContextWithoutAnnouncingTheWholeChat()

    @Test fun roomOutgoingSentStatusIsExposedExactlyOnceOnItsMessage() =
        audit.roomOutgoingSentStatusIsExposedExactlyOnceOnItsMessage()

    @Test fun roomClusterStartAnnouncesItsAuthorExactlyOnce() =
        audit.roomClusterStartAnnouncesItsAuthorExactlyOnce()

    @Test fun roomSentMessageHasNoRetryAction() =
        audit.roomSentMessageHasNoRetryAction()

    @Test fun roomFailedRetryHasANativeTargetAndInvokesTheCorrectMessageOnly() =
        audit.roomFailedRetryHasANativeTargetAndInvokesTheCorrectMessageOnly()

    @Test fun roomHttpsMessageExposesANamedNonGestureLinkAction() =
        audit.roomHttpsMessageExposesANamedNonGestureLinkAction()

    @Test fun rememberPasswordSwitchHasPurposeAndCheckedStateOnOneNode() =
        audit.rememberPasswordSwitchHasPurposeAndCheckedStateOnOneNode()

    @Test fun directFailureRetryAndMixedDirectionTextHonorSystemFontAndTargets() =
        audit.directFailureRetryAndMixedDirectionTextHonorSystemFontAndTargets()

    @Test fun auditDetectsLiveRegionOnTheActualTimelineInsteadOfOnlyTheTestSurface() =
        audit.auditDetectsLiveRegionOnTheActualTimelineInsteadOfOnlyTheTestSurface()

    @Test fun auditAllowsASmallFailureOnlyLiveRegionWithoutAnnouncingOtherRows() =
        audit.auditAllowsASmallFailureOnlyLiveRegionWithoutAnnouncingOtherRows()
}
