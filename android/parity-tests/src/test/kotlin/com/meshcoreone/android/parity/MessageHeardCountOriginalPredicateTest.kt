// PortedFrom: MC1Tests/Views/Chats/Components/MessageBubblePredicateTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/Components/MessageBubbleTestData.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.rendering.BaseColorSlot
import com.meshcoreone.android.core.services.rendering.EnvInputs
import com.meshcoreone.android.core.services.rendering.MessageBuildInputs
import com.meshcoreone.android.core.services.rendering.MessageFragmentBuilder
import com.meshcoreone.android.core.services.rendering.NodeNameMatchKind
import com.meshcoreone.android.core.services.rendering.NodeNameResolution
import com.meshcoreone.android.core.services.rendering.PreviewLoadState
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageHeardCountOriginalPredicateTest {
    @OriginalCase("MessageBubblePredicateTests::showHeardCount: gated by flag AND incoming AND heardRepeats(showFlag : Bool , direction : MessageDirection , heardRepeats : Int , expected : Bool)")
    @Test
    fun allFourOriginalHeardCountRowsUseTheProductionBuilder() {
        val rows = listOf(
            HeardCountCase(false, MessageDirection.INCOMING, 2, false),
            HeardCountCase(true, MessageDirection.INCOMING, 2, true),
            HeardCountCase(true, MessageDirection.INCOMING, 0, false),
            HeardCountCase(true, MessageDirection.OUTGOING, 2, false),
        )
        for ((index, row) in rows.withIndex()) {
            val message = MessageDTO(
                id = UUID.randomUUID(),
                radioId = RadioId(UUID.randomUUID()),
                text = "Test",
                timestamp = 0u,
                createdAt = Instant.EPOCH,
                direction = row.direction,
                status = MessageStatus.DELIVERED,
                pathLength = 2u,
                pathNodes = Bytes.of(0xA3, 0x7F),
                isRead = true,
                heardRepeats = row.heardRepeats,
            )
            val inputs = MessageBuildInputs(
                messageID = message.id,
                previewState = PreviewLoadState.IDLE,
                loadedPreview = null,
                cachedURL = null,
                isInlineImageURL = false,
                hasInlineImageRef = false,
                hasPreviewImageRef = false,
                hasPreviewIconRef = false,
                imageIsGIF = false,
                formattedText = null,
                baseColor = if (message.isOutgoing) BaseColorSlot.OUTGOING else BaseColorSlot.INCOMING,
                formattedPath = null,
                senderResolution = NodeNameResolution("Unknown", NodeNameMatchKind.UNRESOLVED),
                showTimestamp = false,
                showDirectionGap = false,
                showSenderName = true,
                showNewMessagesDivider = false,
            )
            val env = EnvInputs.DEFAULT.copy(
                showIncomingHeardCount = row.showHeardCount,
                previewsEnabled = true,
                currentUserName = "Me",
                translationOffersEnabled = false,
            )
            val footer = MessageFragmentBuilder.makeItem(message, inputs, env).footer

            assertEquals("Original row $index: $row", row.expected, footer.showHeardCount)
        }
    }

    private data class HeardCountCase(
        val showHeardCount: Boolean,
        val direction: MessageDirection,
        val heardRepeats: Long,
        val expected: Boolean,
    )
}
