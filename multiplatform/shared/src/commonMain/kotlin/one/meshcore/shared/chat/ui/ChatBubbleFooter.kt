package one.meshcore.shared.chat.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.MessageItem
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_heard_times_plural
import one.meshcore.shared.resources.chats_message_hop_count_accessibility_label
import one.meshcore.shared.resources.chats_message_path_accessibility_label
import one.meshcore.shared.resources.chats_message_repeat_plural
import one.meshcore.shared.resources.chats_message_repeat_singular
import one.meshcore.shared.resources.chats_message_send_time_accessibility_label
import one.meshcore.shared.resources.chats_message_send_time_corrected_accessibility_label
import one.meshcore.shared.resources.chats_message_status_sent_multiple
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The outgoing bubble footer as one trailing row inside the bubble, mirroring iOS's reversed
 * `BubbleFooterRow`: send-count badge, heard-repeat badge, send time, then the delivery status glyph at
 * the inner trailing edge. The glyph tints to the bubble's on-primary content color so it reads against the
 * accent fill; the ×N send-count is its own badge here, not baked into the glyph.
 */
@Composable
internal fun ColumnScope.OutgoingBubbleFooter(item: MessageItem, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val footer = item.footer
    val tint = LocalContentColor.current
    Row(
        modifier = modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        outgoingFooterSegments(footer).forEach { segment ->
            when (segment) {
                OutgoingFooterSegment.SEND_COUNT ->
                    FooterCaption(
                        spoken = formattedStringResource(
                            Res.string.chats_message_status_sent_multiple,
                            footer.sendCount,
                        ),
                        icon = AppIcons.Send,
                        text = outgoingSendCountChipText(footer.sendCount),
                        color = tint,
                    )
                OutgoingFooterSegment.HEARD -> {
                    val word = stringResource(
                        if (outgoingHeardUsesSingular(footer.heardRepeats)) {
                            Res.string.chats_message_repeat_singular
                        } else {
                            Res.string.chats_message_repeat_plural
                        },
                    )
                    FooterCaption(
                        spoken = "${footer.heardRepeats} $word",
                        icon = AppIcons.Repeat,
                        text = footer.heardRepeats.toString(),
                        color = tint,
                    )
                }
                OutgoingFooterSegment.TIME ->
                    footer.sendTimeToShowEpochSeconds?.let { sendTime ->
                        BubbleSendTimeFooter(
                            epochSeconds = sendTime,
                            wasCorrected = footer.sendTimeWasCorrected,
                            color = tint,
                        )
                    }
                OutgoingFooterSegment.STATUS ->
                    MessageStatusGlyph(footer = footer, onRetry = onRetry, tint = tint)
            }
        }
    }
}

