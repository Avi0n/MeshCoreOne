// AndroidOnly: WP-502 Strict mounted text/content/viewport oracle for Compose 1.10.6 semantics; not a pixel/font oracle.
package com.meshcoreone.android.uitests

import android.app.Activity
import android.graphics.Rect as AndroidRect
import android.view.WindowInsets
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*

internal fun mountedFullBounds(node: SemanticsNode): Rect {
    val position = node.positionInRoot
    return Rect(position.x, position.y, position.x + node.size.width, position.y + node.size.height)
}

private fun contains(outer: Rect, inner: Rect) =
    inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom

private fun AndroidRect.toComposeRect() = Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

internal fun assertMountedProfile(activity: Activity, frame: ScreenCase) {
    val configuration = activity.resources.configuration
    val decor = activity.window.decorView
    val visible = AndroidRect()
    decor.getWindowVisibleDisplayFrame(visible)
    val diagnostic = "WP502_WINDOW_PROFILE|frame=$frame|configuration=${configuration.screenWidthDp}x${configuration.screenHeightDp}" +
        "|density=${activity.resources.displayMetrics.density}|decor=${decor.width}x${decor.height}" +
        "|metrics=${activity.windowManager.currentWindowMetrics.bounds}|visible=$visible|insets=${decor.rootWindowInsets}"
    println(diagnostic)
    assertEquals(diagnostic, frame.widthDp, configuration.screenWidthDp)
    assertEquals(diagnostic, frame.heightDp, configuration.screenHeightDp)
    assertTrue(diagnostic, decor.width > 0 && decor.height > 0 && visible.width() > 0 && visible.height() > 0)
}

internal fun assertMountedViewport(node: SemanticsNode, context: String) {
    val full = mountedFullBounds(node)
    val visible = node.boundsInRoot
    val ancestors = generateSequence(node) { it.parent }.toList()
    val viewports = listOf(ancestors.last().boundsInRoot) + ancestors.drop(1).filter {
        it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) ||
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
    }.map { it.boundsInRoot }
    val diagnostic = "WP502_TEXT_VIEWPORT|$context|full=$full|visible=$visible|viewports=$viewports"
    assertTrue(diagnostic, full.width > 0f && full.height > 0f)
    assertTrue(diagnostic, contains(visible, full) && viewports.all { contains(it, full) })
    val androidRoot = node.root as? ViewRootForTest
    assertNotNull("$diagnostic|Android owning root required", androidRoot)
    val decor = requireNotNull(androidRoot).view.rootView
    val location = IntArray(2)
    decor.getLocationOnScreen(location)
    val window = Rect(location[0].toFloat(), location[1].toFloat(),
        (location[0] + decor.width).toFloat(), (location[1] + decor.height).toFloat())
    val visibleWindow = AndroidRect()
    decor.getWindowVisibleDisplayFrame(visibleWindow)
    val position = node.positionOnScreen
    val screen = Rect(position.x, position.y, position.x + node.size.width, position.y + node.size.height)
    val windowInsets = decor.rootWindowInsets
    val windowDiagnostic = "$diagnostic|screen=$screen|owningWindow=$window|visibleWindow=$visibleWindow" +
        "|windowBounds=${node.boundsInWindow}|rootInsets=$windowInsets"
    assertNotNull(windowDiagnostic, windowInsets)
    val insets = requireNotNull(windowInsets).getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
    println("WP502_NATIVE_VIEWPORT|$windowDiagnostic|systemCutoutImeInsets=$insets")
    assertTrue(windowDiagnostic, window.width > 0f && window.height > 0f && visibleWindow.width() > 0 && visibleWindow.height() > 0)
    assertTrue(windowDiagnostic, contains(window, screen) && contains(visibleWindow.toComposeRect(), screen))
}

internal fun mountedPaintLayout(semantics: TextLayoutResult): TextLayoutResult {
    val input = semantics.layoutInput
    assertTrue("Measured text cannot recover paint constraints: $semantics",
        semantics.size.width in input.constraints.minWidth..input.constraints.maxWidth &&
            semantics.size.height in input.constraints.minHeight..input.constraints.maxHeight)
    // Simple-string semantics drops minimum constraints. Measured size restores them, not a glyph offset.
    val constraints = input.constraints.copy(minWidth = semantics.size.width, minHeight = semantics.size.height)
    return TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0).measure(
        text = input.text,
        style = input.style,
        overflow = input.overflow,
        softWrap = input.softWrap,
        maxLines = input.maxLines,
        placeholders = input.placeholders,
        constraints = constraints,
        layoutDirection = input.layoutDirection,
        density = input.density,
        fontFamilyResolver = input.fontFamilyResolver,
        skipCache = true,
    )
}

