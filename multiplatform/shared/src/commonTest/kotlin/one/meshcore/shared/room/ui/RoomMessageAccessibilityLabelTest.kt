package one.meshcore.shared.room.ui

import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_status_delivered
import one.meshcore.shared.resources.chats_message_status_failed
import one.meshcore.shared.resources.chats_message_status_retrying
import one.meshcore.shared.resources.chats_message_status_sending
import one.meshcore.shared.resources.chats_message_status_sent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomMessageAccessibilityLabelTest {
    @Test
    fun incomingLeadsWithAuthor() {
        assertEquals(
            "Alice: hello",
            roomMessageAccessibilityLabel(author = "Alice", outgoing = false, body = "hello", status = null),
        )
    }

    @Test
    fun incomingWithoutAuthorIsJustTheBody() {
        assertEquals(
            "hello",
            roomMessageAccessibilityLabel(author = "", outgoing = false, body = "hello", status = null),
        )
    }

    @Test
    fun outgoingAppendsDeliveryStatus() {
        assertEquals(
            "hello, Delivered",
            roomMessageAccessibilityLabel(
                author = "",
                outgoing = true,
                body = "hello",
                status = "Delivered",
            ),
        )
    }

    @Test
    fun showingTranslationReplacesTheSpokenBody() {
        assertEquals(
            "Alice: Translated: Hello everyone",
            roomMessageAccessibilityLabel(
                author = "Alice",
                outgoing = false,
                body = "Translated: Hello everyone",
                status = null,
            ),
        )
    }

    @Test
    fun outgoingFailedShowsRetryCaptionAndGlyph() {
        assertTrue(roomOutgoingShowsRetry(MessageStatus.FAILED))
        assertTrue(roomOutgoingShowsFailedGlyph(MessageStatus.FAILED))
        assertEquals(
            Res.string.chats_message_status_failed,
            roomOutgoingStatusCaption(MessageStatus.FAILED),
        )
    }

    @Test
    fun outgoingSendingShowsCaptionWithoutRetry() {
        assertFalse(roomOutgoingShowsRetry(MessageStatus.SENDING))
        assertFalse(roomOutgoingShowsFailedGlyph(MessageStatus.SENDING))
        assertEquals(
            Res.string.chats_message_status_sending,
            roomOutgoingStatusCaption(MessageStatus.SENDING),
        )
    }

    @Test
    fun outgoingPendingUsesSendingCaption() {
        assertEquals(
            Res.string.chats_message_status_sending,
            roomOutgoingStatusCaption(MessageStatus.PENDING),
        )
        assertFalse(roomOutgoingShowsRetry(MessageStatus.PENDING))
    }

    @Test
    fun outgoingSentAndDeliveredShowTheirCaptions() {
        assertEquals(Res.string.chats_message_status_sent, roomOutgoingStatusCaption(MessageStatus.SENT))
        assertEquals(
            Res.string.chats_message_status_delivered,
            roomOutgoingStatusCaption(MessageStatus.DELIVERED),
        )
        assertFalse(roomOutgoingShowsRetry(MessageStatus.SENT))
        assertFalse(roomOutgoingShowsRetry(MessageStatus.DELIVERED))
        assertFalse(roomOutgoingShowsFailedGlyph(MessageStatus.DELIVERED))
    }

    @Test
    fun outgoingRetryingShowsRetryingCaptionWithoutRetryButton() {
        assertEquals(
            Res.string.chats_message_status_retrying,
            roomOutgoingStatusCaption(MessageStatus.RETRYING),
        )
        assertFalse(roomOutgoingShowsRetry(MessageStatus.RETRYING))
        assertFalse(roomOutgoingShowsFailedGlyph(MessageStatus.RETRYING))
    }
}
