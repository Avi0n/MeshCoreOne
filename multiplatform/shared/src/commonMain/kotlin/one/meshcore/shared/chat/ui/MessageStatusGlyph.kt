package one.meshcore.shared.chat.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.MessageFooter
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_status_delivered
import one.meshcore.shared.resources.chats_message_status_failed
import one.meshcore.shared.resources.chats_message_status_retrying
import one.meshcore.shared.resources.chats_message_status_retrying_attempt
import one.meshcore.shared.resources.chats_message_status_sending
import one.meshcore.shared.resources.chats_message_status_sent
import one.meshcore.shared.resources.chats_message_status_sent_multiple
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.rememberAppHaptics
import one.meshcore.shared.ui.rememberReduceMotion
import org.jetbrains.compose.resources.stringResource

/**
 * Outgoing-message status under a sent bubble. Visuals match iOS `BubbleStatusFooter`: a mini spinner
 * while in flight, a filled check for terminal channel `.sent` and `.delivered`, and a refresh control
 * on failure. Spoken status still comes from [statusLabelKind]. Glyph swaps crossfade in place so a
 * DM `.sent` spinner becomes `.delivered` without a fresh bubble identity, matching iOS
 * `withAnimation { timeline.applyStatusUpdate }`.
 */
@Composable
fun MessageStatusGlyph(footer: MessageFooter, onRetry: () -> Unit, tint: Color, modifier: Modifier = Modifier) {
    if (!footer.showStatusRow) return
    val label = statusAccessibilityLabel(footer)
    val kind = statusVisualKind(footer)
    val reduceMotion = rememberReduceMotion()
    val spec = if (reduceMotion) snap() else tween<Float>(MESSAGE_STATUS_CROSSFADE_MS)
    AnimatedContent(
        targetState = kind,
        modifier = modifier,
        transitionSpec = { fadeIn(spec) togetherWith fadeOut(spec) },
        label = "messageStatusGlyph",
    ) { shown ->
        if (shown == StatusVisualKind.RETRY) {
            RetryAffordance(label = label, onRetry = onRetry, tint = tint)
        } else {
            Row(
                modifier = Modifier.semantics { contentDescription = label },
                horizontalArrangement = Arrangement.spacedBy(STATUS_ROW_GAP_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusVisual(kind = shown, tint = tint)
                visibleRetryAttemptText(footer)?.let { attempt ->
                    Text(
                        text = attempt,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusVisual(kind: StatusVisualKind, tint: Color) {
    when (kind) {
        StatusVisualKind.SPINNER -> CircularProgressIndicator(
            modifier = Modifier.size(STATUS_SPINNER_DP.dp),
            color = tint,
            strokeWidth = STATUS_SPINNER_STROKE_DP.dp,
        )
        StatusVisualKind.CHECK -> Icon(
            imageVector = STATUS_SUCCESS_ICON,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(STATUS_ICON_DP.dp),
        )
        StatusVisualKind.RETRY -> Unit
    }
}

@Composable
private fun RetryAffordance(label: String, onRetry: () -> Unit, tint: Color, modifier: Modifier = Modifier) {
    val haptics = rememberAppHaptics()
    // Layout matches the 14dp status glyph so the retry control cannot stretch the bubble fill.
    Box(
        modifier = modifier.size(STATUS_RETRY_LAYOUT_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = STATUS_RETRY_ICON,
            contentDescription = label,
            tint = tint,
            modifier = Modifier
                .size(STATUS_ICON_DP.dp)
                .clickable(role = Role.Button) {
                    haptics.selection()
                    onRetry()
                },
        )
    }
}

/** Which `BubbleStatusFooter` glyph a footer draws. */
internal enum class StatusVisualKind { SPINNER, CHECK, RETRY }

internal fun statusVisualKind(footer: MessageFooter): StatusVisualKind = when (footer.status) {
    MessageStatus.PENDING, MessageStatus.SENDING, MessageStatus.RETRYING -> StatusVisualKind.SPINNER
    MessageStatus.DELIVERED -> StatusVisualKind.CHECK
    MessageStatus.FAILED -> StatusVisualKind.RETRY
    MessageStatus.SENT -> if (footer.isChannelMessage) StatusVisualKind.CHECK else StatusVisualKind.SPINNER
}

/** iOS `BubbleStatusFooter` shows `retryAttempt + 1` because the stored value is zero-based. */
internal fun retryDisplayAttempt(retryAttempt: Int): Int = retryAttempt + 1

internal val STATUS_SUCCESS_ICON get() = AppIcons.CheckCircle
internal val STATUS_RETRY_ICON get() = AppIcons.Refresh

/** The status a footer is spoken as, resolved before any string lookup so the choice is testable. */
internal enum class StatusLabelKind { SENDING, SENT, SENT_MULTIPLE, DELIVERED, FAILED, RETRYING, RETRYING_ATTEMPT }

/**
 * Picks the spoken status for [footer]. A DM `.sent` reads as still-sending: the radio has only
 * acknowledged the queue-for-TX and a missing end-to-end ACK can still flip the row to failed. A channel
 * `.sent` has no ACK to wait for and is terminal success. Mirrors the Swift `MessageStatusText.text(for:)`.
 */
internal fun statusLabelKind(footer: MessageFooter): StatusLabelKind = when (footer.status) {
    MessageStatus.PENDING, MessageStatus.SENDING -> StatusLabelKind.SENDING
    MessageStatus.SENT -> when {
        !footer.isChannelMessage -> StatusLabelKind.SENDING
        footer.sendCount > 1 -> StatusLabelKind.SENT_MULTIPLE
        else -> StatusLabelKind.SENT
    }
    MessageStatus.DELIVERED -> StatusLabelKind.DELIVERED
    MessageStatus.FAILED -> StatusLabelKind.FAILED
    MessageStatus.RETRYING ->
        if (footer.maxRetryAttempts > 0) StatusLabelKind.RETRYING_ATTEMPT else StatusLabelKind.RETRYING
}

@Composable
internal fun statusAccessibilityLabel(footer: MessageFooter): String = when (statusLabelKind(footer)) {
    StatusLabelKind.SENDING -> stringResource(Res.string.chats_message_status_sending)
    StatusLabelKind.SENT -> stringResource(Res.string.chats_message_status_sent)
    StatusLabelKind.SENT_MULTIPLE ->
        formattedStringResource(Res.string.chats_message_status_sent_multiple, footer.sendCount)
    StatusLabelKind.DELIVERED -> stringResource(Res.string.chats_message_status_delivered)
    StatusLabelKind.FAILED -> stringResource(Res.string.chats_message_status_failed)
    StatusLabelKind.RETRYING -> stringResource(Res.string.chats_message_status_retrying)
    StatusLabelKind.RETRYING_ATTEMPT -> retryingAttemptLabel(footer)
}

@Composable
private fun retryingAttemptLabel(footer: MessageFooter): String = formattedStringResource(
    Res.string.chats_message_status_retrying_attempt,
    retryDisplayAttempt(footer.retryAttempt),
    footer.maxRetryAttempts,
)

/** Visible attempt fraction beside the spinner; iOS uses `retryAttempt + 1` / max, not the a11y sentence. */
internal fun visibleRetryAttemptText(footer: MessageFooter): String? {
    if (footer.status != MessageStatus.RETRYING || footer.maxRetryAttempts <= 0) return null
    return "${retryDisplayAttempt(footer.retryAttempt)}/${footer.maxRetryAttempts}"
}

private const val STATUS_ROW_GAP_DP = 3
internal const val STATUS_ICON_DP = 14

/** Layout contribution of the failed-send control; must stay at the glyph size, not 48dp. */
internal const val STATUS_RETRY_LAYOUT_DP = STATUS_ICON_DP
private const val STATUS_SPINNER_DP = 12
private const val STATUS_SPINNER_STROKE_DP = 1.5f

/** iOS `withAnimation` around `applyStatusUpdate` crossfades the footer glyph. */
internal const val MESSAGE_STATUS_CROSSFADE_MS = 300
