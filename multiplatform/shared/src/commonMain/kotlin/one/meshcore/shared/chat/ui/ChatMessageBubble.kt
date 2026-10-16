package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.FragmentLayout
import one.meshcore.shared.chat.GroupingFlags
import one.meshcore.shared.chat.MessageFragment
import one.meshcore.shared.chat.MessageItem
import one.meshcore.shared.chat.fragmentLayout
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.map.MapSnapshotRequest
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_divider_new_messages
import one.meshcore.shared.resources.chats_divider_new_messages_accessibility
import one.meshcore.shared.theme.LocalAppTheme
import one.meshcore.shared.theme.LocalUseDarkTheme
import one.meshcore.shared.ui.LocalHighContrast
import org.jetbrains.compose.resources.stringResource

/**
 * One rendered timeline row: its optional day and new-messages dividers, an optional direction gap, the
 * message bubble (direction-aligned, sender name for a channel message, body text, send-time), and the
 * outgoing status glyph beneath. [MessageFragment] members render in their source order. Under
 * `reverseLayout` the internal top-to-bottom order still reads
 * correctly because only the list axis is inverted, not each row's own column.
 */
@Composable
internal fun ChatMessageBubble(
    item: MessageItem,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onLinkTap: (LinkTarget) -> Unit = {},
    imageResolver: PreviewImageResolver = { null },
    gifModelResolver: (one.meshcore.shared.chat.ImageReference) -> Any? = { null },
    previews: MessagePreviewActions = MessagePreviewActions(),
    mapSnapshotResolver: MapSnapshotImageResolver = { null },
    onRequestMapSnapshot: (MapSnapshotRequest) -> Unit = {},
    onRetryMapSnapshot: (MapSnapshotRequest) -> Unit = {},
    onTapReaction: (String) -> Unit = {},
    onOpenReactionDetails: () -> Unit = {},
    translationPhase: MessageTranslationPhase? = null,
    onTranslate: () -> Unit = {},
) {
    val onManualPreviewFetch = previews.onManualPreviewFetch
    val onRetryPreviewFetch = previews.onRetryPreviewFetch
    val onInlineImageLoadFailed = previews.onInlineImageLoadFailed
    val onRequestPreviewFetch = previews.onRequestPreviewFetch
    val originalBody = item.content.filterIsInstance<MessageFragment.Text>().firstOrNull()?.payload?.raw.orEmpty()
    val spokenBody = spokenTranslationBody(
        kind = translationSpokenKind(translationPhase),
        translatedText = (translationPhase as? MessageTranslationPhase.Showing)?.translatedText.orEmpty(),
        originalBody = originalBody,
    )
    val spokenLabel = messageAccessibilityLabel(item, rememberMessageAccessibilityStrings(item), spokenBody)
    val actions = messageAccessibilityActions(
        item = item,
        onRetry = onRetry,
        onOpenReactionDetails = onOpenReactionDetails,
        onManualPreviewFetch = { onManualPreviewFetch(item.id) },
        onRetryPreviewFetch = { onRetryPreviewFetch(item.id) },
        onLinkTap = onLinkTap,
        translationPhase = translationPhase,
        onTranslate = onTranslate,
    )
    LaunchedEffect(item.previewFetchTaskID) {
        val id = item.previewFetchTaskID ?: return@LaunchedEffect
        onRequestPreviewFetch(id)
    }
    // The bubble is one accessibility node speaking one composed phrase, mirroring the iOS
    // `accessibilityElement(children: .combine)`. Walking the footer glyph by glyph never reaches the
    // message itself. The dividers set their own merge boundary so they stay separate announcements.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = spokenLabel
                customActions = actions
            },
    ) {
        if (item.grouping.showDayDivider) DayDivider(item.envelope.dateEpochSeconds)
        if (item.grouping.showNewMessagesDivider) NewMessagesDividerRow()

        val layout = fragmentLayout(item.content)
        val placement = chatBubblePlacement(
            outgoing = item.envelope.isOutgoing,
            isChannelMessage = item.footer.isChannelMessage,
            senderName = item.envelope.senderName,
            showSenderName = item.grouping.showSenderName,
            hasFailed = item.envelope.hasFailed,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = bubbleRowPaddingTop(item.grouping).dp),
            horizontalAlignment = if (placement.outgoing) Alignment.End else Alignment.Start,
        ) {
            if (placement.showSenderAbove) {
                SenderNameLabel(
                    resolution = item.envelope.senderResolution,
                    nameColor = senderIdentityColor(
                        name = item.envelope.senderName,
                        theme = LocalAppTheme.current,
                        useDark = LocalUseDarkTheme.current,
                        highContrast = LocalHighContrast.current,
                    ),
                    modifier = Modifier.padding(
                        start = senderNameAboveBubbleStartDp(reserveAvatarColumn = true).dp,
                        bottom = senderNameAboveBubbleBottomDp().dp,
                    ),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (placement.outgoing) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.Bottom,
            ) {
                if (placement.showIncomingAvatarColumn) {
                    IncomingAvatarGutter(
                        showAvatar = item.grouping.isClusterEnd,
                        name = item.envelope.incomingAvatar?.name
                            ?: item.envelope.senderResolution.displayName.ifEmpty { item.envelope.senderName },
                        matchedContactID = item.envelope.incomingAvatar?.matchedContactID,
                    )
                }
                val colors = bubbleSurfaceColors(placement.fill)
                Surface(
                    color = colors.container,
                    contentColor = colors.content,
                    shape = RoundedCornerShape(BUBBLE_CORNER_DP.dp),
                    modifier = Modifier.widthIn(max = BUBBLE_MAX_WIDTH_DP.dp),
                ) {
                    BubbleContent(
                        item = item,
                        layout = layout,
                        outgoing = placement.outgoing,
                        translationPhase = translationPhase,
                        onTranslate = onTranslate,
                        onRetry = onRetry,
                        onLinkTap = onLinkTap,
                        imageResolver = imageResolver,
                        gifModelResolver = gifModelResolver,
                        onManualPreviewFetch = { onManualPreviewFetch(item.id) },
                        onRetryPreviewFetch = { onRetryPreviewFetch(item.id) },
                        onInlineImageLoadFailed = { onInlineImageLoadFailed(item.id) },
                    )
                }
            }
            layout.siblings.forEach { fragment ->
                BubbleSiblingFragment(
                    fragment = fragment,
                    onLinkTap = onLinkTap,
                    imageResolver = imageResolver,
                    onManualPreviewFetch = { onManualPreviewFetch(item.id) },
                    mapSnapshotResolver = mapSnapshotResolver,
                    onRequestMapSnapshot = onRequestMapSnapshot,
                    onRetryMapSnapshot = onRetryMapSnapshot,
                    onTapReaction = onTapReaction,
                    onOpenReactionDetails = onOpenReactionDetails,
                    modifier = Modifier.padding(start = placement.siblingIndentDp.dp),
                )
            }
        }
    }
}

