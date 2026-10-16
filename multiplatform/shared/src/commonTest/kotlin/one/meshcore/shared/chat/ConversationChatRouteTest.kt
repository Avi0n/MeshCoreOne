package one.meshcore.shared.chat

import one.meshcore.shared.navigation.ChatRoute
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationChatRouteTest {
    @Test
    fun aDirectRowMatchesTheContactRoute() {
        val contact = ChatFixtures.contact(name = "Alice")
        val conversation = Conversation.Direct(contact)
        assertTrue(conversation.matchesChatRoute(ChatRoute.Direct(contact.id.value)))
        assertFalse(conversation.matchesChatRoute(ChatRoute.Direct("other")))
        assertFalse(conversation.matchesChatRoute(ChatRoute.Channel(contact.id.value)))
    }

    @Test
    fun aChannelRowMatchesTheChannelRoute() {
        val channel = ChatFixtures.channel(name = "Ops")
        val conversation = Conversation.Channel(channel)
        assertTrue(conversation.matchesChatRoute(ChatRoute.Channel(channel.id.value)))
        assertFalse(conversation.matchesChatRoute(ChatRoute.Direct(channel.id.value)))
    }

    @Test
    fun aRoomRowMatchesTheSessionRoute() {
        val room = ChatFixtures.room(name = "HQ")
        val conversation = Conversation.Room(room)
        assertTrue(conversation.matchesChatRoute(ChatRoute.Room(room.id.value)))
        assertFalse(conversation.matchesChatRoute(ChatRoute.Channel(room.id.value)))
    }
}
