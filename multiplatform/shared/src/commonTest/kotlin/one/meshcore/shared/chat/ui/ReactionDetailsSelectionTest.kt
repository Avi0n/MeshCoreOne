package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Badge tap pre-selects that emoji's who-reacted tab. iOS
 * `ReactionDetailsSheet.resolvedSelection`.
 */
class ReactionDetailsSelectionTest {
    @Test
    fun preferredEmojiIsSelectedWhenPresent() {
        assertEquals(
            "❤️",
            resolvedReactionSelection(preferred = "❤️", available = listOf("👍", "❤️", "😂")),
        )
    }

    @Test
    fun missingPreferredFallsBackToFirst() {
        assertEquals(
            "👍",
            resolvedReactionSelection(preferred = "🎉", available = listOf("👍", "❤️")),
        )
    }

    @Test
    fun nilPreferredUsesFirst() {
        assertEquals(
            "👍",
            resolvedReactionSelection(preferred = null, available = listOf("👍", "❤️")),
        )
    }

    @Test
    fun emptyGroupsYieldNull() {
        assertNull(resolvedReactionSelection(preferred = "👍", available = emptyList()))
    }
}
