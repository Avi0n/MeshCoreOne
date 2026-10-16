package one.meshcore.shared.notification

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationTapRoutingTest {
    @Test
    fun channelTapUsesTheNotificationRadioEvenWhenAnotherRadioIsConnected() {
        val notificationRadio = Uuid.normalized(RADIO_A)
        val otherRadio = Uuid.normalized(RADIO_B)
        val channels = listOf(
            channel(id = Uuid.normalized(CHANNEL_A), radio = notificationRadio, index = 2),
            channel(id = Uuid.normalized(CHANNEL_B), radio = otherRadio, index = 2),
        )
        val id = channelRowIdForNotification(
            channelsOnNotificationRadio = channels.filter { it.radioID == notificationRadio },
            channelIndex = 2,
        )
        assertEquals(Uuid.normalized(CHANNEL_A), id)
    }

    @Test
    fun channelTapReturnsNullWhenThatRadioHasNoMatchingSlot() {
        val radio = Uuid.normalized(RADIO_A)
        val channels = listOf(channel(id = Uuid.normalized(CHANNEL_A), radio = radio, index = 0))
        assertNull(channelRowIdForNotification(channels, channelIndex = 7))
    }

    @Test
    fun markReadDmUsesTheContactPartitionWithoutALiveRadio() {
        val contactId = Uuid.normalized(CONTACT_A)
        val contactRadio = Uuid.normalized(RADIO_A)
        val conversation = markReadChatConversation(
            ConversationTarget.Direct(contactId),
            contactRadioId = contactRadio,
        )
        assertEquals(ChatConversationID.dm(contactRadio, contactId), conversation)
        assertNull(
            markReadChatConversation(ConversationTarget.Direct(contactId), contactRadioId = null),
            "a missing contact row cannot invent a radio the way last-radio would",
        )
    }

    @Test
    fun markReadChannelUsesTheNotificationRadioWhenLastRadioIsNull() {
        val radio = Uuid.normalized(RADIO_B)
        val conversation = markReadChatConversation(
            ConversationTarget.Channel(radio, channelIndex = 3),
            contactRadioId = null,
        )
        assertEquals(ChatConversationID.channel(radio, 3), conversation)
    }

    private fun channel(id: Uuid, radio: Uuid, index: Int): ChannelDto = ChannelDto(
        id = id,
        radioID = radio,
        index = index,
        name = "ch$index",
        secret = ByteArray(SECRET_BYTES),
        isEnabled = true,
        unreadCount = 0,
    )

    private companion object {
        const val RADIO_A = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
        const val RADIO_B = "BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB"
        const val CHANNEL_A = "C0000000-0000-0000-0000-00000000000A"
        const val CHANNEL_B = "C0000000-0000-0000-0000-00000000000B"
        const val CONTACT_A = "D0000000-0000-0000-0000-00000000000A"
        const val SECRET_BYTES = 16
    }
}