@Composable
private fun BubbleContent(
    item: MessageItem,
    layout: FragmentLayout,
    outgoing: Boolean,
    translationPhase: MessageTranslationPhase?,
    onTranslate: () -> Unit,
    onRetry: () -> Unit,
    onLinkTap: (LinkTarget) -> Unit,
    imageResolver: PreviewImageResolver,
    gifModelResolver: (one.meshcore.shared.chat.ImageReference) -> Any?,
    onManualPreviewFetch: () -> Unit,
    onRetryPreviewFetch: () -> Unit,
    onInlineImageLoadFailed: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (translationPhase != null) {
            BubbleTranslationControl(phase = translationPhase, onTap = onTranslate)
        }
        layout.textPayload?.let { payload ->
            val showing = translationPhase as? MessageTranslationPhase.Showing
            MessageBodyText(
                formatted = if (showing != null) null else payload.formattedText,
                raw = showing?.translatedText ?: payload.raw,
                outgoing = outgoing,
                style = MaterialTheme.typography.bodyLarge,
                onLinkTap = onLinkTap,
            )
        }
        layout.inlineImage?.let { image ->
            InlineImageFragment(
                image = image,
                onManualFetch = onManualPreviewFetch,
                onRetryFetch = onRetryPreviewFetch,
                onLoadFailed = onInlineImageLoadFailed,
                imageResolver = imageResolver,
                gifModelResolver = gifModelResolver,
                modifier = Modifier.padding(top = FRAGMENT_GAP_DP.dp),
            )
        }
        // Incoming: send time then hop/path/region chips, leading. Outgoing: badges, time, status, trailing.
        if (outgoing) {
            OutgoingBubbleFooter(item = item, onRetry = onRetry, modifier = Modifier.align(Alignment.End))
        } else {
            IncomingBubbleFooter(item = item, modifier = Modifier.align(Alignment.Start))
        }
    }
}

