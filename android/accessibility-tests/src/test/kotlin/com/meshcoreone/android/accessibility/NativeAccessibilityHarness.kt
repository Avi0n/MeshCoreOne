// AndroidOnly: WP-503 Native window, actual system font configuration and measured Compose semantics/pixels.
package com.meshcoreone.android.accessibility

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.designsystem.ThemeColor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

abstract class NativeAccessibilityHarness {
    @get:Rule val compose = createEmptyComposeRule()
    protected lateinit var host: ActivityController<ComponentActivity>

    @Suppress("DEPRECATION")
    @Before fun attachNativeWindowAtSystemFont200() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        application.applicationInfo.flags = application.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val configuration = Configuration(application.resources.configuration).apply {
            fontScale = 2f
            setLayoutDirection(java.util.Locale.forLanguageTag("ar"))
        }
        application.resources.updateConfiguration(configuration, application.resources.displayMetrics)
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().applicationInfo.flags = host.get().applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        host.get().resources.updateConfiguration(configuration, host.get().resources.displayMetrics)
        host.get().window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        assertEquals(2f, host.get().resources.configuration.fontScale, 0f)
    }

    @After fun closeNativeWindow() {
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
    }

    protected fun content(body: @Composable () -> Unit) {
        host.get().setContent(content = body)
    }

    @Composable
    protected fun AuditSurface(body: @Composable () -> Unit) {
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
        return layout
    }

    protected fun assertFont200Unclipped(node: SemanticsNodeInteraction) {
        val layout = font200Layout(node)
        assertFalse("Native text overflows: ${layout.layoutInput.text}", layout.hasVisualOverflow)
    }

    protected fun assertVisibleClickTargetsAtLeast48Dp(): Int {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .fetchSemanticsNodes()
        val root = compose.onNodeWithTag("audit-root").fetchSemanticsNode().boundsInRoot
        val density = host.get().resources.displayMetrics.density
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
        val bitmap = compose.runOnUiThread {
            val view = host.get().window.decorView
            assertTrue("Native window has no pixels", view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
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
}
