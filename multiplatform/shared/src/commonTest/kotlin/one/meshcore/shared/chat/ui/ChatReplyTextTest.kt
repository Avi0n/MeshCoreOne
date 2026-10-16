package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.text.MentionUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the chat reply-text choice so a drift from iOS `ChatConversationView.handleReply` fails here
 * without a device. Channel mentions use the wire sender name (Unknown when missing), and quoting
 * reads the stored payload so a leading `@[…]` can still be stripped.
 */
class ChatReplyTextTest {
    @Test
    fun aChannelReplyMentionsTheWireSenderName() {
        assertEquals("Ridge", chatReplyMentionName(true, "Ridge", contactName = "Saved", unknownLabel = UNKNOWN))
    }

    @Test
    fun aPrefixOnlyChannelReplyMentionsUnknownNotAResolvedContact() {
        assertEquals(
            UNKNOWN,
            chatReplyMentionName(true, senderNodeName = null, contactName = "Saved", unknownLabel = UNKNOWN),
        )
        assertEquals(
            UNKNOWN,
            chatReplyMentionName(true, senderNodeName = "", contactName = "Saved", unknownLabel = UNKNOWN),
        )
    }

    @Test
    fun aDirectReplyMentionsTheContactName() {
        assertEquals(
            "Alice",
            chatReplyMentionName(false, senderNodeName = "Ridge", contactName = "Alice", unknownLabel = UNKNOWN),
        )
    }

    @Test
    fun defaultAppendsAMentionAndKeepsTypedText() {
        val draft = "on my way"
        val result =
            chatReplyText(mentionName = "Alice", messageText = "hello", currentDraft = draft, replyWithQuote = false)
        assertTrue(result.startsWith(draft), "typed composer text must be preserved")
        assertTrue(result.contains(MentionUtilities.createMention("Alice")))
    }

    @Test
    fun quotingUsesTheStoredPayloadSoALeadingMentionIsStripped() {
        val stored = "@[Bob] 0123456789X"
        val result = chatReplyText(
            mentionName = "Alice",
            messageText = stored,
            currentDraft = "ignored",
            replyWithQuote = true,
        )
        assertEquals("@[Alice]\n>0123456789..\n", result)
    }

    private companion object {
        const val UNKNOWN = "Unknown"
    }
}
