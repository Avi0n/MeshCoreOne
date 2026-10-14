// AndroidOnly: WP-503 Preserve all original backup UI names and both JVM SDK variants with the actual shadow dialog window.
package com.meshcoreone.android.accessibility

import java.util.function.Supplier
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeBackupAccessibilityTest : NativeAccessibilityHarness() {
    private val audit by lazy {
        BackupAccessibilityAudit(
            compose, Supplier { activity }, ::content, { AuditSurface(it) },
            Supplier { requireNotNull(ShadowDialog.getLatestDialog().window).decorView },
        )
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
