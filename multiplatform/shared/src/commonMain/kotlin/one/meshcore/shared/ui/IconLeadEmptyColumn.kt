package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Space under the glyph, before the title. */
private val IconLeadTitleGap = 12.dp

/** Space under the title, before the description. */
private val IconLeadDescriptionGap = 8.dp

/**
 * Centered empty state whose glyph sits 12 dp above the title and whose description sits 8 dp
 * below it. Callers own the padding. A column that spaces every child by 8 dp stays on
 * [EmptyMessageColumn].
 */
@Composable
internal fun IconLeadEmptyColumn(
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = IconLeadTitleGap).size(EMPTY_STATE_ICON_SIZE),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = textAlign,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = textAlign,
            modifier = Modifier.padding(top = IconLeadDescriptionGap),
        )
    }
}
