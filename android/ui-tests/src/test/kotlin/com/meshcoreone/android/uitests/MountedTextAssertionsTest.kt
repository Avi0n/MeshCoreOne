// AndroidOnly: WP-502 Mounted positive/negative controls for text-content and viewport assertions; not original cases or font/glyph/device acceptance.
package com.meshcoreone.android.uitests

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MountedTextAssertionsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private var fontScale = 1f
    private var direction = LayoutDirection.Ltr

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun closeHost() { host.pause().stop().destroy() }

    private fun mount(scale: Float = 1f, rtl: Boolean = false, content: @Composable () -> Unit) {
        fontScale = scale
        direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        host.get().setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalLayoutDirection provides direction,
            ) {
                MeshCoreTheme(systemDark = false, motionScale = 0f) { Column(Modifier.width(300.dp)) { content() } }
            }
        }
    }

    private fun check(tag: String, text: String): TextLayoutResult = assertMountedText(
        compose, compose.onNodeWithTag(tag), text, fontScale, direction, "mounted oracle control=$tag",
    )

    private fun rejects(tag: String, text: String, reason: String) {
        val failure = assertThrows(AssertionError::class.java) { check(tag, text) }
        assertTrue("Unexpected primary rejection: ${failure.message}", failure.message.orEmpty().contains(reason))
    }

    private fun rawLayout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single()
    }

    @Test fun unconstrainedParagraphWorkspaceDoesNotReplaceActualUtf16ContentBounds() {
        val samples = listOf("New Chat", "\u2764\uFE0F 2", "\uD83D\uDC69\u200D\uD83D\uDCBB")
        mount {
            samples.forEachIndexed { index, text ->
                Row(Modifier.width(280.dp).horizontalScroll(rememberScrollState())) {
                    Text(text, Modifier.testTag("workspace-$index"), fontSize = 20.sp)
                }
            }
        }
        samples.forEachIndexed { index, text ->
            val layout = check("workspace-$index", text)
            assertTrue("Control must exercise the workspace/paint mismatch", layout.didOverflowWidth)
            assertTrue(layout.multiParagraph.width > layout.size.width)
            assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        }
    }

    @Test fun boundedAndWrappedCjkRtlAtTwoHundredPercentPreserveCompleteText() {
        val short = "\u65b0\u804a\u5929"
        val wrapped = "\u4e2d\u6587 \u05e9\u05dc\u05d5\u05dd \u4e2d\u6587 \u05e9\u05dc\u05d5\u05dd"
        mount(scale = 2f, rtl = true) {
            Text(short, Modifier.testTag("bounded"), fontSize = 20.sp)
            Text(wrapped, Modifier.width(160.dp).testTag("wrapped"), fontSize = 20.sp)
        }
        check("bounded", short)
        val layout = check("wrapped", wrapped)
        assertTrue("Control must actually wrap", layout.lineCount > 1)
        assertEquals(wrapped.length, layout.getLineEnd(layout.lineCount - 1))
    }

    @Test fun genuinelyClippedUnwrappedContentIsRejectedEvenWhenTheNodeFitsItsParent() {
        val text = "Visible prefix and a long clipped suffix"
        mount {
            Text(AnnotatedString(text), Modifier.width(64.dp).testTag("width-clip"),
                fontSize = 20.sp, softWrap = false, overflow = TextOverflow.Clip)
        }
        val layout = rawLayout("width-clip")
        assertTrue("Control must actually exceed the measured width", layout.getLineRight(0) > layout.size.width)
        assertFalse(layout.didOverflowHeight)
        rejects("width-clip", text, "line outside measured text")
    }

    @Test fun genuineEllipsisIsRejectedRatherThanAcceptedAsCompleteCopy() {
        val text = "Visible prefix and a long ellipsized suffix"
        mount {
            Text(AnnotatedString(text), Modifier.width(64.dp).testTag("ellipsis"),
                fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        assertTrue("Control must actually ellipsize", rawLayout("ellipsis").isLineEllipsized(0))
        rejects("ellipsis", text, "ellipsis line=")
    }

    @Test fun genuinelyHeightClippedMultilineContentIsRejected() {
        val text = "First\nSecond\nThird"
        mount {
            Text(AnnotatedString(text), Modifier.width(180.dp).height(24.dp).testTag("height-clip"),
                fontSize = 20.sp, lineHeight = 24.sp, overflow = TextOverflow.Clip)
        }
        assertTrue("Control must actually exceed the measured height", rawLayout("height-clip").didOverflowHeight)
        rejects("height-clip", text, "|vertical")
    }

    @Test fun partiallyVisibleFortyEightDpTargetFailsUntilRealScrollRevealsItsWholeCopyAndBounds() {
        mount {
            Row(Modifier.width(80.dp).horizontalScroll(rememberScrollState())) {
                Spacer(Modifier.width(72.dp))
                Box(Modifier.size(64.dp).clickable {}.testTag("viewport-target"), contentAlignment = Alignment.Center) {
                    Text("OK", fontSize = 20.sp)
                }
            }
        }
        val target = compose.onNodeWithTag("viewport-target").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val partiallyVisible = target.fetchSemanticsNode()
        assertTrue(partiallyVisible.boundsInRoot.width < partiallyVisible.size.width)
        rejects("viewport-target", "OK", "WP502_TEXT_VIEWPORT")
        target.performScrollTo()
        check("viewport-target", "OK")
    }
}
