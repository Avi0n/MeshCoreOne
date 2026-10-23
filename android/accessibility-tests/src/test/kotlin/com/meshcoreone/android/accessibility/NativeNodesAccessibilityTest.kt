// AndroidOnly: WP-503 Preserve every original Nodes case and both JVM SDK variants with exact framework-intent capture.
package com.meshcoreone.android.accessibility

import java.util.function.Supplier
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeNodesAccessibilityTest : NativeAccessibilityHarness() {
    private val audit by lazy {
        NodesAccessibilityAudit(
            compose, Supplier { activity }, ::content, { AuditSurface(it) },
            Supplier { shadowOf(activity).nextStartedActivity },
        )
    }

    @Test fun offlineChatRepeaterAndRoomKeepTransportDisabledAndSavedHistoryRoutingReachable() =
        audit.offlineChatRepeaterAndRoomKeepTransportDisabledAndSavedHistoryRoutingReachable()

    @Test fun offlinePathAndTransportActionsKeepDisabledStateAndFullLabelsAtFont200() =
        audit.offlinePathAndTransportActionsKeepDisabledStateAndFullLabelsAtFont200()

    @Test fun offlineQrShareKeepsNamedAlternativeCopyFeedbackAndExactNativeIntentReachable() =
        audit.offlineQrShareKeepsNamedAlternativeCopyFeedbackAndExactNativeIntentReachable()

    @Test fun actualSearchCrossesSelectedSegmentsAndNativeNameSortKeepsScaledResultsReachable() =
        audit.actualSearchCrossesSelectedSegmentsAndNativeNameSortKeepsScaledResultsReachable()

    @Test fun offlineDiscoveryHasATextualCachedOutcomeInsteadOfAnUnownedIndefiniteSpinner() =
        audit.offlineDiscoveryHasATextualCachedOutcomeInsteadOfAnUnownedIndefiniteSpinner()

    @Test fun offlineCachedNodeRetainsReadableNameTextualStatusAndNativeRowRouting() =
        audit.offlineCachedNodeRetainsReadableNameTextualStatusAndNativeRowRouting()

    @Test fun offlineSyncShowsLocalizedRecoveryDialogWithReachableScaledTextAndDismissAction() =
        audit.offlineSyncShowsLocalizedRecoveryDialogWithReachableScaledTextAndDismissAction()

    @Test fun actualDetailKeepsALongCjkHeadingAndNicknameCancellationReachableAtFont200() =
        audit.actualDetailKeepsALongCjkHeadingAndNicknameCancellationReachableAtFont200()

    @Test fun offlineClearMessagesRequiresConfirmationAndExplainsUnavailableServicesWithoutDismissingDetail() =
        audit.offlineClearMessagesRequiresConfirmationAndExplainsUnavailableServicesWithoutDismissingDetail()
}
