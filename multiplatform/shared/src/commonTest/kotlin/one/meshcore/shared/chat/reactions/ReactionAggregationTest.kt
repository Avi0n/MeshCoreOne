package one.meshcore.shared.chat.reactions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers the badge-row and details-sheet aggregation over the summary string and reaction rows. */
class ReactionAggregationTest {
    @Test
    fun summaryItemsMarkOwnReaction() {
        val items = ReactionAggregation.summaryItems("👍:3,❤️:2", ownEmojis = setOf("❤️"))
        assertEquals(2, items.size)
        assertFalse(items[0].reactedByMe)
        assertTrue(items[1].reactedByMe)
        assertEquals(3, items[0].count)
    }

    @Test
    fun badgesTakeFirstThreeWithOverflowCount() {
        val badges = ReactionAggregation.badges("👍:5,❤️:3,😂:2,😮:1,😢:1,🎉:1")
        assertEquals(3, badges.visible.size)
        assertEquals(3, badges.overflowCount)
        assertEquals("👍", badges.visible.first().emoji)
    }

    @Test
    fun badgesHaveNoOverflowUnderThreshold() {
        val badges = ReactionAggregation.badges("👍:1,❤️:1")
        assertEquals(2, badges.visible.size)
        assertEquals(0, badges.overflowCount)
    }

    @Test
    fun badgesEmptyForNullSummary() {
        val badges = ReactionAggregation.badges(null)
        assertTrue(badges.visible.isEmpty())
        assertEquals(0, badges.overflowCount)
    }

    @Test
    fun detailsGroupByEmojiSortedByCountThenEarliest() {
        val reactions = listOf(
            ReactionDetail("❤️", "Bob", receivedAt = 30.0),
            ReactionDetail("❤️", "Carol", receivedAt = 40.0),
            ReactionDetail("👍", "Alice", receivedAt = 10.0),
            ReactionDetail("👍", "Dave", receivedAt = 50.0),
            ReactionDetail("😂", "Eve", receivedAt = 5.0),
        )
        val groups = ReactionAggregation.details(reactions)
        // 👍 and ❤️ tie at 2; 👍's earliest (10) precedes ❤️'s (30); 😂 has count 1 and sorts last.
        assertEquals(listOf("👍", "❤️", "😂"), groups.map { it.emoji })
        assertEquals(2, groups[0].count)
        assertEquals(listOf("Alice", "Dave"), groups[0].entries.map { it.senderName })
    }

    @Test
    fun detailsEmptyForNoReactions() {
        assertTrue(ReactionAggregation.details(emptyList()).isEmpty())
    }
}
