// AndroidOnly: WP-503 Execute seven remaining original Nodes flows on actual APIs with a captured, blocked system chooser.
package com.meshcoreone.android.accessibility

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.Supplier
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeNodesFlowAccessibilityTest : DeviceAccessibilityHarness() {
    @get:Rule(order = 1) val nativeFailureContext = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            compose.runOnUiThread {
                val decor = activity.window.decorView
                val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
                println("WP503_NODES_FAILURE_WINDOW|case=${description.methodName}|decor=${decor.width}x${decor.height}" +
                    "|imeVisible=${insets.isVisible(WindowInsetsCompat.Type.ime())}" +
                    "|imeBottom=${insets.getInsets(WindowInsetsCompat.Type.ime()).bottom}")
            }
            println("WP503_NODES_FAILURE_TREE|${compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE)}")
        }
    }

    private val intents = ConcurrentLinkedQueue<Intent>()
    private val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
            check(intent.action == Intent.ACTION_CHOOSER) { "Unexpected external activity during native Nodes audit: ${intent.action}" }
            intents.add(Intent(intent))
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }
    private val audit by lazy {
        NodesAccessibilityAudit(
            compose, Supplier { activity }, ::content, { AuditSurface(it) }, Supplier { intents.poll() },
        )
    }

    @Before fun captureActualChooserWithoutLaunchingAnotherApp() {
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor)
    }

    @After fun removeOwnedMonitor() {
        InstrumentationRegistry.getInstrumentation().removeMonitor(monitor)
        check(intents.isEmpty()) { "Every actual chooser intent must be consumed by the original exact-payload assertions" }
    }

    @Test fun offlineChatRepeaterAndRoomKeepTransportDisabledAndSavedHistoryRoutingReachable() =
        audit.offlineChatRepeaterAndRoomKeepTransportDisabledAndSavedHistoryRoutingReachable()

    @Test fun offlineQrShareKeepsNamedAlternativeCopyFeedbackAndExactNativeIntentReachable() =
        audit.offlineQrShareKeepsNamedAlternativeCopyFeedbackAndExactNativeIntentReachable()

    @Test fun actualSearchCrossesSelectedSegmentsAndNativeNameSortKeepsScaledResultsReachable() =
        audit.actualSearchCrossesSelectedSegmentsAndNativeNameSortKeepsScaledResultsReachable()

    @Test fun offlineCachedNodeRetainsReadableNameTextualStatusAndNativeRowRouting() =
        audit.offlineCachedNodeRetainsReadableNameTextualStatusAndNativeRowRouting()

    @Test fun offlineSyncShowsLocalizedRecoveryDialogWithReachableScaledTextAndDismissAction() =
        audit.offlineSyncShowsLocalizedRecoveryDialogWithReachableScaledTextAndDismissAction()

    @Test fun actualDetailKeepsALongCjkHeadingAndNicknameCancellationReachableAtFont200() =
        audit.actualDetailKeepsALongCjkHeadingAndNicknameCancellationReachableAtFont200()

    @Test fun offlineClearMessagesRequiresConfirmationAndExplainsUnavailableServicesWithoutDismissingDetail() =
        audit.offlineClearMessagesRequiresConfirmationAndExplainsUnavailableServicesWithoutDismissingDetail()
}
