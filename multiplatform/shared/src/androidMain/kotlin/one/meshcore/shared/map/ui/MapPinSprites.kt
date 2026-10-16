package one.meshcore.shared.map.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import one.meshcore.shared.map.MapPinPalette
import one.meshcore.shared.map.MapPinShape
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.map.mapPinHopBadge
import one.meshcore.shared.map.mapPinShape
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

/**
 * Rasterizes iOS `PinSpriteRenderer` teardrops at [density] so a 36 dp circle is 36 dp on device,
 * not 48 screen pixels. Node kinds share the teardrop and differ by [MapPinPalette] fill plus a
 * Material Symbol; hop pins add a 20 dp blue badge with a white ring and bold numerals.
 */
internal class MapPinSprites(val density: Float) {
    fun shapeBitmap(shape: MapPinShape): Bitmap = teardrop.standardPin(
        StandardPin(
            fill = shape.fill.toAndroidColor(),
            glyph = shape.glyph,
        ),
    )

    fun letterPinBitmap(fillColor: Int, letter: String): Bitmap = teardrop.standardPin(
        StandardPin(fill = fillColor, letter = letter),
    )

    fun ringedRepeaterBitmap(ringColor: Int): Bitmap = teardrop.standardPin(
        StandardPin(
            fill = MapPinPalette.REPEATER.toAndroidColor(),
            glyph = PinGlyph.CELL_TOWER,
            ringColor = ringColor,
        ),
    )

    fun hopPinBitmap(badge: Int?, ringed: Boolean): Bitmap = teardrop.standardPin(
        StandardPin(
            fill = MapPinPalette.REPEATER.toAndroidColor(),
            glyph = PinGlyph.CELL_TOWER,
            badge = badge,
            ringColor = if (ringed) AndroidColor.WHITE else null,
        ),
    )

    fun heroPinBitmap(): Bitmap = teardrop.standardPin(
        StandardPin(
            fill = opaqueColor(LOCATION_HERO_COLOR),
            centerDot = true,
        ),
    )

    fun obstructionBitmap(): Bitmap {
        val side = canvasKit.pxI(OBSTRUCTION_DP)
        val bitmap = canvasKit.newBitmap(side, side)
        val canvas = Canvas(bitmap)
        val center = side / 2f
        val casing = canvasKit.strokePaint(AndroidColor.WHITE, canvasKit.px(OBSTRUCTION_CASING_DP))
        val stroke = canvasKit.strokePaint(MapLibreChrome.ERROR.toAndroidColor(), canvasKit.px(OBSTRUCTION_STROKE_DP))
        canvasKit.drawObstructionArms(canvas, center, canvasKit.px(OBSTRUCTION_ARM_DP), casing)
        canvasKit.drawObstructionArms(canvas, center, canvasKit.px(OBSTRUCTION_ARM_DP), stroke)
        return bitmap
    }

    fun crosshairBitmap(): Bitmap {
        val capInset = canvasKit.px(CROSSHAIR_CASING_DP) / 2f
        val size = canvasKit.px(CROSSHAIR_SPAN_DP) + capInset * 2f
        val topPadding = canvasKit.px(CROSSHAIR_BADGE_DP) + canvasKit.px(CROSSHAIR_BADGE_GAP_DP)
        val width = size.roundToInt()
        val height = (
            topPadding + size + canvasKit.px(CROSSHAIR_BADGE_GAP_DP) + canvasKit.px(CROSSHAIR_BADGE_DP)
            ).roundToInt()
        val bitmap = canvasKit.newBitmap(width, height)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = topPadding + size / 2f
        val fill = MapPinPalette.PURPLE.toAndroidColor()
        canvasKit.drawCrosshairArms(
            canvas,
            cx,
            cy,
            canvasKit.strokePaint(AndroidColor.WHITE, canvasKit.px(CROSSHAIR_CASING_DP)),
        )
        canvasKit.drawCrosshairArms(canvas, cx, cy, canvasKit.strokePaint(fill, canvasKit.px(CROSSHAIR_STROKE_DP)))
        val badgeTop = topPadding + size + canvasKit.px(CROSSHAIR_BADGE_GAP_DP)
        val badgeR = canvasKit.px(CROSSHAIR_BADGE_DP) / 2f
        val badgeRect = RectF(cx - badgeR, badgeTop, cx + badgeR, badgeTop + canvasKit.px(CROSSHAIR_BADGE_DP))
        canvas.drawRoundRect(
            badgeRect,
            canvasKit.px(CROSSHAIR_BADGE_CORNER_DP),
            canvasKit.px(CROSSHAIR_BADGE_CORNER_DP),
            canvasKit.fillPaint(fill),
        )
        canvasKit.drawCenteredText(
            CenteredLabel(
                canvas = canvas,
                text = LOS_RADIO_LETTER,
                cx = cx,
                cy = badgeRect.centerY(),
                textSize = canvasKit.px(CROSSHAIR_BADGE_TEXT_DP),
                color = AndroidColor.WHITE,
            ),
        )
        return bitmap
    }

