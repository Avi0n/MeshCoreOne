// AndroidOnly: WP-502 Diagnostic native paragraph raster versus mounted owning-view draw; never a replacement text/geometry oracle.
package com.meshcoreone.android.uitests

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.*
import kotlin.math.ceil

internal fun observeMountedNativePaint(
    compose: ComposeTestRule,
    text: SemanticsNodeInteraction,
    native: TextLayoutResult,
    expected: String,
    context: String,
    requireClippingWitness: Boolean = false,
): String {
    val node = text.assertIsDisplayed().fetchSemanticsNode()
    return compose.runOnIdle {
        assertMountedViewport(node, "native paint observation $context")
        assertEquals(context, expected, native.layoutInput.text.text)
        assertEquals("$context|actual measured text width", native.size.width, node.size.width)
        assertEquals("$context|actual measured text height", native.size.height, node.size.height)
        val style = native.layoutInput.style
        assertTrue("$context|observed explicit native color required", style.color.isSpecified)
        assertNull("$context|controlled fixtures require the observed solid-color paint path", style.brush)
        assertTrue("$context|diagnostic paragraph must be finite and bounded",
            native.multiParagraph.width.isFinite() && native.multiParagraph.height.isFinite() &&
                native.multiParagraph.width in 1f..4096f && native.multiParagraph.height in 1f..4096f)
        val margin = native.size.height
        assertTrue("$context|nonempty native allocation required", native.size.width > 0 && margin > 0)
        val width = maxOf(native.size.width, ceil(native.multiParagraph.width).toInt()) + 2 * margin
        val height = maxOf(native.size.height, ceil(native.multiParagraph.height).toInt()) + 2 * margin
        val bitmaps = mutableListOf<Bitmap>()
        fun allocate(width: Int, height: Int): Bitmap =
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmaps.add(it) }
        try {
            val raw = allocate(width, height)
            val clipped = allocate(width, height)
            fun paint(bitmap: Bitmap, clip: Boolean) {
                val canvas = Canvas(bitmap.asImageBitmap())
                canvas.save()
                try {
                    canvas.translate(margin.toFloat(), margin.toFloat())
                    if (clip) canvas.clipRect(0f, 0f, native.size.width.toFloat(), native.size.height.toFloat())
                    native.multiParagraph.paint(
                        canvas = canvas, color = style.color, shadow = style.shadow ?: Shadow.None,
                        decoration = style.textDecoration ?: TextDecoration.None, drawStyle = style.drawStyle ?: Fill,
                    )
                } finally {
                    canvas.restore()
                }
            }
            paint(raw, clip = false)
            paint(clipped, clip = true)
            var ink = 0
            var outside = 0
            var retained = 0
            var changed = 0
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            for (y in 0 until height) for (x in 0 until width) {
                val pixel = raw.getPixel(x, y)
                val clippedPixel = clipped.getPixel(x, y)
                val inAllocation = x in margin until margin + native.size.width &&
                    y in margin until margin + native.size.height
                if (pixel ushr 24 != 0) {
                    ink++
                    if (!inAllocation) outside++
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
                if (clippedPixel ushr 24 != 0) {
                    assertTrue("$context|native clip leaked outside actual allocation", inAllocation)
                    retained++
                }
                if (inAllocation) assertEquals("$context|clip changed an interior native pixel x=$x y=$y", pixel, clippedPixel)
                if (pixel != clippedPixel) changed++
            }
            val inkBounds = "${left - margin},${top - margin}..${right - margin},${bottom - margin}"
            val rawDiagnostic = "WP502_NATIVE_RASTER|$context|source=actual-native-paragraph-offscreen" +
                "|allocation=${native.size}|probe=${width}x$height|probeOrigin=$margin,$margin" +
                "|ink=$ink|outside=$outside|retained=$retained|changed=$changed" +
                "|inkPixelsRelativeToOriginalOrigin=$inkBounds"
            println(rawDiagnostic)
            assertTrue("$rawDiagnostic|blank or non-text native raster", ink > 1 && retained > 1 && left < right && top < bottom)
            assertTrue("$rawDiagnostic|probe edge reached; outside ink observation incomplete",
                left > 0 && top > 0 && right < width - 1 && bottom < height - 1)
            if (requireClippingWitness) {
                assertTrue("$rawDiagnostic|genuine native ink clipping must be observed", outside > 0 && changed > 0 && retained < ink)
            }
            val root = node.root as? ViewRootForTest
            assertNotNull("$context|real owning Android view required", root)
            val decor = requireNotNull(root).view.rootView
            val location = IntArray(2)
            decor.getLocationOnScreen(location)
            val x = node.positionOnScreen.x - location[0]
            val y = node.positionOnScreen.y - location[1]
            assertEquals("$context|subpixel mounted origin needs a separate paint contract", x.toInt().toFloat(), x, 0f)
            assertEquals("$context|subpixel mounted origin needs a separate paint contract", y.toInt().toFloat(), y, 0f)
            val originX = x.toInt()
            val originY = y.toInt()
            assertTrue("$context|mounted allocation outside owning view",
                originX >= 0 && originY >= 0 && originX + native.size.width <= decor.width &&
                    originY + native.size.height <= decor.height)
            val mounted = allocate(decor.width, decor.height)
            decor.draw(AndroidCanvas(mounted))
            val clearPixel = (0 until native.size.height).asSequence().flatMap { row ->
                (0 until native.size.width).asSequence().map { column -> column to row }
            }.firstOrNull { (column, row) -> clipped.getPixel(column + margin, row + margin) ushr 24 == 0 }
            assertNotNull("$context|independent mounted background sample required", clearPixel)
            val (backgroundX, backgroundY) = requireNotNull(clearPixel)
            val background = mounted.getPixel(originX + backgroundX, originY + backgroundY)
            val rawComposite = allocate(width, height)
            rawComposite.eraseColor(background)
            paint(rawComposite, clip = false)
            val clippedComposite = allocate(width, height)
            clippedComposite.eraseColor(background)
            paint(clippedComposite, clip = true)
            var rawMismatches = 0
            var clippedMismatches = 0
            var mountedTextPixels = 0
            for (row in 0 until native.size.height) for (column in 0 until native.size.width) {
                val actual = mounted.getPixel(originX + column, originY + row)
                if (actual != rawComposite.getPixel(column + margin, row + margin)) rawMismatches++
                if (actual != clippedComposite.getPixel(column + margin, row + margin)) clippedMismatches++
                if (actual != background) mountedTextPixels++
            }
            val sampleLeft = maxOf(0, originX - margin)
            val sampleRight = minOf(decor.width, originX + native.size.width + margin)
            var rawHaloMismatches = 0
            var clippedHaloMismatches = 0
            for (row in 0 until native.size.height) for (column in sampleLeft until sampleRight) {
                if (column in originX until originX + native.size.width) continue
                val actual = mounted.getPixel(column, originY + row)
                val probeX = column - originX + margin
                if (actual != rawComposite.getPixel(probeX, row + margin)) rawHaloMismatches++
                if (actual != clippedComposite.getPixel(probeX, row + margin)) clippedHaloMismatches++
            }
            val matchesRaw = rawMismatches == 0 && rawHaloMismatches == 0
            val matchesClipped = clippedMismatches == 0 && clippedHaloMismatches == 0
            val match = when {
                matchesRaw && matchesClipped -> "both"
                matchesRaw -> "raw-only"
                matchesClipped -> "clipped-only"
                else -> "neither"
            }
            val observation = "mountedPaintMatch=$match|nativeRasterInk=$ink,outside=$outside,clipChanged=$changed,retained=$retained" +
                "|nativeRasterExtents=$inkBounds|mountedViewDrawTextPixels=$mountedTextPixels" +
                "|allocationMismatchesRaw=$rawMismatches,clipped=$clippedMismatches" +
                "|mountedHaloX=$sampleLeft..$sampleRight,haloMismatchesRaw=$rawHaloMismatches,clipped=$clippedHaloMismatches" +
                "|mountedOrigin=$originX,$originY|mountedAllocation=${native.size}"
            println("WP502_MOUNTED_RASTER|$context|source=actual-owning-view-software-draw|$observation")
            assertTrue("$context|$observation|blank mounted capture cannot witness real text", mountedTextPixels > 1)
            assertNotEquals("$context|$observation|mounted capture/paint contract matches neither observation", "neither", match)
            if (requireClippingWitness) {
                assertEquals("$context|$observation|genuine negative needs distinguishable actual mounted clipping", "clipped-only", match)
            }
            observation
        } finally {
            bitmaps.asReversed().forEach { it.recycle() }
        }
    }
}