/** iOS `BubbleSendTimeFooter`: clock, optional corrected badge, and a spoken Send time: label. */
@Composable
private fun BubbleSendTimeFooter(
    epochSeconds: Double,
    wasCorrected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val timeText = chatClockLabel(epochSeconds, formatter = LocalTimestampFormatter.current)
    val spoken = if (wasCorrected) {
        formattedStringResource(Res.string.chats_message_send_time_corrected_accessibility_label, timeText)
    } else {
        formattedStringResource(Res.string.chats_message_send_time_accessibility_label, timeText)
    }
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (wasCorrected) {
            Icon(
                imageVector = AppIcons.Warning,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(CORRECTED_BADGE_ICON_DP.dp),
            )
        }
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/**
 * The outgoing footer segments in render order, mirroring iOS `BubbleFooterRow` reversed for a trailing
 * bubble: the send-count and heard-repeat badges, then the send time, then the delivery status glyph hugging
 * the inner trailing edge. The glyph is always present for an outgoing bubble (its footer always shows the
 * status row); the badges and time appear only when their data is present.
 */
internal enum class OutgoingFooterSegment { SEND_COUNT, HEARD, TIME, STATUS }

/** Incoming send time then hop / path / region / optional heard-count chips, leading. */
internal enum class IncomingFooterSegment { TIME, HOP, PATH, REGION, HEARD }

internal fun incomingFooterSegments(footer: one.meshcore.shared.chat.MessageFooter): List<IncomingFooterSegment> =
    buildList {
        if (footer.sendTimeToShowEpochSeconds != null) add(IncomingFooterSegment.TIME)
        if (footer.showHop) add(IncomingFooterSegment.HOP)
        if (!footer.formattedPath.isNullOrEmpty()) add(IncomingFooterSegment.PATH)
        if (!footer.regionToShow.isNullOrEmpty()) add(IncomingFooterSegment.REGION)
        if (footer.showHeardCount) add(IncomingFooterSegment.HEARD)
    }

internal fun outgoingFooterSegments(footer: one.meshcore.shared.chat.MessageFooter): List<OutgoingFooterSegment> =
    buildList {
        if (footer.sendCount > 1) add(OutgoingFooterSegment.SEND_COUNT)
        if (footer.heardRepeats > 0) add(OutgoingFooterSegment.HEARD)
        // iOS renders the send time on every bubble whenever footer.sendTimeToShow is non-nil, not on a cluster
        // boundary; the builder now sets it unconditionally, so this is present on each outgoing bubble.
        if (footer.sendTimeToShowEpochSeconds != null) add(OutgoingFooterSegment.TIME)
        add(OutgoingFooterSegment.STATUS)
    }

/** iOS `BubbleSendCountFooter` text: `2x`, not a leading multiplication sign. */
internal fun outgoingSendCountChipText(count: Int): String = "${count}x"

/** iOS `BubbleRepeatFooter` VoiceOver uses singular only when the count is 1. */
internal fun outgoingHeardUsesSingular(count: Int): Boolean = count == 1

/** Incoming send time and optional metadata chips on one leading row. */
@Composable
internal fun IncomingBubbleFooter(item: MessageItem, modifier: Modifier = Modifier) {
    val segments = incomingFooterSegments(item.footer)
    if (segments.isNotEmpty()) {
        IncomingBubbleFooterContent(item = item, segments = segments, modifier = modifier)
    }
}

@Composable
private fun IncomingBubbleFooterContent(
    item: MessageItem,
    segments: List<IncomingFooterSegment>,
    modifier: Modifier = Modifier,
) {
    val footer = item.footer
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val hopSpoken = formattedStringResource(
        Res.string.chats_message_hop_count_accessibility_label,
        footer.hopCount,
    )
    val pathSpoken = footer.formattedPath?.let { path ->
        formattedStringResource(Res.string.chats_message_path_accessibility_label, path)
    }
    val regionSpoken = regionSpokenText(footer.regionToShow, footer.regionMatchNames)
    var showingRegionHelp by remember { mutableStateOf(false) }
    val regionAmbiguous = footerCaptionIsInteractive(footer.regionMatchNames.size)
    Row(
        modifier = modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        segments.forEach { segment ->
            when (segment) {
                IncomingFooterSegment.TIME ->
                    footer.sendTimeToShowEpochSeconds?.let { sendTime ->
                        BubbleSendTimeFooter(
                            epochSeconds = sendTime,
                            wasCorrected = footer.sendTimeWasCorrected,
                            color = tint,
                        )
                    }
                IncomingFooterSegment.HOP -> FooterCaption(
                    spoken = hopSpoken,
                    icon = AppIcons.Timeline,
                    text = footer.hopCount.toString(),
                    color = tint,
                )
                IncomingFooterSegment.PATH -> FooterCaption(
                    spoken = pathSpoken.orEmpty(),
                    icon = AppIcons.Route,
                    text = footer.formattedPath.orEmpty(),
                    color = tint,
                )
                IncomingFooterSegment.REGION -> FooterCaption(
                    spoken = regionSpoken.orEmpty(),
                    icon = AppIcons.Public,
                    text = footer.regionToShow.orEmpty(),
                    color = tint,
                    onClick = if (regionAmbiguous) {
                        { showingRegionHelp = true }
                    } else {
                        null
                    },
                    trailingIcon = if (regionAmbiguous) AppIcons.Info else null,
                )
                IncomingFooterSegment.HEARD -> FooterCaption(
                    spoken = formattedStringResource(
                        Res.string.chats_message_action_heard_times_plural,
                        footer.incomingHeardTotal,
                    ),
                    icon = AppIcons.GraphicEq,
                    text = footer.incomingHeardTotal.toString(),
                    color = tint,
                )
            }
        }
    }
    RegionAmbiguousDialog(
        visible = showingRegionHelp,
        matchNames = footer.regionMatchNames,
        onDismiss = { showingRegionHelp = false },
    )
}

@Composable
private fun FooterCaption(
    spoken: String,
    icon: ImageVector,
    text: String,
    color: Color,
    onClick: (() -> Unit)? = null,
    trailingIcon: ImageVector? = null,
) {
    val label = @Composable {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = FOOTER_CAPTION_TEXT_MAX),
        )
    }
    val leading = @Composable {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(FOOTER_CAPTION_ICON_DP.dp),
        )
    }
    val chipOutline = Modifier
        .border(
            width = FOOTER_CHIP_STROKE_DP.dp,
            color = color.copy(alpha = FOOTER_CHIP_STROKE_ALPHA),
            shape = CircleShape,
        )
        .padding(
            horizontal = FOOTER_CHIP_HORIZONTAL_PADDING_DP.dp,
            vertical = FOOTER_CHIP_VERTICAL_PADDING_DP.dp,
        )
    Row(
        modifier = Modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(chipOutline)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        leading()
        label()
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(FOOTER_CAPTION_ICON_DP.dp),
            )
        }
    }
}

/** Ambiguous flood-region captions are the only tappable footer slot. */
internal fun footerCaptionIsInteractive(regionMatchCount: Int): Boolean = regionMatchCount > 1

internal const val FOOTER_CHIP_HORIZONTAL_PADDING_DP = 6
internal const val FOOTER_CHIP_VERTICAL_PADDING_DP = 2
internal const val FOOTER_CHIP_STROKE_DP = 1
internal const val FOOTER_CHIP_STROKE_ALPHA = 0.35f

private const val CORRECTED_BADGE_ICON_DP = 12
internal const val FOOTER_CAPTION_ICON_DP = 12
private val FOOTER_CAPTION_TEXT_MAX = 160.dp