    fun locationDotBitmap(fillRgb: Int, isDark: Boolean): Bitmap {
        val side = canvasKit.pxI(LOCATION_DOT_DP)
        val bitmap = canvasKit.newBitmap(side, side)
        val canvas = Canvas(bitmap)
        val center = side / 2f
        val radius = canvasKit.px(LOCATION_DOT_DIAMETER_DP) / 2f
        val casingColor = if (isDark) {
            AndroidColor.argb(LOCATION_DOT_DARK_CASING_ALPHA, COLOR_CHANNEL_MAX, COLOR_CHANNEL_MAX, COLOR_CHANNEL_MAX)
        } else {
            AndroidColor.WHITE
        }
        canvas.drawCircle(center, center, radius, canvasKit.fillPaint(opaqueColor(fillRgb)))
        canvas.drawCircle(
            center,
            center,
            radius,
            canvasKit.strokePaint(casingColor, canvasKit.px(LOCATION_DOT_STROKE_DP)),
        )
        return bitmap
    }

    fun transparentBitmap(): Bitmap = canvasKit.newBitmap(1, 1)

    fun pillBitmap(): Bitmap {
        val side = canvasKit.pxI(PILL_SIZE_DP)
        val bitmap = canvasKit.newBitmap(side, side)
        val canvas = Canvas(bitmap)
        val margin = canvasKit.px(PILL_MARGIN_DP)
        val rect = RectF(margin, margin, side - margin, side - margin)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(PILL_FILL_ALPHA, COLOR_CHANNEL_MAX, COLOR_CHANNEL_MAX, COLOR_CHANNEL_MAX)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, canvasKit.px(PILL_CORNER_DP), canvasKit.px(PILL_CORNER_DP), fill)
        return bitmap
    }

    private data class StandardPin(
        val fill: Int,
        val glyph: PinGlyph? = null,
        val letter: String? = null,
        val badge: Int? = null,
        val ringColor: Int? = null,
        val centerDot: Boolean = false,
    )

    private val canvasKit = CanvasKit()
    private val teardrop = TeardropPin()

    private data class CenteredLabel(
        val canvas: Canvas,
        val text: String,
        val cx: Float,
        val cy: Float,
        val textSize: Float,
        val color: Int,
    )

    private inner class CanvasKit {
        fun drawObstructionArms(canvas: Canvas, center: Float, arm: Float, paint: Paint) {
            canvas.drawLine(center - arm, center - arm, center + arm, center + arm, paint)
            canvas.drawLine(center + arm, center - arm, center - arm, center + arm, paint)
        }

        fun drawCrosshairArms(canvas: Canvas, cx: Float, cy: Float, paint: Paint) {
            val outer = px(CROSSHAIR_OUTER_DP)
            val gap = px(CROSSHAIR_GAP_DP)
            canvas.drawLine(cx, cy - outer, cx, cy - gap, paint)
            canvas.drawLine(cx, cy + gap, cx, cy + outer, paint)
            canvas.drawLine(cx - outer, cy, cx - gap, cy, paint)
            canvas.drawLine(cx + gap, cy, cx + outer, cy, paint)
        }

        fun drawCenteredText(label: CenteredLabel) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = label.color
                this.textSize = label.textSize
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            val baseline = label.cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
            label.canvas.drawText(label.text, label.cx, baseline, paint)
        }

        fun newBitmap(width: Int, height: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap.density = (density * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
            return bitmap
        }

        fun px(dp: Float): Float = dp * density

        fun pxI(dp: Float): Int = (dp * density).roundToInt().coerceAtLeast(1)

        fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

        fun strokePaint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

    private inner class TeardropPin {
        fun standardPin(pin: StandardPin): Bitmap {
            val circle = canvasKit.px(CIRCLE_DP)
            val triangle = canvasKit.px(TRIANGLE_DP)
            val overlap = canvasKit.px(TRIANGLE_OVERLAP_DP)
            val ringPadding = if (pin.ringColor != null) canvasKit.px(RING_PADDING_DP) else 0f
            val ringSize = if (pin.ringColor != null) canvasKit.px(RING_SIZE_DP) else 0f
            val badgeRoom = if (pin.badge != null) canvasKit.px(BADGE_ROOM_DP) else 0f
            val width = maxOf(circle, ringSize, badgeRoom)
            val height = circle + triangle - overlap + ringPadding
            val bitmap = canvasKit.newBitmap(width.roundToInt(), height.roundToInt())
            val canvas = Canvas(bitmap)
            val cx = width / 2f
            val circleTop = ringPadding
            val circleRect = RectF(cx - circle / 2f, circleTop, cx + circle / 2f, circleTop + circle)

            if (pin.ringColor != null) {
                val ring = canvasKit.strokePaint(pin.ringColor, canvasKit.px(RING_STROKE_DP))
                val radius = ringSize / 2f - canvasKit.px(RING_STROKE_DP) / 2f
                canvas.drawCircle(cx, circleRect.centerY(), radius, ring)
            }

            val shadow = canvasKit.fillPaint(pin.fill).apply {
                setShadowLayer(
                    canvasKit.px(SHADOW_BLUR_DP),
                    0f,
                    canvasKit.px(SHADOW_OFFSET_DP),
                    AndroidColor.argb(SHADOW_ALPHA, 0, 0, 0),
                )
            }
            canvas.drawOval(circleRect, shadow)
            canvas.drawOval(circleRect, canvasKit.fillPaint(pin.fill))
            drawCenter(canvas, circleRect, pin)

            val triangleTop = circleRect.bottom - overlap
            val pointer = Path().apply {
                moveTo(cx - triangle / 2f, triangleTop)
                lineTo(cx + triangle / 2f, triangleTop)
                lineTo(cx, triangleTop + triangle)
                close()
            }
            canvas.drawPath(pointer, canvasKit.fillPaint(pin.fill))

            if (pin.badge != null) {
                drawHopBadge(canvas, circleRect, pin.badge)
            }
            return bitmap
        }

        fun drawCenter(canvas: Canvas, circleRect: RectF, pin: StandardPin) {
            when {
                pin.glyph != null -> drawGlyph(canvas, circleRect, pin.glyph)
                pin.letter != null -> canvasKit.drawCenteredText(
                    CenteredLabel(
                        canvas = canvas,
                        text = pin.letter,
                        cx = circleRect.centerX(),
                        cy = circleRect.centerY(),
                        textSize = canvasKit.px(LETTER_TEXT_DP),
                        color = AndroidColor.WHITE,
                    ),
                )
                pin.centerDot -> {
                    val dot = canvasKit.px(CENTER_DOT_DP)
                    canvas.drawCircle(
                        circleRect.centerX(),
                        circleRect.centerY(),
                        dot / 2f,
                        canvasKit.fillPaint(AndroidColor.WHITE),
                    )
                }
            }
        }

        fun drawHopBadge(canvas: Canvas, circleRect: RectF, badge: Int) {
            val size = canvasKit.px(HOP_BADGE_DP)
            val badgeRect = RectF(
                circleRect.right + canvasKit.px(HOP_BADGE_OVERHANG_DP) - size,
                circleRect.top,
                circleRect.right + canvasKit.px(HOP_BADGE_OVERHANG_DP),
                circleRect.top + size,
            )
            canvas.drawOval(badgeRect, canvasKit.fillPaint(MapPinPalette.BLUE.toAndroidColor()))
            canvas.drawOval(
                badgeRect,
                canvasKit.strokePaint(AndroidColor.WHITE, canvasKit.px(HOP_BADGE_STROKE_DP)),
            )
            canvasKit.drawCenteredText(
                CenteredLabel(
                    canvas = canvas,
                    text = badge.toString(),
                    cx = badgeRect.centerX(),
                    cy = badgeRect.centerY(),
                    textSize = canvasKit.px(HOP_BADGE_TEXT_DP),
                    color = AndroidColor.WHITE,
                ),
            )
        }

        fun drawGlyph(canvas: Canvas, circle: RectF, glyph: PinGlyph) {
            val size = canvasKit.px(GLYPH_DP)
            val dst = RectF(
                circle.centerX() - size / 2f,
                circle.centerY() - size / 2f,
                circle.centerX() + size / 2f,
                circle.centerY() + size / 2f,
            )
            drawVector(canvas, glyph.imageVector, dst, AndroidColor.WHITE)
        }

        fun drawVector(canvas: Canvas, vector: ImageVector, dst: RectF, color: Int) {
            val paint = canvasKit.fillPaint(color)
            val scaleX = dst.width() / vector.viewportWidth
            val scaleY = dst.height() / vector.viewportHeight
            canvas.save()
            canvas.translate(dst.left, dst.top)
            canvas.scale(scaleX, scaleY)
            drawVectorGroup(canvas, vector.root, paint)
            canvas.restore()
        }

        fun drawVectorGroup(canvas: Canvas, group: VectorGroup, paint: Paint) {
            for (node in group) {
                when (node) {
                    is VectorPath -> {
                        val path = node.pathData.toPath().asAndroidPath()
                        path.fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                            Path.FillType.EVEN_ODD
                        } else {
                            Path.FillType.WINDING
                        }
                        canvas.drawPath(path, paint)
                    }
                    is VectorGroup -> drawVectorGroup(canvas, node, paint)
                }
            }
        }
    }
}

