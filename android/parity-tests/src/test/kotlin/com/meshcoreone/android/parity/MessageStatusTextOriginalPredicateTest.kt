// PortedFrom: MC1Tests/Views/Chats/Components/MessageStatusTextTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: WP-501 Exercise the private status formatter through its actual App label consumer.
package com.meshcoreone.android.parity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.navigation.messageBubbleAccessibilityLabel
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as Chats
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.services.rendering.GroupingFlags
import com.meshcoreone.android.core.services.rendering.MessageFooter
import com.meshcoreone.android.core.services.rendering.MessageItem
import com.meshcoreone.android.core.services.rendering.MessageItemEnvelope
import com.meshcoreone.android.core.services.rendering.NodeNameMatchKind
import com.meshcoreone.android.core.services.rendering.NodeNameResolution
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS")
class MessageStatusTextOriginalPredicateTest {
    @OriginalCase("MessageStatusTextTests::DM .sent renders as Sending()")
    @Test fun directSentOriginalRendersAsSending() {
        assertStatusText(makeItem(MessageStatus.SENT, isChannelMessage = false), Chats.chatsMessageStatusSending)
    }

    @OriginalCase("MessageStatusTextTests::Channel .sent renders as Sent()")
    @Test fun channelSentOriginalRendersAsSent() {
        assertStatusText(makeItem(MessageStatus.SENT, isChannelMessage = true), Chats.chatsMessageStatusSent)
    }

    @OriginalCase("MessageStatusTextTests::DM .delivered still renders as Delivered()")
    @Test fun directDeliveredOriginalRendersAsDelivered() {
        assertStatusText(makeItem(MessageStatus.DELIVERED, isChannelMessage = false), Chats.chatsMessageStatusDelivered)
    }

    @OriginalCase("MessageStatusTextTests::DM .failed still renders as Failed()")
    @Test fun directFailedOriginalRendersAsFailed() {
        assertStatusText(makeItem(MessageStatus.FAILED, isChannelMessage = false), Chats.chatsMessageStatusFailed)
    }

    private fun makeItem(
        status: MessageStatus,
        isChannelMessage: Boolean,
        heardRepeats: Long = 0,
        sendCount: Long = 1,
    ) = MessageItem(
        id = UUID.randomUUID(),
        envelope = MessageItemEnvelope(
            messageID = UUID.randomUUID(),
            isOutgoing = true,
            senderName = "me",
            senderResolution = NodeNameResolution("me", NodeNameMatchKind.EXACT),
            status = status,
            date = Instant.ofEpochSecond(1_700_000_000),
            hasFailed = status == MessageStatus.FAILED,
            containsSelfMention = false,
            mentionSeen = false,
            incomingAvatar = null,
        ),
        content = SnapshotList.empty(),
        footer = MessageFooter(
            showHop = false, hopCount = 0, formattedPath = null, regionToShow = null,
            sendTimeToShow = null, sendTimeWasCorrected = false, showStatusRow = true,
            status = status, isChannelMessage = isChannelMessage, heardRepeats = heardRepeats,
            retryAttempt = 0, maxRetryAttempts = 0, sendCount = sendCount,
        ),
        grouping = GroupingFlags(
            showTimestamp = false, showDirectionGap = false, showSenderName = false,
            showNewMessagesDivider = false,
        ),
        shouldRequestPreviewFetch = false,
    )

    private fun assertStatusText(item: MessageItem, expectedResource: Int) {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val message = MessageDTO(
            id = item.envelope.messageID,
            radioId = RadioId(UUID.randomUUID()),
            channelIndex = if (item.footer.isChannelMessage) 0u else null,
            text = "Test",
            timestamp = 1_700_000_000u,
            createdAt = item.envelope.date,
            direction = MessageDirection.OUTGOING,
            status = item.envelope.status,
            heardRepeats = item.footer.heardRepeats,
            sendCount = item.footer.sendCount,
        )
        assertEquals(
            "Test, ${resources.getString(expectedResource)}",
            messageBubbleAccessibilityLabel(message, item, false, resources),
        )
    }
}
