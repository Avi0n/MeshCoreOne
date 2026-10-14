// AndroidOnly: WP-503 Share original font-oracle positive/real-clipping controls across JVM and actual Android.
package com.meshcoreone.android.accessibility

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import java.util.function.Supplier
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

internal class FontAccessibilityAudit(
    override val compose: ComposeTestRule,
    private val host: Supplier<ComponentActivity>,
    private val render: (@Composable () -> Unit) -> Unit,
    private val surface: @Composable (@Composable () -> Unit) -> Unit,
) : NativeAccessibilityAssertions() {
    override val activity: ComponentActivity get() = host.get()

    override fun content(body: @Composable () -> Unit) = render(body)

    @Composable
    override fun AuditSurface(body: @Composable () -> Unit) = surface(body)

    fun shrinkWrappedLabelRetainsVisibleUnclippedInkDespiteWiderParagraphAllocation() {
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

    fun genuinelyClippedNativeTextIsStillRejectedWithoutAnyTolerance() {
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

    fun centeredParagraphAllocationDoesNotReplaceCompleteNativeGlyphEvidence() {
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

    fun completeNativeGlyphOracleStillRejectsActualClipping() {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    Column {
                        Box(Modifier.size(100.dp, 60.dp).clipToBounds().testTag("font-paint-viewport")) {
                            Text("No data was changed.", Modifier.requiredWidth(276.dp).testTag("font-paint-clipped"),
                                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        val node = compose.onNodeWithTag("font-paint-clipped")
        assertTrue("The negative control must actually have the constrained native viewport",
            compose.onNodeWithTag("font-paint-viewport").fetchSemanticsNode().size.width == 100)
        assertTrue("This negative control is clipped by its actual ancestor, not maxLines", !textLayout(node).multiParagraph.didExceedMaxLines)
        val failure = assertThrows(AssertionError::class.java) { assertFont200PaintedUnclipped(node) }
        assertTrue(failure.message.orEmpty().contains("retain"))
    }
}