private enum class PinGlyph { PERSON, CELL_TOWER, GROUPS, LOCATION }

private val MapPinShape.fill: MapLibreChrome.PinRgb
    get() = when (this) {
        MapPinShape.CHAT_TEARDROP -> MapPinPalette.CHAT
        MapPinShape.REPEATER_DIAMOND -> MapPinPalette.REPEATER
        MapPinShape.ROOM_HEXAGON -> MapPinPalette.ROOM
        MapPinShape.DROPPED_PIN -> MapPinPalette.DROPPED
    }

private val MapPinShape.glyph: PinGlyph
    get() = when (this) {
        MapPinShape.CHAT_TEARDROP -> PinGlyph.PERSON
        MapPinShape.REPEATER_DIAMOND -> PinGlyph.CELL_TOWER
        MapPinShape.ROOM_HEXAGON -> PinGlyph.GROUPS
        MapPinShape.DROPPED_PIN -> PinGlyph.LOCATION
    }

private val PinGlyph.imageVector: ImageVector
    get() = when (this) {
        PinGlyph.PERSON -> AppIcons.Person
        PinGlyph.CELL_TOWER -> AppIcons.CellTower
        PinGlyph.GROUPS -> AppIcons.Groups
        PinGlyph.LOCATION -> AppIcons.Place
    }

