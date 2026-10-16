package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.ConversationSection
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_row_pin
import one.meshcore.shared.resources.chats_title
import org.jetbrains.compose.resources.stringResource

/**
 * Section title for a pinned group or the remaining chats. Spoken as a heading so the two
 * groups stay distinct without a pin glyph on each row.
 */
@Composable
internal fun ConversationSectionHeader(kind: ConversationSection.Kind) {
    val title = stringResource(
        if (kind == ConversationSection.Kind.PINNED) Res.string.chats_row_pin else Res.string.chats_title,
    )
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SECTION_HEADER_HORIZONTAL, vertical = SECTION_HEADER_VERTICAL)
            .semantics { heading() },
    )
}

private val SECTION_HEADER_HORIZONTAL = 16.dp
private val SECTION_HEADER_VERTICAL = 12.dp
