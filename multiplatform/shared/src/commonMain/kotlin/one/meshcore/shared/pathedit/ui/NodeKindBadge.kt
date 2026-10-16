package one.meshcore.shared.pathedit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/** Tonal tag labeling a node kind (Room, Discovered). Extra-small corners, not a chip. */
@Composable
internal fun NodeKindBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = modifier
            .background(
                color.copy(alpha = PathEditMetrics.BADGE_BACKGROUND_ALPHA),
                MaterialTheme.shapes.extraSmall,
            )
            .padding(
                horizontal = PathEditMetrics.badgeHorizontalPadding,
                vertical = PathEditMetrics.badgeVerticalPadding,
            ),
    )
}
