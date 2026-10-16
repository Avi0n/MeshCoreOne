package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_timestamp_yesterday
import org.jetbrains.compose.resources.stringResource

/**
 * Visible text for iOS `ConversationTimestamp`: clock today, localized Yesterday, abbreviated
 * month and day otherwise. [yesterday] is the already-localized chats Yesterday string.
 */
fun conversationTimestampText(stamp: ConversationListTime, yesterday: String): String = when (stamp) {
    is ConversationListTime.Clock -> stamp.text
    ConversationListTime.Yesterday -> yesterday
    is ConversationListTime.MonthDay -> stamp.text
}

/** Clock / Yesterday / month-day for an epoch-seconds stamp, keyed on [nowSeconds]. */
@Composable
fun conversationTimestampLabel(epochSeconds: Double, nowSeconds: Long): String = conversationTimestampText(
    conversationListTime(epochSeconds, nowSeconds, formatter = LocalTimestampFormatter.current),
    stringResource(Res.string.chats_timestamp_yesterday),
)

@Composable
fun conversationTimestampLabel(epochSeconds: Long, nowSeconds: Long): String = conversationTimestampText(
    conversationListTime(epochSeconds, nowSeconds, formatter = LocalTimestampFormatter.current),
    stringResource(Res.string.chats_timestamp_yesterday),
)
