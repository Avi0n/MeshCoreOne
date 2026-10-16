package one.meshcore.shared.chat

import one.meshcore.shared.model.NotificationLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers the `Conversation` uniform accessors and `ChatFilter.filtered` (spec §10.1, §10.3). */
class ConversationFilterTest {
    @Test
    fun directConversationMapsMuteToNotificationLevel() {
        val muted = Conversation.Direct(ChatFixtures.contact(name = "Alice", isMuted = true))
        val unmuted = Conversation.Direct(ChatFixtures.contact(name = "Bob", isMuted = false))
        assertEquals(NotificationLevel.MUTED, muted.notificationLevel)
        assertTrue(muted.isMuted)
        assertEquals(NotificationLevel.ALL, unmuted.notificationLevel)
        assertFalse(unmuted.isMuted)
    }

    @Test
    fun directDisplayNamePrefersNickname() {
        val withNickname = Conversation.Direct(ChatFixtures.contact(name = "Alice", nickname = "Ally"))
        val withoutNickname = Conversation.Direct(ChatFixtures.contact(name = "Bob"))
        assertEquals("Ally", withNickname.displayName)
        assertEquals("Bob", withoutNickname.displayName)
    }

    @Test
    fun channelAndRoomAccessorsPassThrough() {
        val channel = Conversation.Channel(
            ChatFixtures.channel(
                name = "General",
                notificationLevel = NotificationLevel.MENTIONS_ONLY,
                isPinned = true,
            ),
        )
        val room = Conversation.Room(ChatFixtures.room(name = "Ops", unreadCount = 4))
        assertEquals("General", channel.displayName)
        assertEquals(NotificationLevel.MENTIONS_ONLY, channel.notificationLevel)
        assertTrue(channel.isPinned)
        assertFalse(channel.isMuted)
        assertEquals(4, room.unreadCount)
    }

    @Test
    fun filterAllReturnsEverything() {
        val conversations = sampleConversations()
        assertEquals(conversations.size, conversations.filtered(ChatFilter.ALL, "", ::unnamedChannelName).size)
    }

    @Test
    fun filterUnreadKeepsOnlyUnmutedWithUnread() {
        val unreadChannel = Conversation.Channel(ChatFixtures.channel(name = "Hot", unreadCount = 3))
        val mutedUnread = Conversation.Direct(ChatFixtures.contact(name = "Muted", isMuted = true, unreadCount = 5))
        val readContact = Conversation.Direct(ChatFixtures.contact(name = "Quiet", unreadCount = 0))
        val result = listOf(unreadChannel, mutedUnread, readContact)
            .filtered(ChatFilter.UNREAD, "", ::unnamedChannelName)
        assertEquals(listOf(unreadChannel.id), result.map { it.id })
    }

    @Test
    fun kindFiltersKeepOnlyTheirCase() {
        val conversations = sampleConversations()
        assertTrue(
            conversations.filtered(ChatFilter.DIRECT_MESSAGES, "", ::unnamedChannelName).all {
                it is Conversation.Direct
            },
        )
        assertTrue(
            conversations.filtered(ChatFilter.CHANNELS, "", ::unnamedChannelName).all {
                it is Conversation.Channel
            },
        )
        assertTrue(
            conversations.filtered(ChatFilter.ROOMS, "", ::unnamedChannelName).all {
                it is Conversation.Room
            },
        )
    }

    @Test
    fun searchIgnoresFilterAndMatchesDisplayNameCaseInsensitively() {
        val alice = Conversation.Direct(ChatFixtures.contact(name = "Alice"))
        val bob = Conversation.Direct(ChatFixtures.contact(name = "Bob"))
        // ROOMS filter would normally exclude both direct rows, but a non-empty search overrides it.
        val result = listOf(alice, bob).filtered(ChatFilter.ROOMS, "ALI", ::unnamedChannelName)
        assertEquals(listOf(alice.id), result.map { it.id })
    }

    @Test
    fun searchMatchesDisplayNameIgnoringDiacritics() {
        val jose = Conversation.Direct(ChatFixtures.contact(name = "José"))
        val bob = Conversation.Direct(ChatFixtures.contact(name = "Bob"))
        val result = listOf(jose, bob).filtered(ChatFilter.ALL, "jose", ::unnamedChannelName)
        assertEquals(listOf(jose.id), result.map { it.id })
    }

    @Test
    fun searchMatchesLocalizedUnnamedChannelFallback() {
        val unnamed = Conversation.Channel(ChatFixtures.channel(name = "", index = 4))
        val named = Conversation.Channel(ChatFixtures.channel(name = "Ops"))
        val result = listOf(unnamed, named).filtered(
            ChatFilter.ALL,
            searchText = "canal 4",
            unnamedChannelName = ::unnamedChannelName,
        )
        assertEquals(listOf(unnamed.id), result.map { it.id })
    }

    private fun sampleConversations(): List<Conversation> = listOf(
        Conversation.Direct(ChatFixtures.contact(name = "Alice")),
        Conversation.Channel(ChatFixtures.channel(name = "General")),
        Conversation.Room(ChatFixtures.room(name = "Ops")),
    )

    private fun unnamedChannelName(index: Int): String = "Canal $index"
}
