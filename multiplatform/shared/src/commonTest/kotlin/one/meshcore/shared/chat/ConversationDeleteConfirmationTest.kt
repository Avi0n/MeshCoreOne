package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which conversation-list deletes ask first. Mirrors the Swift `ChatListActions.handleDeleteConversation`:
 * a room raises the leave-room alert, a direct chat and a channel run straight away.
 */
class ConversationDeleteConfirmationTest {
    @Test
    fun roomDeleteAsksFirst() {
        // Leaving a room deletes every room message and removes the contact, so a mis-tap must not run it.
        assertTrue(Conversation.Room(ChatFixtures.room(name = "Ops Room")).requiresDeleteConfirmation)
    }

    @Test
    fun directAndChannelDeletesDoNotAsk() {
        assertFalse(Conversation.Direct(ChatFixtures.contact(name = "Alice")).requiresDeleteConfirmation)
        assertFalse(Conversation.Channel(ChatFixtures.channel(name = "General")).requiresDeleteConfirmation)
    }
}
