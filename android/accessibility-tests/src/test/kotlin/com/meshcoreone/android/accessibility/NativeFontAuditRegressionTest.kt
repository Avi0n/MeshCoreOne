// AndroidOnly: WP-503 Preserve original positive/negative font-oracle cases and both JVM SDK variants.
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
class NativeFontAuditRegressionTest : NativeAccessibilityHarness() {
    private val audit by lazy {
        FontAccessibilityAudit(compose, Supplier { activity }, ::content, { AuditSurface(it) })
    }

    @Test fun shrinkWrappedLabelRetainsVisibleUnclippedInkDespiteWiderParagraphAllocation() =
        audit.shrinkWrappedLabelRetainsVisibleUnclippedInkDespiteWiderParagraphAllocation()

    @Test fun genuinelyClippedNativeTextIsStillRejectedWithoutAnyTolerance() =
        audit.genuinelyClippedNativeTextIsStillRejectedWithoutAnyTolerance()

    @Test fun centeredParagraphAllocationDoesNotReplaceCompleteNativeGlyphEvidence() =
        audit.centeredParagraphAllocationDoesNotReplaceCompleteNativeGlyphEvidence()

    @Test fun completeNativeGlyphOracleStillRejectsActualClipping() =
        audit.completeNativeGlyphOracleStillRejectsActualClipping()
}
