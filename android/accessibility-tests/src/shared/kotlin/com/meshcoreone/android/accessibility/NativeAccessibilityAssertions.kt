// AndroidOnly: WP-503 Native window, actual system font configuration and measured Compose semantics/pixels.
package com.meshcoreone.android.accessibility

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import com.meshcoreone.android.core.designsystem.ThemeColor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

abstract class NativeAccessibilityAssertions {
    protected abstract val compose: ComposeTestRule
    protected abstract val activity: ComponentActivity
    protected abstract fun content(body: @Composable () -> Unit)

    @Composable
    protected open fun AuditSurface(body: @Composable () -> Unit) {
        Surface(Modifier.fillMaxSize().testTag("audit-root"), color = MaterialTheme.colorScheme.background) {
            body()
        }
    }

    protected fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals("Expected one real native text layout", 1, layouts.size)
        return layouts.single()
    }

    protected fun font200Layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val layout = textLayout(node)
        assertEquals("Do not substitute a preview Density for system font", 2f, layout.layoutInput.density.fontScale, 0f)
        assertEquals("The native layout must actually be RTL", LayoutDirection.Rtl, layout.layoutInput.layoutDirection)
        println("WP503_FONT|text=${layout.layoutInput.text}|size=${layout.size}|constraints=${layout.layoutInput.constraints}" +
            "|paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}" +
            "|widthOverflow=${layout.didOverflowWidth}|heightOverflow=${layout.didOverflowHeight}|lines=${layout.lineCount}")
        for (line in 0 until layout.lineCount) {
            println("WP503_FONT_LINE|text=${layout.layoutInput.text}|line=$line" +
                "|left=${layout.getLineLeft(line)}|right=${layout.getLineRight(line)}" +
                "|top=${layout.getLineTop(line)}|bottom=${layout.getLineBottom(line)}")
        }
        return layout
    }

    protected fun assertFont200Unclipped(node: SemanticsNodeInteraction) {
        assertUnclippedContent(font200Layout(node))
    }

    protected fun scrollWholeAllocationIntoView(node: SemanticsNodeInteraction) {
        val measured = node.fetchSemanticsNode()
        val root = compose.onNodeWithTag("audit-root").fetchSemanticsNode().boundsInRoot
        val bounds = measured.boundsInRoot
        if (bounds.left >= root.left && bounds.top >= root.top && bounds.right <= root.right && bounds.bottom <= root.bottom &&
            bounds.width == measured.size.width.toFloat() && bounds.height == measured.size.height.toFloat()) return
        val scroll = requireNotNull(generateSequence(measured.parent) { it.parent }.firstOrNull {
            it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }) { "A clipped allocation must have an actual non-gesture scroll action" }
        val top = measured.positionInRoot.y
        val bottom = top + measured.size.height
        val viewportTop = maxOf(root.top, scroll.boundsInRoot.top)
        val viewportBottom = minOf(root.bottom, scroll.boundsInRoot.bottom)
        val delta = if (bottom > viewportBottom) bottom - viewportBottom else if (top < viewportTop) top - viewportTop else 0f
        if (delta != 0f) {
            compose.onNode(SemanticsMatcher("Actual whole-allocation scroll viewport") { it.id == scroll.id }, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, delta)) }
        }
        println("WP503_WHOLE_ALLOCATION_SCROLL|delta=$delta|before=$bounds|after=${node.fetchSemanticsNode().boundsInRoot}|viewport=$root")
    }

    protected fun assertScrollableFont200Unclipped(node: SemanticsNodeInteraction) {
        node.performScrollTo()
        val layout = font200Layout(node)
        assertUnclippedContent(layout)
        val scroll = generateSequence(node.fetchSemanticsNode().parent) { it.parent }.first {
            it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }
        val scrollAction = compose.onNode(SemanticsMatcher("Actual text scroll viewport") { it.id == scroll.id },
            useUnmergedTree = true)
        for (line in 0 until layout.lineCount) {
            val viewport = scrollAction.fetchSemanticsNode().boundsInRoot
            val position = node.fetchSemanticsNode().positionInRoot
            val top = position.y + layout.getLineTop(line)
            val bottom = position.y + layout.getLineBottom(line)
            val delta = if (bottom > viewport.bottom) bottom - viewport.bottom
                else if (top < viewport.top) top - viewport.top else 0f
            if (delta != 0f) scrollAction.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, delta)) }
            val after = node.fetchSemanticsNode().positionInRoot
            println("WP503_SCROLL_LINE|text=${layout.layoutInput.text}|line=$line|position=$position->$after|viewport=$viewport")
            assertTrue("Every actual text line must be reachable by non-gesture native scroll",
                after.x + layout.getLineLeft(line) >= viewport.left && after.x + layout.getLineRight(line) <= viewport.right &&
                    after.y + layout.getLineTop(line) >= viewport.top && after.y + layout.getLineBottom(line) <= viewport.bottom)
        }
    }

    protected fun assertUnclippedContent(layout: TextLayoutResult) {
        assertTrue("Nonempty audit text must have actual measured lines", layout.layoutInput.text.isNotEmpty() && layout.lineCount > 0)
        assertFalse("Native text was truncated: ${layout.layoutInput.text}", layout.multiParagraph.didExceedMaxLines)
        // The paragraph can retain its parent's maximum width after Text shrink-wraps. Check the actual lines, not that allocation.
        for (line in 0 until layout.lineCount) {
            assertFalse("Native line $line was ellipsized", layout.isLineEllipsized(line))
            assertTrue("Native line $line clips horizontally: ${layout.layoutInput.text}",
                layout.getLineLeft(line) >= 0f && layout.getLineRight(line) <= layout.size.width)
            assertTrue("Native line $line clips vertically: ${layout.layoutInput.text}",
                layout.getLineTop(line) >= 0f && layout.getLineBottom(line) <= layout.size.height)
        }
    }

    protected fun assertVisibleClickTargetsAtLeast48Dp(): Int {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .fetchSemanticsNodes()
        val root = compose.onNodeWithTag("audit-root").fetchSemanticsNode().boundsInRoot
        val density = activity.resources.displayMetrics.density
        var measured = 0
        for (node in nodes) {
            if (node.config.contains(SemanticsProperties.Disabled)) continue
            if (!node.boundsInRoot.overlaps(root)) continue
            val origin = node.positionInRoot
            val size = node.size
            // A scrollable viewport can show part of the next card. Audit it after scrolling it fully into view.
            if (origin.x < root.left || origin.y < root.top ||
                origin.x + size.width > root.right || origin.y + size.height > root.bottom) {
                println("WP503_PARTIAL_TARGET|position=$origin|size=$size")
                continue
            }
            val target = node.touchBoundsInRoot
            val width = target.width / density
            val height = target.height / density
            println("WP503_TARGET|width=$width|height=$height|role=${node.config.getOrNull(SemanticsProperties.Role)}")
            assertTrue("Enabled target is only ${width}x${height}dp", width >= 48f && height >= 48f)
            measured++
        }
        assertTrue("Zero measured controls is not accessibility evidence", measured > 0)
        return measured
    }

    protected fun assertNoWholeTimelineLiveRegion(vararg messageBodies: String) {
        require(messageBodies.toSet().size >= 2) { "Audit at least two distinct actual message rows" }
        val config = compose.onNodeWithTag("audit-root", useUnmergedTree = true).fetchSemanticsNode().config
        assertFalse("Do not announce the whole changing screen", config.contains(SemanticsProperties.LiveRegion))
        val rows = messageBodies.flatMap { body ->
            compose.onAllNodes(hasText(body), useUnmergedTree = true).fetchSemanticsNodes().also {
                assertTrue("Actual message row not mounted: $body", it.isNotEmpty())
            }
        }
        fun containsBody(node: SemanticsNode, body: String): Boolean =
            narrative(node).contains(body) || node.children.any { containsBody(it, body) }
        val regions = rows.flatMap { node -> generateSequence(node) { it.parent }.toList() }
            .distinctBy { it.id }.filter { it.config.contains(SemanticsProperties.LiveRegion) }
        for (region in regions) {
            val messages = messageBodies.count { containsBody(region, it) }
            assertTrue("Actual live region ${region.id} absorbs $messages message rows", messages < 2)
        }
    }

    protected fun narrative(node: SemanticsNode): String = buildString {
        // A content description replaces text; concatenating both invents a duplicate announcement.
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
        if (!description.isNullOrEmpty()) append(description.joinToString("\n"))
        else append(node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty())
        node.config.getOrNull(SemanticsProperties.StateDescription)?.let { append(it) }
    }

    protected fun messageNarrative(body: String, required: String) = SemanticsMatcher(
        "One message exposes body and '$required'",
    ) { node -> narrative(node).let { it.contains(body) && it.contains(required) } }

    protected fun paintedBackground(node: SemanticsNodeInteraction, expected: Color): ThemeColor {
        compose.waitForIdle()
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val bitmap = nativeWindowBitmap()
        try {
            val left = floor(bounds.left).toInt()
            val top = floor(bounds.top).toInt()
            val right = ceil(bounds.right).toInt()
            val bottom = ceil(bounds.bottom).toInt()
            assertTrue("Text must be wholly inside the native window",
                left >= 0 && top >= 0 && right <= bitmap.width && bottom <= bitmap.height && right > left && bottom > top)
            val histogram = mutableMapOf<Int, Int>()
            for (y in top until bottom) for (x in left until right) {
                val pixel = bitmap.getPixel(x, y)
                histogram[pixel] = (histogram[pixel] ?: 0) + 1
            }
            val dominant = requireNotNull(histogram.maxByOrNull { it.value })
            assertTrue("No unambiguous painted bubble background", dominant.value > (right - left) * (bottom - top) / 3)
            for (shift in listOf(0, 8, 16, 24)) {
                assertTrue("Measured pixels do not match this feature's emitted surface",
                    abs(((dominant.key ushr shift) and 255) - ((expected.toArgb() ushr shift) and 255)) <= 1)
            }
            return ThemeColor.hex(dominant.key.toLong())
        } finally {
            bitmap.recycle()
        }
    }

    protected fun assertFontPaintContainsInk(node: SemanticsNodeInteraction) {
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val bitmap = nativeWindowBitmap()
        try {
            val left = floor(bounds.left).toInt()
            val top = floor(bounds.top).toInt()
            val right = ceil(bounds.right).toInt()
            val bottom = ceil(bounds.bottom).toInt()
            assertTrue(left >= 0 && top >= 0 && right <= bitmap.width && bottom <= bitmap.height && right > left && bottom > top)
            val colors = mutableMapOf<Int, Int>()
            for (y in top until bottom) for (x in left until right) {
                val pixel = bitmap.getPixel(x, y)
                colors[pixel] = (colors[pixel] ?: 0) + 1
            }
            val largest = requireNotNull(colors.maxByOrNull { it.value })
            val ink = (right - left) * (bottom - top) - largest.value
            println("WP503_FONT_PAINT|label=${narrative(node.fetchSemanticsNode())}|bounds=$bounds" +
                "|colors=${colors.size}|nonBackgroundPixels=$ink")
            assertTrue("No actual painted text within its native bounds", ink > 0)
        } finally {
            bitmap.recycle()
        }
    }

    protected fun assertFont200PaintedUnclipped(node: SemanticsNodeInteraction, view: View = activity.window.decorView) {
        val layout = font200Layout(node)
        assertTrue(layout.layoutInput.text.isNotEmpty() && layout.lineCount > 0)
        assertFalse("Native text was truncated", layout.multiParagraph.didExceedMaxLines)
        for (line in 0 until layout.lineCount) assertFalse("Native text was ellipsized", layout.isLineEllipsized(line))
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val window = nativeWindowBitmap(view)
        try {
            val left = floor(bounds.left).toInt()
            val top = floor(bounds.top).toInt()
            val width = ceil(bounds.right).toInt() - left
            val height = ceil(bounds.bottom).toInt() - top
            assertTrue("Text must be inside its actual native window",
                left >= 0 && top >= 0 && width > 0 && height > 0 &&
                    left + width <= window.width && top + height <= window.height)
            val actual = Bitmap.createBitmap(window, left, top, width, height)
            try {
                val histogram = mutableMapOf<Int, Int>()
                for (y in 0 until actual.height) for (x in 0 until actual.width) {
                    val color = actual.getPixel(x, y)
                    histogram[color] = (histogram[color] ?: 0) + 1
                }
                val background = requireNotNull(histogram.maxByOrNull { it.value }).key
                assertTrue("Text needs an unambiguous background", histogram.getValue(background) > width * height / 2)
                val reference = Bitmap.createBitmap(ceil(layout.multiParagraph.width).toInt(),
                    ceil(layout.multiParagraph.height).toInt(), Bitmap.Config.ARGB_8888)
                try {
                    reference.eraseColor(background)
                    val canvas = Canvas(reference)
                    layout.multiParagraph.paint(androidx.compose.ui.graphics.Canvas(canvas),
                        color = if (layout.layoutInput.style.color == Color.Unspecified) Color.Black else layout.layoutInput.style.color)
                    fun ink(bitmap: Bitmap): IntArray {
                        var minY = bitmap.height
                        var maxY = -1
                        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                            if (bitmap.getPixel(x, y) != background) {
                                minY = minOf(minY, y)
                                maxY = maxOf(maxY, y)
                            }
                        }
                        assertTrue("Zero painted glyphs is not evidence", maxY >= minY)
                        return IntArray(maxY - minY + 1) { row ->
                            (0 until bitmap.width).count { x -> bitmap.getPixel(x, minY + row) != background }
                        }
                    }
                    val observed = ink(actual)
                    val expected = ink(reference)
                    println("WP503_FONT_FULL_INK|text=${layout.layoutInput.text}|actual=${observed.contentToString()}" +
                        "|reference=${expected.contentToString()}|bounds=$bounds")
                    // Native alignment can shift rasterized glyphs without losing ink. Require every row's full coverage, with no tolerance.
                    assertTrue("Every raster row must retain the complete native paragraph's ink",
                        expected.contentEquals(observed))
                } finally {
                    reference.recycle()
                }
            } finally {
                actual.recycle()
            }
        } finally {
            window.recycle()
        }
    }

    private fun nativeWindowBitmap(view: View = activity.window.decorView): Bitmap = compose.runOnUiThread {
        assertTrue("Native window has no pixels", view.width > 0 && view.height > 0)
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
}
