package one.meshcore.shared.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_show_original
import one.meshcore.shared.resources.chats_message_action_translate
import one.meshcore.shared.resources.chats_message_action_translating
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private val BUBBLE_TRANSLATE_ICON_SIZE = 14.dp
private val BUBBLE_TRANSLATE_ICON_GAP = 4.dp
private val BUBBLE_TRANSLATE_SPINNER_STROKE = 1.5.dp

/** In-bubble Translation offer / progress / show-original. Mirrors iOS `BubbleTranslationControl`. */
@Composable
fun BubbleTranslationControl(phase: MessageTranslationPhase, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val label = when (phase) {
        MessageTranslationPhase.Offer -> stringResource(Res.string.chats_message_action_translate)
        MessageTranslationPhase.InProgress -> stringResource(Res.string.chats_message_action_translating)
        is MessageTranslationPhase.Showing -> stringResource(Res.string.chats_message_action_show_original)
    }
    val enabled = phase !is MessageTranslationPhase.InProgress
    val tint = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier.clickable(enabled = enabled, onClick = onTap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BUBBLE_TRANSLATE_ICON_GAP),
    ) {
        if (phase is MessageTranslationPhase.InProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(BUBBLE_TRANSLATE_ICON_SIZE),
                strokeWidth = BUBBLE_TRANSLATE_SPINNER_STROKE,
                color = tint,
            )
        } else {
            Icon(
                imageVector = AppIcons.Translate,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(BUBBLE_TRANSLATE_ICON_SIZE),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}
