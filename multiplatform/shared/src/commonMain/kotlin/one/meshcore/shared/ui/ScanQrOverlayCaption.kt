package one.meshcore.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Error capsule fill opacity on theme error, over the camera preview. */
internal const val SCAN_QR_ERROR_FILL_ALPHA = 0.8f

/** Instruction capsule fill opacity on inverse surface, over the camera preview. */
internal const val SCAN_QR_INSTRUCTION_FILL_ALPHA = 0.6f

private val SCAN_QR_CAPTION_PADDING = 16.dp
private val SCAN_QR_CAPTION_LEADING_GAP = 12.dp
private val SCAN_QR_FRAME_SIZE = 250.dp
private val SCAN_QR_FRAME_OUTER_STROKE = 6.dp
private val SCAN_QR_FRAME_INNER_STROKE = 3.dp
private val SCAN_QR_FRAME_INSET = 6.dp
private val SCAN_QR_FRAME_CORNER = 20.dp
private val SCAN_QR_FRAME_INNER_CORNER = 14.dp
private val SCAN_QR_FRAME_LIGHT_EDGE = Color.White

internal fun scanQrInstructionFill(inverseSurface: Color): Color =
    inverseSurface.copy(alpha = SCAN_QR_INSTRUCTION_FILL_ALPHA)

internal fun scanQrErrorFill(error: Color): Color = error.copy(alpha = SCAN_QR_ERROR_FILL_ALPHA)

/** Two-tone viewfinder stays visible over both light and dark camera scenes. */
@Composable
internal fun ScanQrViewfinderFrame(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier.size(SCAN_QR_FRAME_SIZE),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .border(
                    SCAN_QR_FRAME_OUTER_STROKE,
                    scheme.scrim,
                    RoundedCornerShape(SCAN_QR_FRAME_CORNER),
                ),
        )
        Box(
            Modifier
                .matchParentSize()
                .padding(SCAN_QR_FRAME_INSET)
                .border(
                    SCAN_QR_FRAME_INNER_STROKE,
                    SCAN_QR_FRAME_LIGHT_EDGE,
                    RoundedCornerShape(SCAN_QR_FRAME_INNER_CORNER),
                ),
        )
    }
}

/**
 * Caption over a camera preview: an inverse-surface instruction chip, or an error capsule the user
 * taps to dismiss. Matches the iOS overlay layout with Material inverse / error roles instead of
 * hardcoded black and white.
 */
@Composable
internal fun ScanQrOverlayCaption(
    text: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (error) scanQrErrorFill(scheme.error) else scanQrInstructionFill(scheme.inverseSurface)
    val content = if (error) scheme.onError else scheme.inverseOnSurface
    Row(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(fill, RoundedCornerShape(percent = 50))
            .padding(SCAN_QR_CAPTION_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SCAN_QR_CAPTION_LEADING_GAP),
    ) {
        leading?.invoke()
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            textAlign = TextAlign.Center,
        )
    }
}
