package one.meshcore.shared.room.ui

/** Top padding for same-cluster follow-ups (no timestamp). iOS `sameClusterPaddingTop`. */
private const val SAME_CLUSTER_PADDING_TOP_DP = 2

/** iOS `RoomMessageBubble` top padding: 2 pt on clustered follow-ups, 0 when a timestamp starts the group. */
internal fun roomSameClusterTopPaddingDp(showTimestamp: Boolean): Int =
    if (showTimestamp) 0 else SAME_CLUSTER_PADDING_TOP_DP
