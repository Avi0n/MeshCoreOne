package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.BaseColorSlot
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.GroupingFlags
import one.meshcore.shared.chat.MessageActionAvailability
import one.meshcore.shared.chat.MessageEnvelope
import one.meshcore.shared.chat.MessageFooter
import one.meshcore.shared.chat.MessageFragment
import one.meshcore.shared.chat.MessageItem
import one.meshcore.shared.chat.MessageTextPayload
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.NodeNameResolution
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The composed bubble label is what a screen reader speaks for a whole message, so its content and
 * ordering are asserted directly against the iOS `accessibilityMessageLabel` rules.
 */
class MessageAccessibilityLabelTest {
    private val strings = MessageAccessibilityStrings(
        unverifiedNicknameSuffix = "(WireName)",
        unverifiedNicknameWarning = "Unverified name match",
        possibleMatch = "Possible match, matched by short prefix",
        status = "Delivered",
        hopCount = "Hop count: 3",
        path = "Routing path: A > B",
        region = "Region EU",
    )

    @Test
    fun outgoingLabelSpeaksTheTextThenTheDeliveryStatus() {
        val label = messageAccessibilityLabel(item(text = "on my way", outgoing = true), strings)
        assertEquals("on my way, Delivered", label)
    }

    @Test
    fun incomingLabelAppendsHopPathAndRegionInOrder() {
        val item = item(
            text = "roger",
            outgoing = false,
            footer = footer(
                outgoing = false,
                showHop = true,
                hopCount = 3,
                formattedPath = "A > B",
                regionToShow = "EU",
            ),
        )
        assertEquals("roger, Hop count: 3, Routing path: A > B, Region EU", messageAccessibilityLabel(item, strings))
    }

    @Test
    fun incomingLabelOmitsRoutingSlotsThatHaveNothingToSay() {
        val item = item(text = "roger", outgoing = false, footer = footer(outgoing = false))
        assertEquals("roger", messageAccessibilityLabel(item, strings))
    }

    @Test
    fun outgoingLabelNeverSpeaksIncomingRoutingMetadata() {
        val item = item(
            text = "on my way",
            outgoing = true,
            footer = footer(
                outgoing = true,
                showHop = true,
                hopCount = 3,
                formattedPath = "A > B",
                regionToShow = "EU",
            ),
        )
        val label = messageAccessibilityLabel(item, strings)
        assertEquals("on my way, Delivered", label)
        assertTrue("Hop count" !in label, "an outgoing bubble has no inbound hop count to speak")
    }

    @Test
    fun channelSenderLeadsTheLabel() {
        val item = item(
            text = "net check",
            outgoing = false,
            senderName = "Alice",
            footer = footer(outgoing = false, isChannel = true),
        )
        assertEquals("Alice: net check", messageAccessibilityLabel(item, strings))
    }

    @Test
    fun unverifiedNicknameSpeaksTheWireNameAndTheWarning() {
        val item = item(
            text = "net check",
            outgoing = false,
            senderName = "WireName",
            resolution = NodeNameResolution(
                displayName = "WireName",
                matchKind = NodeNameMatchKind.EXACT,
                unverifiedNickname = "Alice",
            ),
            footer = footer(outgoing = false, isChannel = true),
        )
        assertEquals(
            "Alice (WireName): Unverified name match, net check",
            messageAccessibilityLabel(item, strings),
        )
    }

    @Test
    fun prefixMatchedSenderIsMarkedAsAGuess() {
        val item = item(
            text = "net check",
            outgoing = false,
            senderName = "Alice",
            resolution = NodeNameResolution(displayName = "Alice", matchKind = NodeNameMatchKind.FALLBACK),
            footer = footer(outgoing = false, isChannel = true),
        )
        assertEquals(
            "Alice: Possible match, matched by short prefix, net check",
            messageAccessibilityLabel(item, strings),
        )
    }

    @Test
    fun showingTranslationSpeaksTheTranslatedBody() {
        val item = item(text = "bonjour tout le monde", outgoing = false)
        assertEquals(
            "Translated: Hello everyone",
            messageAccessibilityLabel(item, strings, spokenBody = "Translated: Hello everyone"),
        )
    }

    @Test
    fun directMessageNeverLeadsWithASenderName() {
        val item = item(text = "roger", outgoing = false, senderName = "Alice", footer = footer(outgoing = false))
        assertEquals("roger", messageAccessibilityLabel(item, strings))
    }

    private fun item(
        text: String,
        outgoing: Boolean,
        senderName: String = "",
        resolution: NodeNameResolution = NodeNameResolution(senderName, NodeNameMatchKind.EXACT),
        footer: MessageFooter = footer(outgoing = outgoing),
    ): MessageItem = MessageItem(
        id = ChatFixtures.freshUuid(),
        envelope = MessageEnvelope(
            messageID = ChatFixtures.freshUuid(),
            isOutgoing = outgoing,
            senderName = senderName,
            senderResolution = resolution,
            status = footer.status,
            dateEpochSeconds = ChatFixtures.BASE_EPOCH_SECONDS,
            containsSelfMention = false,
            mentionSeen = false,
        ),
        content = listOf(
            MessageFragment.Text(
                MessageTextPayload(
                    raw = text,
                    formattedText = null,
                    baseColor = if (outgoing) BaseColorSlot.OUTGOING else BaseColorSlot.INCOMING,
                    isOutgoing = outgoing,
                    currentUserName = "Me",
                ),
            ),
        ),
        footer = footer,
        grouping = GroupingFlags(
            showTimestamp = false,
            showDirectionGap = false,
            showSenderName = true,
            showNewMessagesDivider = false,
        ),
        shouldRequestPreviewFetch = false,
        actionAvailability = MessageActionAvailability(
            canReply = false,
            canCopy = true,
            canSendAgain = false,
            canBlockSender = false,
            canSendDM = false,
            canShowRepeatDetails = false,
            canViewPath = false,
            canDelete = true,
        ),
    )

    @Suppress("LongParameterList")
    private fun footer(
        outgoing: Boolean,
        isChannel: Boolean = false,
        showHop: Boolean = false,
        hopCount: Int = 0,
        formattedPath: String? = null,
        regionToShow: String? = null,
    ): MessageFooter = MessageFooter(
        showStatusRow = outgoing,
        status = if (outgoing) MessageStatus.DELIVERED else MessageStatus.SENT,
        isChannelMessage = isChannel,
        heardRepeats = 0,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        sendCount = 1,
        sendTimeToShowEpochSeconds = null,
        sendTimeWasCorrected = false,
        showHop = showHop,
        hopCount = hopCount,
        formattedPath = formattedPath,
        regionToShow = regionToShow,
    )
}
