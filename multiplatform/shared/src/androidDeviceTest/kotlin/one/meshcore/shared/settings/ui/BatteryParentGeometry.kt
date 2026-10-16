package one.meshcore.shared.settings.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs

internal data class BatteryParentGeometry(
    val index: Int,
    val field: Rect,
    val percent: Rect,
    val unit: Rect,
    val viewport: Rect,
    val clippedFieldInWindow: Rect,
    val window: Rect,
    val imeBottom: Int,
    val navigationBottom: Int,
    val imeVisible: Boolean,
    val focused: Boolean,
    val scroll: Float,
    val viewHeight: Int,
    val rootHeight: Int,
    val viewOrigin: IntOffset,
    val viewScroll: Int,
    val rootScroll: Int,
    val softInputMode: Int,
    val value: String,
    val valueLineWidth: Float,
    val valueLineHeight: Float,
    val valueHorizontalPadding: Float,
    val valueComplete: Boolean,
) {
    val valueFits: Boolean
        get() = valueComplete &&
            valueLineWidth <= this.field.width - valueHorizontalPadding + ROUNDING_PIXEL &&
            valueLineHeight <= this.field.height + ROUNDING_PIXEL

    val complete: Boolean
        get() {
            val usable = Rect(
                viewport.left,
                maxOf(viewport.top, window.top),
                viewport.right,
                minOf(
                    viewport.bottom,
                    window.bottom - maxOf(imeBottom, navigationBottom),
                ),
            )
            return listOf(this.field, percent, unit).all { bounds ->
                bounds.width > 0 &&
                    bounds.height > 0 &&
                    bounds.left >= usable.left - ROUNDING_PIXEL &&
                    bounds.right <= usable.right + ROUNDING_PIXEL &&
                    bounds.top >= usable.top - ROUNDING_PIXEL &&
                    bounds.bottom <= usable.bottom + ROUNDING_PIXEL
            } &&
                abs(clippedFieldInWindow.width - this.field.width) <= ROUNDING_PIXEL &&
                abs(clippedFieldInWindow.height - this.field.height) <= ROUNDING_PIXEL &&
                abs(percent.center.y - this.field.center.y) <= ROUNDING_PIXEL &&
                abs(unit.center.y - this.field.center.y) <= ROUNDING_PIXEL
        }

    private companion object {
        const val ROUNDING_PIXEL = 1f
    }
}