@Composable
private fun BubbleSiblingFragment(
    fragment: MessageFragment,
    onLinkTap: (LinkTarget) -> Unit,
    imageResolver: PreviewImageResolver,
    onManualPreviewFetch: () -> Unit,
    mapSnapshotResolver: MapSnapshotImageResolver,
    onRequestMapSnapshot: (MapSnapshotRequest) -> Unit,
    onRetryMapSnapshot: (MapSnapshotRequest) -> Unit,
    onTapReaction: (String) -> Unit,
    onOpenReactionDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (fragment) {
        is MessageFragment.ReactionSummary ->
            ReactionBadgesRow(
                summary = fragment.summary,
                modifier = modifier
                    .offset(y = (-REACTION_OVERLAP_DP).dp),
                onTapReaction = onTapReaction,
                onOpenDetails = onOpenReactionDetails,
            )
        is MessageFragment.LinkPreview ->
            LinkPreviewFragment(
                state = fragment.state,
                imageResolver = imageResolver,
                onManualPreviewFetch = onManualPreviewFetch,
                modifier = modifier.padding(top = FRAGMENT_GAP_DP.dp),
            )
        is MessageFragment.MalwareWarning ->
            MalwareWarningCard(url = fragment.url, modifier = modifier.padding(top = FRAGMENT_GAP_DP.dp))
        is MessageFragment.MapPreview ->
            MapPreviewFragment(
                state = fragment.state,
                onOpenMap = { coordinate -> onLinkTap(coordinate) },
                modifier = modifier.padding(top = FRAGMENT_GAP_DP.dp),
                snapshotResolver = mapSnapshotResolver,
                onRequestSnapshot = onRequestMapSnapshot,
                onRetry = onRetryMapSnapshot,
            )
        is MessageFragment.Text, is MessageFragment.InlineImage -> Unit
    }
}

@Composable
private fun DayDivider(epochSeconds: Double) {
    // iOS MessageDayDividerView always prints the calendar date so the in-bubble
    // clock has a day anchor. Today/Yesterday stay on the Chats list only.
    val text = chatDayLabel(epochSeconds, formatter = LocalTimestampFormatter.current).absolute
    Row(
        // Its own merge boundary, so the day label stays a separate announcement instead of being
        // absorbed into the bubble's composed label.
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(DIVIDER_CHIP_CORNER_DP.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun NewMessagesDividerRow() {
    val spoken = stringResource(Res.string.chats_divider_new_messages_accessibility)
    Row(
        // The unread boundary is the one landmark a screen-reader user navigates the thread by, so it is
        // its own labelled node rather than text absorbed into the message below it.
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val dividerTint = MaterialTheme.colorScheme.primary
        HorizontalDivider(modifier = Modifier.weight(1f), color = dividerTint)
        Text(
            text = stringResource(Res.string.chats_divider_new_messages),
            style = MaterialTheme.typography.labelSmall,
            color = dividerTint,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = dividerTint)
    }
}

private const val BUBBLE_MAX_WIDTH_DP = 300
private const val BUBBLE_CORNER_DP = 16
private const val DIVIDER_CHIP_CORNER_DP = 10
private const val DIRECTION_GAP_DP = 14
private const val NEW_SENDER_GAP_DP = 8
private const val SAME_CLUSTER_PADDING_TOP_DP = 2

/** iOS `SenderNameMetrics.leadingIndent` past the bubble's rounded leading corner. */
internal const val SENDER_NAME_LEADING_INDENT_DP = 4

/** iOS `SenderNameMetrics.bubbleGap` between the name and the bubble. */
internal const val SENDER_NAME_BUBBLE_GAP_DP = 3

/** iOS `bubbleStackSpacing`; subtracted so the name-to-bubble gap is counted once. */
internal const val BUBBLE_STACK_SPACING_DP = 2

/** Leading inset for a sender name above an incoming bubble. */
internal fun senderNameAboveBubbleStartDp(reserveAvatarColumn: Boolean): Int =
    (if (reserveAvatarColumn) INCOMING_AVATAR_COLUMN_WIDTH_DP else 0) + SENDER_NAME_LEADING_INDENT_DP

/** Bottom inset under a sender name when the enclosing column has [BUBBLE_STACK_SPACING_DP]. */
internal fun senderNameAboveBubbleBottomDp(): Int = SENDER_NAME_BUBBLE_GAP_DP - BUBBLE_STACK_SPACING_DP
private const val FRAGMENT_GAP_DP = 6

/** iOS `ReactionsFragmentView` offset that pulls badges onto the bubble's bottom edge. */
internal const val REACTION_OVERLAP_DP = 6

/**
 * Top gap for a timeline row. A direction switch is the strongest break, then a new named sender;
 * same-cluster follow-ups stay tightly stacked. Mirrors iOS `UnifiedMessageBubble.paddingTop`.
 */
internal fun bubbleRowPaddingTop(grouping: GroupingFlags): Int = when {
    grouping.showDirectionGap -> DIRECTION_GAP_DP
    grouping.showSenderName -> NEW_SENDER_GAP_DP
    else -> SAME_CLUSTER_PADDING_TOP_DP
}
