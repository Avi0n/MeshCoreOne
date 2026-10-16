package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.MessageFragment
import one.meshcore.shared.chat.MessageItem
import one.meshcore.shared.chat.NodeNameResolution
import one.meshcore.shared.model.toUppercaseHex

/**
 * The sheet's content-preview header, mirroring iOS `ActionsPreviewHeader`: for an incoming message the
 * sender's node-ID (first key-prefix byte in hex) then the resolved name, the send time trailing, and a
 * one-line preview of the message text (two lines at accessibility Dynamic Type). Falls back to the
 * resolved envelope while [message] details load. Name and timestamp stack when they cannot share a
 * row, matching iOS `ViewThatFits`.
 */
@Composable
internal fun MessageActionsPreviewHeader(item: MessageItem, message: MessageDto?, previewLineLimit: Int) {
    val envelope = item.envelope
    val senderNodeId = if (!envelope.isOutgoing) {
        message?.senderKeyPrefix?.firstOrNull()?.let { byteArrayOf(it).toUppercaseHex() }
    } else {
        null
    }
    val previewText = message?.text ?: item.content
        .filterIsInstance<MessageFragment.Text>()
        .joinToString(" ") { it.payload.raw }
    val timestamp = chatClockLabel(envelope.dateEpochSeconds, formatter = LocalTimestampFormatter.current)
    Column(
        modifier = Modifier.fillMaxWidth().padding(MESSAGE_ACTIONS_PREVIEW_PADDING),
    ) {
        PreviewMetaThatFits(
            senderNodeId = senderNodeId,
            senderResolution = envelope.senderResolution,
            timestamp = timestamp,
        )
        Text(
            text = previewText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = previewLineLimit,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PreviewMetaThatFits(senderNodeId: String?, senderResolution: NodeNameResolution, timestamp: String) {
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val probe = subcompose("probe") {
            Row {
                PreviewSenderPrefix(senderNodeId)
                SenderNameLabel(senderResolution)
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }.map { it.measure(Constraints()) }
        val stacked = shouldStackMessageActionsPreview(
            probeWidthPx = probe.maxOfOrNull { it.width } ?: 0,
            availableWidthPx = constraints.maxWidth,
        )
        val content = subcompose(if (stacked) "stacked" else "wide") {
            if (stacked) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PreviewSenderPrefix(senderNodeId)
                        SenderNameLabel(senderResolution)
                    }
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PreviewSenderPrefix(senderNodeId)
                    SenderNameLabel(senderResolution)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        val height = content.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            content.forEach { it.placeRelative(0, 0) }
        }
    }
}

@Composable
private fun PreviewSenderPrefix(senderNodeId: String?) {
    if (senderNodeId != null) {
        Text(
            text = senderNodeId,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
    }
}

internal val MESSAGE_ACTIONS_PREVIEW_PADDING = 16.dp

internal fun messageActionsPreviewLineLimit(isAccessibilitySize: Boolean): Int = if (isAccessibilitySize) 2 else 1

internal fun shouldStackMessageActionsPreview(probeWidthPx: Int, availableWidthPx: Int): Boolean =
    probeWidthPx > availableWidthPx
