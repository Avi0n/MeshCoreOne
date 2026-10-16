package one.meshcore.shared.notification

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The notification reply / mark-read / body-tap routers all decode a [ConversationKey] back to its target
 * through [ConversationKey.target]. These pin the round-trip and the malformed-key rejections so a tap can
 * never act on the wrong conversation.
 */
class ConversationKeyTargetTest {
    private val radio = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    private val contact = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
    private val session = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")

    @Test
    fun `direct key decodes to its contact`() {
        val target = ConversationKey.direct(contact).target()
        assertEquals(ConversationTarget.Direct(contact), target)
    }

    @Test
    fun `channel key decodes to its radio and index`() {
        val target = ConversationKey.channel(radio, 7).target()
        assertEquals(ConversationTarget.Channel(radio, 7), target)
    }

    @Test
    fun `channel index zero decodes`() {
        val target = ConversationKey.channel(radio, 0).target()
        assertEquals(ConversationTarget.Channel(radio, 0), target)
    }

    @Test
    fun `room key decodes to its session`() {
        val target = ConversationKey.room(session).target()
        assertEquals(ConversationTarget.Room(session), target)
    }

    @Test
    fun `an unknown prefix decodes to null`() {
        assertNull(ConversationKey("map-1-2").target())
    }

    @Test
    fun `a channel key without an index decodes to null`() {
        assertNull(ConversationKey("channel-${radio.value}").target())
    }

    @Test
    fun `a channel key with a non-numeric index decodes to null`() {
        assertNull(ConversationKey("channel-${radio.value}-x").target())
    }

    @Test
    fun `a dm reaction taps into the contact conversation`() {
        val key = reaction(contactID = contact, channelIndex = null).conversationKey()
        assertEquals(ConversationTarget.Direct(contact), key?.target())
    }

    @Test
    fun `a channel reaction taps into the channel conversation`() {
        val key = reaction(contactID = null, channelIndex = 3).conversationKey()
        assertEquals(ConversationTarget.Channel(radio, 3), key?.target())
    }

    @Test
    fun `a reaction with neither target has no tap key`() {
        assertNull(reaction(contactID = null, channelIndex = null).conversationKey())
    }

    @Test
    fun `only a direct conversation carries failed-reply actions`() {
        assertTrue(ConversationKey.direct(contact).carriesDirectMessageActions())
        assertFalse(ConversationKey.channel(radio, 1).carriesDirectMessageActions())
        assertFalse(ConversationKey.room(session).carriesDirectMessageActions())
    }

    private fun reaction(contactID: Uuid?, channelIndex: Int?) = ReactionNotification(
        reactorName = "Bob",
        emoji = "👍",
        messagePreview = "hi",
        messageID = session,
        contactID = contactID,
        channelIndex = channelIndex,
        radioID = radio,
        playSound = true,
    )
}