private fun opaqueColor(rgb: Int): Int = AndroidColor.rgb(
    (rgb shr Byte.SIZE_BITS * 2) and COLOR_CHANNEL_MAX,
    (rgb shr Byte.SIZE_BITS) and COLOR_CHANNEL_MAX,
    rgb and COLOR_CHANNEL_MAX,
)

private const val COLOR_CHANNEL_MAX = 255
private const val LOCATION_HERO_COLOR = 0xFF375F
private const val LOCATION_DOT_DARK_CASING_ALPHA = 217
private const val PILL_FILL_ALPHA = 217
private const val PILL_SIZE_DP = 20f
private const val PILL_CORNER_DP = 8f
private const val PILL_MARGIN_DP = 1f

private const val CIRCLE_DP = 36f
private const val TRIANGLE_DP = 10f
private const val TRIANGLE_OVERLAP_DP = 3f
private const val RING_PADDING_DP = 4f
private const val RING_SIZE_DP = 44f
private const val RING_STROKE_DP = 3f
private const val BADGE_ROOM_DP = 48f
private const val HOP_BADGE_DP = 20f
private const val HOP_BADGE_OVERHANG_DP = 4f
private const val HOP_BADGE_STROKE_DP = 2f
private const val HOP_BADGE_TEXT_DP = 13f
private const val GLYPH_DP = 22f
private const val LETTER_TEXT_DP = 14f
private const val CENTER_DOT_DP = 12f
private const val SHADOW_BLUR_DP = 4f
private const val SHADOW_OFFSET_DP = 2f
private const val SHADOW_ALPHA = 76
private const val OBSTRUCTION_DP = 26f
private const val OBSTRUCTION_ARM_DP = 9f
private const val OBSTRUCTION_CASING_DP = 6f
private const val OBSTRUCTION_STROKE_DP = 2.5f
private const val CROSSHAIR_SPAN_DP = 44f
private const val CROSSHAIR_CASING_DP = 6f
private const val CROSSHAIR_STROKE_DP = 2f
private const val CROSSHAIR_GAP_DP = 4f
private const val CROSSHAIR_OUTER_DP = 22f
private const val CROSSHAIR_BADGE_DP = 20f
private const val CROSSHAIR_BADGE_GAP_DP = 2f
private const val CROSSHAIR_BADGE_CORNER_DP = 9f
private const val CROSSHAIR_BADGE_TEXT_DP = 11f
private const val LOS_RADIO_LETTER = "R"
private const val LOCATION_DOT_DP = 20f
private const val LOCATION_DOT_DIAMETER_DP = 14f
private const val LOCATION_DOT_STROKE_DP = 2f

