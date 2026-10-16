package one.meshcore.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import one.meshcore.shared.connection.BleSignalTier
import one.meshcore.shared.map.SnrQuality

const val SIGNAL_BAR_COUNT = 4

private val SIGNAL_BAR_WIDTH = 3.dp
private val SIGNAL_BAR_GAP = 2.dp
private val SIGNAL_BAR_MAX_HEIGHT = 14.dp
private val SIGNAL_BAR_MIN_HEIGHT = 4.dp
private val SIGNAL_BAR_CORNER = 1.dp
private const val SIGNAL_BAR_INACTIVE_ALPHA = 0.24f

/**
 * Four rising bars. Filled bars use [tint]; empty bars use a muted on-surface so the meter stays
 * visible at every strength. The parent row owns TalkBack.
 */
@Composable
fun SignalBarMeter(filledBars: Int, tint: Color, modifier: Modifier = Modifier) {
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SIGNAL_BAR_INACTIVE_ALPHA)
    val filled = filledBars.coerceIn(0, SIGNAL_BAR_COUNT)
    Canvas(
        modifier.size(
            width = SIGNAL_BAR_WIDTH * SIGNAL_BAR_COUNT + SIGNAL_BAR_GAP * (SIGNAL_BAR_COUNT - 1),
            height = SIGNAL_BAR_MAX_HEIGHT,
        ),
    ) {
        val barWidth = SIGNAL_BAR_WIDTH.toPx()
        val gap = SIGNAL_BAR_GAP.toPx()
        val maxH = size.height
        val minH = SIGNAL_BAR_MIN_HEIGHT.toPx()
        val corner = CornerRadius(SIGNAL_BAR_CORNER.toPx())
        for (index in 0 until SIGNAL_BAR_COUNT) {
            val t = (index + 1).toFloat() / SIGNAL_BAR_COUNT
            val height = minH + (maxH - minH) * t
            drawRoundRect(
                color = if (index < filled) tint else inactive,
                topLeft = Offset(x = index * (barWidth + gap), y = maxH - height),
                size = Size(barWidth, height),
                cornerRadius = corner,
            )
        }
    }
}

/** BLE scan: stronger RSSI fills more of the four bars. */
val BleSignalTier.filledBarCount: Int
    get() = when (this) {
        BleSignalTier.STRONG -> SIGNAL_BAR_COUNT
        BleSignalTier.MEDIUM -> 3
        BleSignalTier.WEAK -> 2
    }

/** SNR buckets: excellent fills all four, unknown fills none. */
val SnrQuality.filledBarCount: Int
    get() = when (this) {
        SnrQuality.EXCELLENT -> SIGNAL_BAR_COUNT
        SnrQuality.GOOD -> 3
        SnrQuality.FAIR -> 2
        SnrQuality.POOR -> 1
        SnrQuality.UNKNOWN -> 0
    }
