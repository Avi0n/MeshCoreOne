package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto

/**
 * Reorders messages within narrow same-sender clusters by sender timestamp, restoring send order when
 * mesh relay delivered a same-sender burst out of order.
 *
 * The input must already be sorted by [MessageDto.sortDate] (the display sort key). A cluster extends
 * while consecutive messages are the same sender and their `sortDate` gap is within
 * [ChatConstants.SAME_SENDER_REORDER_WINDOW_SECONDS]; a cluster of more than one is re-sorted by
 * `timestamp` ascending, tie-broken by `createdAt`. The window is measured on `sortDate` — the axis the
 * input is sorted by — so it stays non-negative and cannot pull together rows far apart on the display
 * axis. "Same sender" is same direction and same channel/DM kind; for channel messages the non-null
 * `senderNodeName` must also match (null names never cluster).
 */
fun reorderSameSenderClusters(messages: List<MessageDto>): List<MessageDto> {
    if (messages.size <= 1) return messages

    val result = messages.toMutableList()
    var clusterStart = 0

    while (clusterStart < result.size) {
        var clusterEnd = clusterStart + 1
        while (clusterEnd < result.size) {
            val gap = result[clusterEnd].sortDate - result[clusterEnd - 1].sortDate
            if (!isSameSender(result[clusterEnd], result[clusterEnd - 1]) ||
                gap > ChatConstants.SAME_SENDER_REORDER_WINDOW_SECONDS
            ) {
                break
            }
            clusterEnd++
        }

        if (clusterEnd - clusterStart > 1) {
            val sorted = result.subList(clusterStart, clusterEnd)
                .sortedWith(compareBy({ it.timestamp }, { it.createdAt }))
            for (offset in sorted.indices) {
                result[clusterStart + offset] = sorted[offset]
            }
        }

        clusterStart = clusterEnd
    }

    return result
}

private fun isSameSender(a: MessageDto, b: MessageDto): Boolean {
    if (a.direction != b.direction) return false
    if (a.isChannelMessage != b.isChannelMessage) return false

    // Channel messages carry a human-readable sender name (not a stable id); a null name is "unknown"
    // and never clusters, and two distinct users sharing a name would be falsely clustered.
    if (a.isChannelMessage) {
        val nameA = a.senderNodeName ?: return false
        val nameB = b.senderNodeName ?: return false
        return nameA == nameB
    }

    // Same-direction DMs share the same sender.
    return true
}
