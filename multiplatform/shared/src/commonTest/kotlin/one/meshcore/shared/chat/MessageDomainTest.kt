package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers the derived accessors on `MessageStatus` and `MessageDto` (the Swift computed-property parity). */
class MessageDomainTest {
    @Test
    fun statusIsPendingForPendingAndSending() {
        assertTrue(MessageStatus.PENDING.isPending)
        assertTrue(MessageStatus.SENDING.isPending)
        assertFalse(MessageStatus.SENT.isPending)
        assertFalse(MessageStatus.DELIVERED.isPending)
    }

    @Test
    fun statusHasFailedOnlyForFailed() {
        assertTrue(MessageStatus.FAILED.hasFailed)
        assertFalse(MessageStatus.SENT.hasFailed)
    }

    @Test
    fun isOutgoingReflectsDirection() {
        assertTrue(ChatFixtures.message(direction = MessageDirection.OUTGOING).isOutgoing)
        assertFalse(ChatFixtures.message(direction = MessageDirection.INCOMING).isOutgoing)
    }

    @Test
    fun messagePendingAndFailedDelegateToStatus() {
        assertTrue(ChatFixtures.message(status = MessageStatus.PENDING).isPending)
        assertTrue(ChatFixtures.message(status = MessageStatus.FAILED).hasFailed)
        assertFalse(ChatFixtures.message(status = MessageStatus.SENT).isPending)
    }

    @Test
    fun senderDateIsTheTimestampInSeconds() {
        val message = ChatFixtures.message(timestamp = 1_700_000_000)
        assertEquals(1_700_000_000.0, message.senderDateEpochSeconds)
    }

    @Test
    fun wireSentDatePrefersSenderTimestampWhenCorrected() {
        val corrected = ChatFixtures.message(
            timestamp = 1_700_000_000,
            senderTimestamp = 100,
            timestampCorrected = true,
        )
        assertEquals(100.0, corrected.wireSentDateEpochSeconds)

        val uncorrected = ChatFixtures.message(timestamp = 1_700_000_000, senderTimestamp = null)
        assertEquals(1_700_000_000.0, uncorrected.wireSentDateEpochSeconds)
    }

    @Test
    fun reactionTimestampPrefersSenderTimestamp() {
        assertEquals(100, ChatFixtures.message(timestamp = 5000, senderTimestamp = 100).reactionTimestamp)
        assertEquals(5000, ChatFixtures.message(timestamp = 5000, senderTimestamp = null).reactionTimestamp)
    }
}
