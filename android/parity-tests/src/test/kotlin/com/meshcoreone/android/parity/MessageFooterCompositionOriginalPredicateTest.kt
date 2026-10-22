// PortedFrom: MC1Tests/Views/Chats/Components/MessageBubblePredicateTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/Components/MessageBubbleTestData.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.RouteType
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

class MessageFooterCompositionOriginalPredicateTest {
    @OriginalCase("MessageBubblePredicateTests::hasFooter true iff any of hop/path/region contributes(testCase : HasFooterCase)")
    @Test
    fun allSevenOriginalFooterContributionRowsUseTheProductionBuilder() {
        val rows = listOf(
            FooterCase(false, false, RouteType.DIRECT, null, null, false),
            FooterCase(true, false, RouteType.FLOOD, null, null, true),
            FooterCase(false, false, RouteType.DIRECT, null, "A3,7F", true),
            FooterCase(false, true, RouteType.FLOOD, "US", null, true),
            FooterCase(false, true, RouteType.DIRECT, "US", null, false),
            FooterCase(false, true, RouteType.FLOOD, "US", "A3,7F", true),
            FooterCase(true, true, RouteType.FLOOD, "US", "A3,7F", true),
        )
        for ((index, row) in rows.withIndex()) {
            val message = MessageDTO(
                id = UUID.randomUUID(),
                radioId = RadioId(UUID.randomUUID()),
                text = "Test",
                timestamp = 0u,
                createdAt = Instant.EPOCH,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                pathLength = 2u,
                pathNodes = Bytes.of(0xA3, 0x7F),
                isRead = true,
                routeType = row.routeType,
                regionScope = row.regionScope,
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
                baseColor = BaseColorSlot.INCOMING,
                formattedPath = row.formattedPath,
                senderResolution = NodeNameResolution("Unknown", NodeNameMatchKind.UNRESOLVED),
                showTimestamp = false,
                showDirectionGap = false,
                showSenderName = true,
                showNewMessagesDivider = false,
            )
            val env = EnvInputs.DEFAULT.copy(
                showIncomingPath = row.formattedPath != null,
                showIncomingHopCount = row.showHop,
                showIncomingRegion = row.showRegion,
                previewsEnabled = true,
                currentUserName = "Me",
            )
            val footer = MessageFragmentBuilder.makeItem(message, inputs, env).footer
            val hasFooter = footer.showHop || footer.formattedPath != null || footer.regionToShow != null

            assertEquals("Original row $index: $row", row.expected, hasFooter)
        }
    }

    private data class FooterCase(
        val showHop: Boolean,
        val showRegion: Boolean,
        val routeType: RouteType,
        val regionScope: String?,
        val formattedPath: String?,
        val expected: Boolean,
    )
}
