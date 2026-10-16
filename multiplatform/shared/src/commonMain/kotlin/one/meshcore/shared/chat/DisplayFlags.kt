package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import kotlin.math.abs

/**
 * Per-message display grouping signals. Mirrors the Swift `ChatViewModel.DisplayFlags`. The
 * new-messages divider flag is not here: it is a positional decision the thread view assigns
 * separately (spec §9.4), not a function of the adjacent pair.
 */
data class DisplayFlags(
    val showTimestamp: Boolean,
    val showDirectionGap: Boolean,
    val showSenderName: Boolean,
    val showDayDivider: Boolean,
    /** Incoming channel cluster-end: the row that carries the sender avatar. DMs stay false. */
    val isClusterEnd: Boolean = false,
)

/**
 * Whether [later] continues an incoming channel cluster from [earlier]: both channel, both incoming,
 * same non-null sender name, gap ≤ 300s. Empty names still cluster; missing names do not.
 * Mirrors iOS `ChatMessageBakeState.incomingClusterContinues`.
 */
fun incomingClusterContinues(earlier: MessageDto, later: MessageDto): Boolean {
    val incomingChannelPair = earlier.isChannelMessage &&
        later.isChannelMessage &&
        !earlier.isOutgoing &&
        !later.isOutgoing
    val withinGroupGap = abs(later.timestamp - earlier.timestamp) <= ChatConstants.MESSAGE_GROUPING_GAP_SECONDS
    val currentName = later.senderNodeName
    val sameNamedSender = currentName != null && currentName == earlier.senderNodeName
    return incomingChannelPair && withinGroupGap && sameNamedSender
}

/**
 * Computes the grouping flags for [message] given its predecessor. Keys on send time
 * ([MessageDto.senderDateEpochSeconds]), not the `sortDate` sort key: under block-at-reconnect a
 * drained batch shares one `sortDate`, so grouping on it would collapse every in-block divider, while
 * send time keeps honest separators inside the block.
 */
fun computeDisplayFlags(message: MessageDto, previous: MessageDto?, next: MessageDto? = null): DisplayFlags {
    val continuesToNext = next?.let { incomingClusterContinues(message, it) } == true
    val isClusterEnd = message.isChannelMessage && !message.isOutgoing && !continuesToNext

    if (previous == null) {
        return DisplayFlags(
            showTimestamp = true,
            showDirectionGap = false,
            showSenderName = true,
            showDayDivider = true,
            isClusterEnd = isClusterEnd,
        )
    }

    val timeGap = abs(message.timestamp - previous.timestamp)
    val dayChanged = !isSameCalendarDay(message.senderDateEpochSeconds, previous.senderDateEpochSeconds)

    val showTimestamp = timeGap > ChatConstants.MESSAGE_GROUPING_GAP_SECONDS
    val showDirectionGap = message.direction != previous.direction

    val showSenderName = when {
        // Direct messages never show a sender name; keep true so each row still
        // gets the cluster gap used for new-sender spacing.
        message.contactID != null -> true
        // Outgoing channel rows never show a name. Side-switch spacing comes from
        // showDirectionGap instead.
        message.isOutgoing -> false
        previous.isOutgoing || timeGap > ChatConstants.MESSAGE_GROUPING_GAP_SECONDS -> true
        message.senderNodeName != null && previous.senderNodeName != null ->
            message.senderNodeName != previous.senderNodeName
        // No sender name on either side; show it to be safe.
        else -> true
    }

    return DisplayFlags(
        showTimestamp = showTimestamp,
        showDirectionGap = showDirectionGap,
        showSenderName = showSenderName,
        showDayDivider = dayChanged,
        isClusterEnd = isClusterEnd,
    )
}
