package one.meshcore.shared.chat

/**
 * Per-row grouping signals: whether to show the timestamp, a direction gap, the sender name, the
 * new-messages divider, and the day divider. Fully structural equality. Mirrors the Swift `GroupingFlags`.
 * The pair-wise flags are computed by [computeDisplayFlags]; the thread layer assigns the positional
 * [showNewMessagesDivider].
 */
data class GroupingFlags(
    val showTimestamp: Boolean,
    val showDirectionGap: Boolean,
    val showSenderName: Boolean,
    val showNewMessagesDivider: Boolean,
    val showDayDivider: Boolean = false,
    val isClusterEnd: Boolean = false,
)
