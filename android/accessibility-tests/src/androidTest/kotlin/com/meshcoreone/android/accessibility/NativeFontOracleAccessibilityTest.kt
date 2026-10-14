// AndroidOnly: WP-503 Calibrate original native font/glyph oracles with unchanged positive and real-clipping controls.
package com.meshcoreone.android.accessibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.function.Supplier
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeFontOracleAccessibilityTest : DeviceAccessibilityHarness() {
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
