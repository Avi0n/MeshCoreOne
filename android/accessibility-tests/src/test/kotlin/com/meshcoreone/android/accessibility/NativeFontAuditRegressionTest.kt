// AndroidOnly: WP-503 Reject genuine clipping without misclassifying shrink-wrapped native paragraph allocations.
package com.meshcoreone.android.accessibility

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeFontAuditRegressionTest : NativeAccessibilityHarness() {
    @Test fun shrinkWrappedLabelRetainsVisibleUnclippedInkDespiteWiderParagraphAllocation() {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    Column { Text("Default", Modifier.testTag("font-short"), style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
        val node = compose.onNodeWithTag("font-short")
        val layout = font200Layout(node)
        assertTrue("Reproduce the native allocated-width counterexample", layout.didOverflowWidth)
        assertUnclippedContent(layout)
        assertFontPaintContainsInk(node)
    }

    @Test fun genuinelyClippedNativeTextIsStillRejectedWithoutAnyTolerance() {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    Column {
                        Text("clipped native text", Modifier.size(32.dp, 4.dp).testTag("font-clipped"),
                            style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    }
                }
            }
        }
        val failure = assertThrows(AssertionError::class.java) {
            assertFont200Unclipped(compose.onNodeWithTag("font-clipped"))
        }
        assertTrue(failure.message.orEmpty().contains("truncated") || failure.message.orEmpty().contains("clips"))
    }

    @Test fun centeredParagraphAllocationDoesNotReplaceCompleteNativeGlyphEvidence() {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    Column {
                        Text("No data was changed.", Modifier.testTag("font-center"),
                            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        val node = compose.onNodeWithTag("font-center")
        val layout = font200Layout(node)
        assertTrue("Reproduce the centered allocated-width counterexample", layout.getLineRight(0) > layout.size.width)
        assertFont200PaintedUnclipped(node)
    }

    @Test fun completeNativeGlyphOracleStillRejectsActualClipping() {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    Column {
                        Text("clipped native text", Modifier.size(32.dp, 4.dp).testTag("font-paint-clipped"),
                            style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    }
                }
            }
        }
        assertThrows(AssertionError::class.java) {
            assertFont200PaintedUnclipped(compose.onNodeWithTag("font-paint-clipped"))
        }
    }
}
