// AndroidOnly: WP-503 Execute original backup UI assertions against actual Android dialog windows, not SAF/codec/restore acceptance.
package com.meshcoreone.android.accessibility

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.function.Supplier
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeBackupFlowAccessibilityTest : DeviceAccessibilityHarness() {
    private val audit by lazy {
        BackupAccessibilityAudit(
            compose, Supplier { activity }, ::content, { AuditSurface(it) }, Supplier { dialogRoot() },
        )
    }

    private fun dialogRoot(): View {
        var root: View? = null
        onView(isRoot()).inRoot(isDialog()).check { view, missing ->
            if (missing != null) throw missing
            root = requireNotNull(view)
        }
        return requireNotNull(root) { "The native painted-text oracle requires the actual visible dialog root" }
    }

    @Test fun connectedImportExplainsItsGateAndPickerCancellationAllowsAnotherAttempt() =
        audit.connectedImportExplainsItsGateAndPickerCancellationAllowsAnotherAttempt()

    @Test fun typedParseFailureIsReadableAndDismissalRestoresTheActualImportAction() =
        audit.typedParseFailureIsReadableAndDismissalRestoresTheActualImportAction()

    @Test fun previewAndMixedResultExposeTheirActualCountsAndScrollableCompletion() =
        audit.previewAndMixedResultExposeTheirActualCountsAndScrollableCompletion()

    @Test fun pendingImportHasTextualProgressAndAnAccessibleCancellationOutcome() =
        audit.pendingImportHasTextualProgressAndAnAccessibleCancellationOutcome()
}
