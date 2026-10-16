package one.meshcore.shared.chat.reactions

/** One emoji's aggregate for the badge row: its count and whether the local node reacted with it. */
data class ReactionSummaryItem(val emoji: String, val count: Int, val reactedByMe: Boolean)

/** The badge-row model: the leading [ReactionConstants.BADGE_PREFIX_COUNT] items plus an overflow count. */
data class ReactionBadges(val visible: List<ReactionSummaryItem>, val overflowCount: Int)

/** A single reactor in the details sheet: who reacted and when (epoch seconds). */
data class ReactionDetailEntry(val senderName: String, val receivedAt: Double)

/** One emoji's group in the details sheet: the emoji, its count, and its reactors (insertion order). */
data class ReactionDetailGroup(val emoji: String, val count: Int, val entries: List<ReactionDetailEntry>)

/** A persisted reaction as the aggregation reads it. */
data class ReactionDetail(val emoji: String, val senderName: String, val receivedAt: Double)

/**
 * Pure aggregation over the cached summary string and the full reaction rows, feeding the badge row and the
 * details sheet. Kept separate from persistence so the UI reads derived values without a store round-trip.
 * Mirrors the grouping in the Swift `ReactionBadgesView` and `ReactionDetailsSheet`.
 */
object ReactionAggregation {
    /**
     * Turns the cached `"👍:3,❤️:2"` summary into badge items, marking each emoji the local node reacted
     * with (from [ownEmojis]). The order is the summary's own count-descending order; no self highlight is
     * applied at render (the badges are uniform), but [ReactionSummaryItem.reactedByMe] drives the toggle.
     */
    fun summaryItems(summary: String?, ownEmojis: Set<String> = emptySet()): List<ReactionSummaryItem> =
        ReactionParser.parseSummary(summary).map { (emoji, count) ->
            ReactionSummaryItem(emoji = emoji, count = count, reactedByMe = emoji in ownEmojis)
        }

    /** Splits the summary items into the visible prefix and an overflow count for the `+N` badge. */
    fun badges(summary: String?, ownEmojis: Set<String> = emptySet()): ReactionBadges {
        val items = summaryItems(summary, ownEmojis)
        val visible = items.take(ReactionConstants.BADGE_PREFIX_COUNT)
        return ReactionBadges(visible = visible, overflowCount = (items.size - visible.size).coerceAtLeast(0))
    }

    /**
     * Groups reaction rows by emoji, ordered by count descending then by earliest `receivedAt` ascending,
     * each group listing its reactors. The first group is the one the details sheet auto-selects.
     */
    fun details(reactions: List<ReactionDetail>): List<ReactionDetailGroup> = reactions.groupBy { it.emoji }
        .map { (emoji, rows) ->
            ReactionDetailGroup(
                emoji = emoji,
                count = rows.size,
                entries = rows.map { ReactionDetailEntry(it.senderName, it.receivedAt) },
            )
        }
        .sortedWith(
            compareByDescending<ReactionDetailGroup> { it.count }
                .thenBy { group -> group.entries.minOf { it.receivedAt } },
        )
}
