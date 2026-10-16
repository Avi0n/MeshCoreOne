package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the pure PendingSend envelope conversion: round-trip and the discriminator guards (spec §1.5). */
class PendingSendEnvelopeTest {
    private val radioID = ChatFixtures.RADIO
    private val rowID = ChatFixtures.freshUuid()
    private val messageID = ChatFixtures.freshUuid()
    private val contactID = ChatFixtures.freshUuid()
    private val enqueuedAt = ChatFixtures.BASE_EPOCH_SECONDS

    @Test
    fun dmEnvelopeStoresSentinelTextAndRoundTrips() {
        val envelope = DirectMessageEnvelope(messageID = messageID, contactID = contactID, isResend = true)
        val dto = envelope.toPendingSendDto(
            id = rowID,
            radioID = radioID,
            sequence = 1,
            enqueuedAtEpochSeconds = enqueuedAt,
            attemptCount = 0,
        )
        assertEquals(PendingSendKind.DM, dto.kind)
        assertEquals(contactID, dto.contactID)
        assertEquals("", dto.messageText)
        assertEquals(0, dto.messageTimestamp)
        assertNull(dto.localNodeName)
        assertEquals(envelope, dto.toDirectMessageEnvelope())
        assertEquals(envelope, dto.toEnvelope())
    }

    @Test
    fun channelEnvelopeStoresFieldsVerbatimAndRoundTrips() {
        val envelope = ChannelMessageEnvelope(
            messageID = messageID,
            channelIndex = 3,
            isResend = true,
            messageText = "hi there",
            messageTimestamp = 4242,
            localNodeName = "Me",
        )
        val dto = envelope.toPendingSendDto(
            id = rowID,
            radioID = radioID,
            sequence = 7,
            enqueuedAtEpochSeconds = enqueuedAt,
            attemptCount = 2,
        )
        assertEquals(PendingSendKind.CHANNEL, dto.kind)
        assertEquals(3, dto.channelIndex)
        assertEquals("hi there", dto.messageText)
        assertEquals(4242, dto.messageTimestamp)
        assertEquals("Me", dto.localNodeName)
        assertEquals(envelope, dto.toChannelMessageEnvelope())
        assertEquals(envelope, dto.toEnvelope())
    }

    @Test
    fun dmRowDoesNotConvertToChannelEnvelope() {
        val dto = DirectMessageEnvelope(messageID = messageID, contactID = contactID)
            .toPendingSendDto(id = rowID, radioID = radioID, sequence = 1, enqueuedAtEpochSeconds = enqueuedAt)
        assertNull(dto.toChannelMessageEnvelope())
    }

    @Test
    fun channelRowDoesNotConvertToDirectEnvelope() {
        val dto = ChannelMessageEnvelope(messageID = messageID, channelIndex = 0)
            .toPendingSendDto(id = rowID, radioID = radioID, sequence = 1, enqueuedAtEpochSeconds = enqueuedAt)
        assertNull(dto.toDirectMessageEnvelope())
    }

    @Test
    fun dmRowMissingContactDoesNotConvert() {
        val malformed = PendingSendDto(
            id = rowID,
            radioID = radioID,
            messageID = messageID,
            kind = PendingSendKind.DM,
            sequence = 1,
            enqueuedAtEpochSeconds = enqueuedAt,
            contactID = null,
        )
        assertNull(malformed.toDirectMessageEnvelope())
        assertNull(malformed.toEnvelope())
    }

    @Test
    fun toEnvelopeDispatchesByKind() {
        val dm = DirectMessageEnvelope(messageID = messageID, contactID = contactID)
            .toPendingSendDto(id = rowID, radioID = radioID, sequence = 1, enqueuedAtEpochSeconds = enqueuedAt)
        val channel = ChannelMessageEnvelope(messageID = messageID, channelIndex = 1)
            .toPendingSendDto(id = rowID, radioID = radioID, sequence = 2, enqueuedAtEpochSeconds = enqueuedAt)
        assertTrue(dm.toEnvelope() is DirectMessageEnvelope)
        assertTrue(channel.toEnvelope() is ChannelMessageEnvelope)
    }
}
