package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.MessageFooter
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The spoken status a bubble resolves to. A DM `.sent` is only a queue-for-TX acknowledgement and can still
 * flip to failed, so it must read as sending; a channel `.sent` has no acknowledgement to wait for and is
 * terminal success. Mirrors the Swift `MessageStatusText.text(for:)` branch on `isChannelMessage`.
 */
class MessageStatusLabelTest {
    private fun footer(
        status: MessageStatus,
        isChannelMessage: Boolean,
        sendCount: Int = 1,
        maxRetryAttempts: Int = 0,
    ) = MessageFooter(
        showStatusRow = true,
        status = status,
        isChannelMessage = isChannelMessage,
        heardRepeats = 0,
        retryAttempt = 0,
        maxRetryAttempts = maxRetryAttempts,
        sendCount = sendCount,
        sendTimeToShowEpochSeconds = null,
        sendTimeWasCorrected = false,
        showHop = false,
        hopCount = 0,
        formattedPath = null,
        regionToShow = null,
    )

    @Test
    fun directMessageSentAnnouncesAsStillSending() {
        assertEquals(
            StatusLabelKind.SENDING,
            statusLabelKind(footer(MessageStatus.SENT, isChannelMessage = false)),
        )
    }

    @Test
    fun directMessageSentTwiceStillAnnouncesAsSending() {
        assertEquals(
            StatusLabelKind.SENDING,
            statusLabelKind(footer(MessageStatus.SENT, isChannelMessage = false, sendCount = 2)),
        )
    }

    @Test
    fun channelSentIsTerminalSuccess() {
        assertEquals(
            StatusLabelKind.SENT,
            statusLabelKind(footer(MessageStatus.SENT, isChannelMessage = true)),
        )
        assertEquals(
            StatusLabelKind.SENT_MULTIPLE,
            statusLabelKind(footer(MessageStatus.SENT, isChannelMessage = true, sendCount = 2)),
        )
    }

    @Test
    fun deliveredFailedAndRetryingAreUnchangedByConversationKind() {
        assertEquals(
            StatusLabelKind.DELIVERED,
            statusLabelKind(footer(MessageStatus.DELIVERED, isChannelMessage = false)),
        )
        assertEquals(
            StatusLabelKind.FAILED,
            statusLabelKind(footer(MessageStatus.FAILED, isChannelMessage = false)),
        )
        assertEquals(
            StatusLabelKind.RETRYING,
            statusLabelKind(footer(MessageStatus.RETRYING, isChannelMessage = false)),
        )
        assertEquals(
            StatusLabelKind.RETRYING_ATTEMPT,
            statusLabelKind(footer(MessageStatus.RETRYING, isChannelMessage = false, maxRetryAttempts = 3)),
        )
    }

    @Test
    fun pendingAndSendingAnnounceAsSending() {
        assertEquals(
            StatusLabelKind.SENDING,
            statusLabelKind(footer(MessageStatus.PENDING, isChannelMessage = true)),
        )
        assertEquals(
            StatusLabelKind.SENDING,
            statusLabelKind(footer(MessageStatus.SENDING, isChannelMessage = true)),
        )
    }
}