/** Bottom-anchored sprite for a path thumbnail pin. Badge points draw no marker. */
internal fun MapPinSprites.pathPointBitmap(point: MapPoint): Bitmap? {
    if (point.pinStyle == PinStyle.BADGE) return null
    val badge = mapPinHopBadge(point.pinStyle, point.hopIndex)
    return when (point.pinStyle) {
        PinStyle.POINT_A -> letterPinBitmap(MapPinPalette.BLUE.toAndroidColor(), LOS_ENDPOINT_A)
        PinStyle.POINT_B -> letterPinBitmap(MapPinPalette.GREEN.toAndroidColor(), LOS_ENDPOINT_B)
        PinStyle.REPEATER_HOP -> hopPinBitmap(badge, ringed = false)
        PinStyle.REPEATER_RING_WHITE -> hopPinBitmap(badge, ringed = true)
        PinStyle.REPEATER_RING_BLUE -> ringedRepeaterBitmap(MapPinPalette.BLUE.toAndroidColor())
        PinStyle.REPEATER_RING_GREEN -> ringedRepeaterBitmap(MapPinPalette.GREEN.toAndroidColor())
        PinStyle.CROSSHAIR -> crosshairBitmap()
        PinStyle.OBSTRUCTION -> obstructionBitmap()
        PinStyle.LOCATION_FIX_LATEST -> heroPinBitmap()
        else -> point.pinStyle.mapPinShape()?.let { shapeBitmap(it) }
    }
}