internal fun assertMountedText(
    compose: ComposeTestRule,
    target: SemanticsNodeInteraction,
    expected: String,
    fontScale: Float,
    direction: LayoutDirection,
    context: String,
): TextLayoutResult {
    val targetNode = target.assertIsDisplayed().fetchSemanticsNode()
    compose.runOnIdle { assertMountedViewport(targetNode, "$context target=$expected") }
    val text = compose.onNode(
        hasText(expected) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
            SemanticsMatcher("text belongs to target ${targetNode.id}") { node ->
                generateSequence(node) { it.parent }.any { it.id == targetNode.id }
            },
        useUnmergedTree = true,
    )
    val textNode = text.assertIsDisplayed().fetchSemanticsNode()
    compose.runOnIdle { assertMountedViewport(textNode, "$context text=$expected") }
    val results = mutableListOf<TextLayoutResult>()
    text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
    val layout = results.single()
    val actual = layout.layoutInput.text.text
    // Retain the reconstructed coordinates for diagnostics; they are not necessarily paint coordinates.
    val lines = (0 until layout.lineCount).map { line ->
        Rect(layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line))
    }
    val size = Rect(0f, 0f, layout.size.width.toFloat(), layout.size.height.toFloat())
    val diagnostic = "WP502_TEXT_CONTENT|$context|expected=$expected|actual=$actual" +
        "|node=${mountedFullBounds(textNode)}|visible=${textNode.boundsInRoot}|size=${layout.size}" +
        "|constraints=${layout.layoutInput.constraints}|style=${layout.layoutInput.style}" +
        "|paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}" +
        "|workspaceOverflowWidth=${layout.didOverflowWidth}|overflowHeight=${layout.didOverflowHeight}" +
        "|lines=$lines"
    assertEquals(diagnostic, expected, actual)
    assertEquals(diagnostic, fontScale, layout.layoutInput.density.fontScale, 0f)
    assertEquals(diagnostic, direction, layout.layoutInput.layoutDirection)
    assertTrue(diagnostic, layout.lineCount > 0 && size.width > 0f && size.height > 0f)
    assertTrue("$diagnostic|measured text exceeds node", layout.size.width <= textNode.size.width && layout.size.height <= textNode.size.height)
    for (line in 0 until layout.lineCount) {
        assertFalse("$diagnostic|ellipsis line=$line", layout.isLineEllipsized(line))
    }
    assertFalse("$diagnostic|vertical", layout.didOverflowHeight)
    val paint = compose.runOnIdle { mountedPaintLayout(layout) }
    val paintLines = (0 until paint.lineCount).map { line ->
        Rect(paint.getLineLeft(line), paint.getLineTop(line), paint.getLineRight(line), paint.getLineBottom(line))
    }
    val paintDiagnostic = "$diagnostic|paintInput=${paint.layoutInput}|paintSize=${paint.size}" +
        "|paintParagraph=${paint.multiParagraph.width}x${paint.multiParagraph.height}|paintLines=$paintLines"
    assertEquals("$paintDiagnostic|paint changed measured allocation", layout.size, paint.size)
    assertEquals("$paintDiagnostic|paint changed full text", layout.layoutInput.text, paint.layoutInput.text)
    assertEquals("$paintDiagnostic|paint changed style", layout.layoutInput.style, paint.layoutInput.style)
    assertEquals("$paintDiagnostic|paint changed line count", layout.lineCount, paint.lineCount)
    assertFalse("$paintDiagnostic|vertical", paint.didOverflowHeight)
    for (line in 0 until paint.lineCount) {
        assertEquals("$paintDiagnostic|paint changed line start=$line", layout.getLineStart(line), paint.getLineStart(line))
        assertEquals("$paintDiagnostic|paint changed line end=$line", layout.getLineEnd(line), paint.getLineEnd(line))
        assertFalse("$paintDiagnostic|ellipsis line=$line", paint.isLineEllipsized(line))
    }
    var covered = 0
    for (line in 0 until paint.lineCount) {
        assertEquals("$paintDiagnostic|missing text before line=$line", covered, paint.getLineStart(line))
        covered = paint.getLineEnd(line)
        assertTrue("$paintDiagnostic|line outside measured text line=$line", contains(size, paintLines[line]))
    }
    assertEquals("$paintDiagnostic|missing UTF16 suffix", actual.length, covered)
    val characters = actual.indices.associateWith { paint.getBoundingBox(it) }
    val characterDiagnostic = "$paintDiagnostic|paintCharacters=$characters"
    if (layout.didOverflowWidth) {
        println("$characterDiagnostic|semanticsCharacters=${actual.indices.associateWith { layout.getBoundingBox(it) }}")
    }
    for ((offset, bounds) in characters) {
        assertTrue("$characterDiagnostic|character outside measured text UTF16=$offset", contains(size, bounds))
    }
    return layout
}
