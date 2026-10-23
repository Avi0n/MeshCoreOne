// PortedFrom: MC1Tests/Views/Chats/Components/MessageBubblePredicateTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/Components/MessageBubbleTestData.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.services.rendering.BaseColorSlot
import com.meshcoreone.android.core.services.rendering.EnvInputs
import com.meshcoreone.android.core.services.rendering.MessageBuildInputs
import com.meshcoreone.android.core.services.rendering.MessageFooter
import com.meshcoreone.android.core.services.rendering.MessageFragmentBuilder
import com.meshcoreone.android.core.services.rendering.NodeNameMatchKind
import com.meshcoreone.android.core.services.rendering.NodeNameResolution
import com.meshcoreone.android.core.services.rendering.PreviewLoadState
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageRegionOriginalPredicateTest {
    @OriginalCase("MessageBubblePredicateTests::regionToShow: nil unless flag AND isFloodRouted AND scope present(showFlag : Bool , routeType : RouteType , scope : String ? , expected : String ?)")
    @Test
    fun allEightOriginalRegionRowsUseTheProductionBuilder() {
        val rows = listOf(
            RegionCase(true, RouteType.FLOOD, "United States", "United States"),
            RegionCase(true, RouteType.TC_FLOOD, "United States", "United States"),
            RegionCase(true, RouteType.FLOOD, null, null),
            RegionCase(true, RouteType.DIRECT, "United States", null),
            RegionCase(true, RouteType.TC_DIRECT, "United States", null),
            RegionCase(false, RouteType.FLOOD, "United States", null),
            RegionCase(false, RouteType.DIRECT, "United States", null),
            RegionCase(true, RouteType.DIRECT, null, null),
        )
        for ((index, row) in rows.withIndex()) {
            val message = makeMessage(routeType = row.routeType, regionScope = row.scope)
            val footer = sourceFooter(message, showRegion = row.showRegion)

            assertEquals("Original row $index: $row", row.expected, footer.regionToShow)
        }
    }

    @OriginalCase("MessageBubblePredicateTests::regionToShow: channel messages render region regardless of routeType()")
    @Test
    fun directRouteOriginalChannelStillShowsRegion() {
        val message = makeMessage(channelIndex = 0u, routeType = RouteType.DIRECT, regionScope = "United States")
        val footer = sourceFooter(message, showRegion = true)

        assertEquals("United States", footer.regionToShow)
    }

    @OriginalCase("MessageBubblePredicateTests::regionToShow: legacy radio with no routeType respects pathLength 0xFF as direct()")
    @Test
    fun legacyNoRouteOriginalDirectPathHidesRegion() {
        val message = makeMessage(pathLength = 0xFFu, routeType = null, regionScope = "United States")
        val footer = sourceFooter(message, showRegion = true)

        assertNull(footer.regionToShow)
    }

    private fun makeMessage(
        channelIndex: UByte? = null,
        pathLength: UByte = 2u,
        routeType: RouteType? = null,
        regionScope: String? = null,
    ) = MessageDTO(
        id = UUID.randomUUID(),
        radioId = RadioId(UUID.randomUUID()),
        contactID = null,
        channelIndex = channelIndex,
        text = "Test",
        timestamp = 0u,
        createdAt = Instant.EPOCH,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED,
        pathLength = pathLength,
        pathNodes = Bytes.of(0xA3, 0x7F),
        senderNodeName = if (channelIndex != null) "RemoteNode" else null,
        isRead = true,
        routeType = routeType,
        regionScope = regionScope,
        regionScopeMatches = SnapshotList.empty(),
    )

    private fun sourceFooter(message: MessageDTO, showRegion: Boolean): MessageFooter {
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
            formattedPath = null,
            senderResolution = NodeNameResolution("Unknown", NodeNameMatchKind.UNRESOLVED),
            showTimestamp = false,
            showDirectionGap = false,
            showSenderName = true,
            showNewMessagesDivider = false,
        )
        val env = EnvInputs.DEFAULT.copy(
            showIncomingRegion = showRegion,
            previewsEnabled = true,
            currentUserName = "Me",
            translationOffersEnabled = false,
        )
        return MessageFragmentBuilder.makeItem(message, inputs, env).footer
    }

    private data class RegionCase(
        val showRegion: Boolean,
        val routeType: RouteType,
        val scope: String?,
        val expected: String?,
    )
}
