package one.meshcore.shared.room.ui

import one.meshcore.shared.chat.text.MentionUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the room reply-text choice so a drift from iOS RoomConversationView.handleReply (quote vs bare
 * mention, preserving typed text) fails here without a device. The chat surface honors the same preference;
 * before this the room path quoted unconditionally, the opposite of the default.
 */
class RoomReplyTextTest {
    @Test
    fun defaultAppendsAMentionAndKeepsTypedText() {
        val draft = "on my way"
        val result = roomReplyText(name = "Alice", messageText = "hello", currentDraft = draft, replyWithQuote = false)
        assertTrue(result.startsWith(draft), "typed composer text must be preserved")
        assertTrue(result.contains(MentionUtilities.createMention("Alice")))
    }

    @Test
    fun quotingReplacesTheDraftWithAQuotedPreview() {
        val result = roomReplyText(
            name = "Alice",
            messageText = "hello there friend",
            currentDraft = "ignored draft",
            replyWithQuote = true,
        )
        assertEquals(MentionUtilities.buildReplyText("Alice", "hello there friend"), result)
        assertTrue(result.contains(">"), "quoted reply carries a quote marker")
    }
}
