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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextAlign
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
    private val nativeCaretEvidence = mutableMapOf<String, String>()

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
        compose, compose.onNodeWithTag(tag), text, fontScale, direction,
        "mounted oracle control=$tag|${nativeCaretEvidence.getOrDefault(tag, "native-callback=not-observed")}",
    )

    private fun rejects(tag: String, text: String, reason: String) {
        val failure = assertThrows(AssertionError::class.java) { check(tag, text) }
        assertTrue("Unexpected primary rejection: ${failure.message}", failure.message.orEmpty().contains(reason))
    }

    private fun rawLayout(tag: String, useUnmergedTree: Boolean = false): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = useUnmergedTree)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single()
    }

    private fun observeNativeCaretGeometry(semantics: TextLayoutResult, native: TextLayoutResult, tag: String) {
        val paint = compose.runOnIdle { mountedPaintLayout(semantics) }
        for ((source, layout) in listOf("semantics" to semantics, "derived" to paint, "native-callback" to native)) {
            println("WP502_NATIVE_CARET_INPUT|tag=$tag|source=$source|size=${layout.size}" +
                "|paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}|constraints=${layout.layoutInput.constraints}" +
                "|fontScale=${layout.layoutInput.density.fontScale}|direction=${layout.layoutInput.layoutDirection}" +
                "|align=${layout.layoutInput.style.textAlign}|overflow=${layout.layoutInput.overflow}" +
                "|vertical=${layout.didOverflowHeight}|text=${layout.layoutInput.text.text}")
            for (line in 0 until layout.lineCount) {
                println("WP502_NATIVE_CARET_LINE|tag=$tag|source=$source|line=$line" +
                    "|range=${layout.getLineStart(line)}..${layout.getLineEnd(line)}" +
                    "|edges=${layout.getLineLeft(line)},${layout.getLineTop(line)},${layout.getLineRight(line)},${layout.getLineBottom(line)}" +
                    "|baseline=${layout.getLineBaseline(line)}|ellipsis=${layout.isLineEllipsized(line)}")
            }
            layout.layoutInput.text.text.indices.forEach { offset ->
                println("WP502_NATIVE_CARET_UNIT|tag=$tag|source=$source|UTF16=$offset" +
                    "|unit=${layout.layoutInput.text.text[offset].code}|bounds=${layout.getBoundingBox(offset)}" +
                    "|line=${layout.getLineForOffset(offset)}|paragraphDirection=${layout.getParagraphDirection(offset)}" +
                    "|runDirection=${layout.getBidiRunDirection(offset)}")
            }
            for (offset in 0..layout.layoutInput.text.length) {
                println("WP502_NATIVE_CARET_POSITION|tag=$tag|source=$source|offset=$offset" +
                    "|primary=${layout.getHorizontalPosition(offset, true)}|secondary=${layout.getHorizontalPosition(offset, false)}")
            }
        }
    }

    private fun assertNativePaintAgreement(semantics: TextLayoutResult, native: TextLayoutResult, tag: String) {
        val paint = compose.runOnIdle { mountedPaintLayout(semantics) }
        val diagnostic = "native paint witness=$tag|semanticsSize=${semantics.size}|paintSize=${paint.size}|nativeSize=${native.size}"
        assertEquals(diagnostic, native.layoutInput.text, paint.layoutInput.text)
        assertEquals(diagnostic, native.layoutInput.style, paint.layoutInput.style)
        assertEquals(diagnostic, native.layoutInput.density.density, paint.layoutInput.density.density, 0f)
        assertEquals(diagnostic, native.layoutInput.density.fontScale, paint.layoutInput.density.fontScale, 0f)
        assertEquals(diagnostic, native.layoutInput.layoutDirection, paint.layoutInput.layoutDirection)
        assertEquals(diagnostic, native.layoutInput.softWrap, paint.layoutInput.softWrap)
        assertEquals(diagnostic, native.layoutInput.maxLines, paint.layoutInput.maxLines)
        assertEquals(diagnostic, native.layoutInput.overflow, paint.layoutInput.overflow)
        assertEquals(diagnostic, native.size, paint.size)
        assertEquals(diagnostic, native.multiParagraph.width, paint.multiParagraph.width, 0f)
        assertEquals(diagnostic, native.multiParagraph.height, paint.multiParagraph.height, 0f)
        assertEquals(diagnostic, native.firstBaseline, paint.firstBaseline, 0f)
        assertEquals(diagnostic, native.lastBaseline, paint.lastBaseline, 0f)
        assertEquals(diagnostic, native.lineCount, paint.lineCount)
        for (line in 0 until native.lineCount) {
            assertEquals(diagnostic, native.getLineStart(line), paint.getLineStart(line))
            assertEquals(diagnostic, native.getLineEnd(line), paint.getLineEnd(line))
            assertEquals(diagnostic, native.getLineLeft(line), paint.getLineLeft(line), 0f)
            assertEquals(diagnostic, native.getLineRight(line), paint.getLineRight(line), 0f)
            assertEquals(diagnostic, native.getLineTop(line), paint.getLineTop(line), 0f)
            assertEquals(diagnostic, native.getLineBottom(line), paint.getLineBottom(line), 0f)
            assertEquals(diagnostic, native.isLineEllipsized(line), paint.isLineEllipsized(line))
        }
        native.layoutInput.text.text.indices.forEach { offset ->
            assertEquals("$diagnostic|UTF16=$offset", native.getBoundingBox(offset), paint.getBoundingBox(offset))
        }
    }

    private fun retainNativeCaretEvidence(native: TextLayoutResult, vararg tags: String) {
        val lines = (0 until native.lineCount).joinToString { line ->
            "$line:${native.getLineLeft(line)},${native.getLineTop(line)},${native.getLineRight(line)},${native.getLineBottom(line)}"
        }
        val boxes = native.layoutInput.text.text.indices.joinToString { offset -> "$offset:${native.getBoundingBox(offset)}" }
        val evidence = "nativeDerivedAgreement=passed|nativeSize=${native.size}" +
            "|nativeParagraph=${native.multiParagraph.width}x${native.multiParagraph.height}|nativeLines=$lines|nativeUtf16=$boxes"
        tags.forEach { nativeCaretEvidence[it] = evidence }
    }

    private fun mixedBidiPaintWitnesses(rtl: Boolean) {
        val hebrewFirst = "\u05e9\u05dc\u05d5\u05dd Beta"
        val latinFirst = "Beta \u05e9\u05dc\u05d5\u05dd"
        val wrapped = "\u05e9\u05dc\u05d5\u05dd Beta \u4e2d\u6587 \u05e9\u05dc\u05d5\u05dd Beta \uD83D\uDC69\u200D\uD83D\uDCBB"
        val native = mutableMapOf<String, TextLayoutResult>()
        mount(scale = if (rtl) 2f else 1f, rtl = rtl) {
            @Composable fun pair(tag: String, text: String, modifier: Modifier, align: TextAlign) {
                Text(text, modifier.testTag(tag), fontSize = 16.sp, textAlign = align)
                Text(text, modifier.testTag("$tag-native"), fontSize = 16.sp, textAlign = align,
                    onTextLayout = { native[tag] = it })
            }
            pair("content-bidi", hebrewFirst, Modifier.widthIn(max = 280.dp), TextAlign.Start)
            pair("center-bidi", latinFirst, Modifier.widthIn(max = 280.dp), TextAlign.Center)
            pair("fixed-right-bidi", hebrewFirst, Modifier.width(260.dp), TextAlign.Right)
            pair("wrapped-center-bidi", wrapped, Modifier.width(180.dp), TextAlign.Center)
            pair("wrapped-right-bidi", wrapped, Modifier.width(180.dp), TextAlign.Right)
        }
        val samples = listOf(
            "content-bidi" to hebrewFirst, "center-bidi" to latinFirst, "fixed-right-bidi" to hebrewFirst,
            "wrapped-center-bidi" to wrapped, "wrapped-right-bidi" to wrapped,
        )
        for ((tag, text) in samples) {
            val semantics = rawLayout(tag)
            val painted = compose.runOnIdle { native.getValue(tag) }
            observeNativeCaretGeometry(semantics, painted, tag)
            assertNativePaintAgreement(semantics, painted, tag)
            retainNativeCaretEvidence(painted, tag, "$tag-native")
            if (tag == "fixed-right-bidi") {
                val raster = observeMountedNativePaint(compose, compose.onNodeWithTag(tag), painted, text, tag)
                nativeCaretEvidence[tag] = "$raster|${nativeCaretEvidence.getValue(tag)}"
            }
            println("WP502_NATIVE_CARET_AGREEMENT|tag=$tag|allNativeDerivedParagraphLineAndUtf16AssertionsReached=true")
            check(tag, text)
            check("$tag-native", text)
            if (tag.startsWith("wrapped-")) assertTrue("Native witness must actually wrap", painted.lineCount > 1)
        }
        val reconstructed = rawLayout("content-bidi")
        assertTrue("Mixed-bidi witness must expose wider reconstructed workspace",
            reconstructed.multiParagraph.width > reconstructed.size.width)
        assertTrue("Mixed-bidi witness must expose shifted reconstructed line origin",
            reconstructed.getLineRight(0) > reconstructed.size.width)
        val centered = rawLayout("center-bidi")
        assertTrue("Centered witness must expose wider reconstructed workspace",
            centered.multiParagraph.width > centered.size.width)
        assertTrue("Centered witness must expose shifted reconstructed line origin",
            centered.getLineRight(0) > centered.size.width)
        val fixed = compose.runOnIdle { native.getValue("fixed-right-bidi") }
        assertEquals("Fixed-width right alignment must retain its real origin", 260, fixed.size.width)
        assertTrue("Fixed-width native line must retain left-side free space", fixed.getLineLeft(0) > 0f)
    }

    @Test fun mixedHebrewLatinLtrPaintMatchesActualCallbacksForContentCenterRightAndWrappedText() {
        mixedBidiPaintWitnesses(rtl = false)
    }

    @Test fun mixedHebrewLatinRtlAtTwoHundredPercentMatchesActualPaintCallbacksAndAlignment() {
        mixedBidiPaintWitnesses(rtl = true)
    }

    @Test fun mixedBidiCenterAndRightClippedTextCannotPassByMovingItsCoordinateOrigin() {
        val text = "\u05e9\u05dc\u05d5\u05dd Beta \u05e9\u05dc\u05d5\u05dd clipped suffix"
        mount {
            Text(AnnotatedString(text), Modifier.width(64.dp).testTag("bidi-center-clip"),
                fontSize = 20.sp, textAlign = TextAlign.Center, softWrap = false, overflow = TextOverflow.Clip)
            Text(AnnotatedString(text), Modifier.width(64.dp).testTag("bidi-right-clip"),
                fontSize = 20.sp, textAlign = TextAlign.Right, softWrap = false, overflow = TextOverflow.Clip)
        }
        for (tag in listOf("bidi-center-clip", "bidi-right-clip")) {
            val layout = rawLayout(tag)
            assertTrue("Native clipped line must genuinely exceed its allocation",
                layout.getLineLeft(0) < 0f || layout.getLineRight(0) > layout.size.width)
            assertFalse(layout.didOverflowHeight)
            assertFalse(layout.isLineEllipsized(0))
            observeMountedNativePaint(compose, compose.onNodeWithTag(tag), layout, text, tag, requireClippingWitness = true)
            rejects(tag, text, "line outside measured text")
        }
    }

    @Test fun mixedBidiRightAndCenterEllipsisCannotBecomeCompleteCopyThroughReconstruction() {
        val text = "\u05e9\u05dc\u05d5\u05dd Beta \u05e9\u05dc\u05d5\u05dd hidden suffix"
        mount {
            Text(AnnotatedString(text), Modifier.width(64.dp).testTag("bidi-right-ellipsis"),
                fontSize = 20.sp, textAlign = TextAlign.Right, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(AnnotatedString(text), Modifier.width(64.dp).testTag("bidi-center-ellipsis"),
                fontSize = 20.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        for (tag in listOf("bidi-right-ellipsis", "bidi-center-ellipsis")) {
            assertTrue("Native bidi witness must actually ellipsize", rawLayout(tag).isLineEllipsized(0))
            rejects(tag, text, "ellipsis line=")
        }
    }

    @Test fun mixedBidiRightAlignedCopyStillRequiresRealTargetViewportContainmentAndScrolling() {
        val text = "\u05e9\u05dc\u05d5\u05dd Beta"
        val native = mutableMapOf<String, TextLayoutResult>()
        mount {
            Row(Modifier.width(120.dp).horizontalScroll(rememberScrollState())) {
                Spacer(Modifier.width(112.dp))
                Box(Modifier.width(96.dp).height(64.dp).clickable {}.testTag("bidi-viewport-target")) {
                    Text(text, Modifier.width(96.dp).testTag("bidi-viewport-text"), fontSize = 16.sp, textAlign = TextAlign.Right)
                }
            }
            Text(text, Modifier.width(96.dp).testTag("bidi-viewport-native"), fontSize = 16.sp,
                textAlign = TextAlign.Right, onTextLayout = { native["viewport"] = it })
        }
        val target = compose.onNodeWithTag("bidi-viewport-target").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val partial = target.fetchSemanticsNode()
        assertTrue("Actual target must be only partially visible", partial.boundsInRoot.width < partial.size.width)
        rejects("bidi-viewport-target", text, "WP502_TEXT_VIEWPORT")
        target.performScrollTo()
        val semantics = rawLayout("bidi-viewport-text", useUnmergedTree = true)
        val painted = compose.runOnIdle { native.getValue("viewport") }
        observeNativeCaretGeometry(semantics, painted, "bidi-viewport-after-scroll")
        assertNativePaintAgreement(semantics, painted, "bidi-viewport-after-scroll")
        retainNativeCaretEvidence(painted, "bidi-viewport-target", "bidi-viewport-native")
        val raster = observeMountedNativePaint(
            compose, compose.onNodeWithTag("bidi-viewport-text", useUnmergedTree = true),
            painted, text, "bidi-viewport-after-scroll",
        )
        nativeCaretEvidence["bidi-viewport-target"] = "$raster|${nativeCaretEvidence.getValue("bidi-viewport-target")}"
        println("WP502_NATIVE_CARET_AGREEMENT|tag=bidi-viewport-after-scroll|allNativeDerivedParagraphLineAndUtf16AssertionsReached=true")
        check("bidi-viewport-target", text)
        check("bidi-viewport-native", text)
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
