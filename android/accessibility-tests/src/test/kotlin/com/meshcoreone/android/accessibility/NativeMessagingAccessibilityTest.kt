// AndroidOnly: WP-503 Preserve all original mounted chat/room cases and both JVM SDK variants.
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
class NativeMessagingAccessibilityTest : NativeAccessibilityHarness() {
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

    @Test fun renderedDirectAndChannelSpansClearTheirPaintedBubblesInAll38EffectiveFrames() =
        audit.renderedDirectAndChannelSpansClearTheirPaintedBubblesInAll38EffectiveFrames()
}
