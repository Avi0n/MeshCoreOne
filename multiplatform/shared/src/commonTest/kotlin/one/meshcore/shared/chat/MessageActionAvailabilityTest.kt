package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.RouteType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ports the Swift `MessageActionAvailabilityTests` matrix so long-press gates cannot drift from iOS.
 */
class MessageActionAvailabilityTest {
    @Test
    fun fetchedMessageWithHopsUnlocksPathActionsOverAStaleBakedItem() {
        val baked = MessageActionAvailability.of(
            ChatFixtures.message(direction = MessageDirection.INCOMING).copy(
                pathNodes = null,
                routeType = RouteType.FLOOD,
            ),
        )
        val fetched = ChatFixtures.message(direction = MessageDirection.INCOMING).copy(
            pathNodes = byteArrayOf(0xA3.toByte(), 0x7F),
            routeType = RouteType.FLOOD,
        )
        assertFalse(baked.canViewPath)
        assertTrue(messageActionsAvailability(baked, fetched).canViewPath)
        assertFalse(messageActionsAvailability(baked, fetched = null).canViewPath)
    }

    @Test
    fun hopLessFetchDoesNotHidePathActionsTheBakedItemAlreadyHas() {
        val baked = MessageActionAvailability.of(
            ChatFixtures.message(direction = MessageDirection.INCOMING).copy(
                pathNodes = byteArrayOf(0xA3.toByte(), 0x7F),
                routeType = RouteType.FLOOD,
            ),
        )
        val fetched = ChatFixtures.message(direction = MessageDirection.INCOMING).copy(
            pathNodes = null,
            routeType = RouteType.FLOOD,
        )
        assertTrue(baked.canViewPath)
        assertTrue(messageActionsAvailability(baked, fetched).canViewPath)
    }

    @Test
    fun floodRoutedWithNonEmptyPathNodesCanViewPath() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING).copy(
            pathNodes = byteArrayOf(0xA3.toByte(), 0x7F),
            routeType = RouteType.FLOOD,
        )
        assertTrue(MessageActionAvailability.of(message).canViewPath)
    }

    @Test
    fun floodRoutedWithEmptyPathNodesCannotViewPath() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING).copy(
            pathNodes = byteArrayOf(),
            routeType = RouteType.FLOOD,
        )
        assertFalse(MessageActionAvailability.of(message).canViewPath)
    }

    @Test
    fun floodRoutedWithNullPathNodesCannotViewPath() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING).copy(
            pathNodes = null,
            routeType = RouteType.FLOOD,
        )
        assertFalse(MessageActionAvailability.of(message).canViewPath)
    }

    @Test
    fun incomingExtraPathsStayAvailableWhenCanonicalPathIsUnknown() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING, channelIndex = 2).copy(
            pathNodes = null,
            heardRepeats = 2,
        )

        val availability = MessageActionAvailability.of(message)

        assertFalse(availability.canViewPath)
        assertTrue(availability.canShowIncomingPaths)
    }

    @Test
    fun directRoutedCannotViewPath() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING).copy(
            pathNodes = byteArrayOf(0xA3.toByte(), 0x7F),
            routeType = RouteType.DIRECT,
        )
        assertFalse(MessageActionAvailability.of(message).canViewPath)
    }

    @Test
    fun outgoingMessageCannotViewPath() {
        val message = ChatFixtures.message(direction = MessageDirection.OUTGOING).copy(
            pathNodes = byteArrayOf(0xA3.toByte(), 0x7F),
            routeType = RouteType.FLOOD,
        )
        assertFalse(MessageActionAvailability.of(message).canViewPath)
    }

    @Test
    fun channelMessageWithDirectRouteTypeIsStillFloodAndCanViewPath() {
        // Channel index forces isFloodRouted regardless of routeType raw.
        val message = ChatFixtures.message(
            direction = MessageDirection.INCOMING,
            channelIndex = 0,
        ).copy(
            pathNodes = byteArrayOf(0xA3.toByte(), 0x7F),
            routeType = RouteType.DIRECT,
        )
        assertTrue(MessageActionAvailability.of(message).canViewPath)
    }

    @Test
    fun channelIncomingWithSenderNameCanSendDmAndBlock() {
        val message = ChatFixtures.message(
            direction = MessageDirection.INCOMING,
            channelIndex = 0,
            senderNodeName = "RemoteNode",
        )
        val availability = MessageActionAvailability.of(message)
        assertTrue(availability.canSendDM)
        assertTrue(availability.canBlockSender)
        assertTrue(availability.canReply)
        assertFalse(availability.canSendAgain)
    }

    @Test
    fun channelOutgoingCannotSendDm() {
        val message = ChatFixtures.message(
            direction = MessageDirection.OUTGOING,
            channelIndex = 0,
            senderNodeName = "Me",
        )
        val availability = MessageActionAvailability.of(message)
        assertFalse(availability.canSendDM)
        assertFalse(availability.canBlockSender)
        assertTrue(availability.canSendAgain)
        assertFalse(availability.canReply)
    }

    @Test
    fun dmIncomingCannotSendDm() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING, channelIndex = null)
        assertFalse(MessageActionAvailability.of(message).canSendDM)
    }

    @Test
    fun channelIncomingPrefixOnlyCannotSendDmOrBlock() {
        val message = ChatFixtures.message(
            direction = MessageDirection.INCOMING,
            channelIndex = 0,
            senderNodeName = null,
        ).copy(senderKeyPrefix = byteArrayOf(0xAA.toByte()))
        val availability = MessageActionAvailability.of(message)
        assertFalse(availability.canSendDM)
        assertFalse(availability.canBlockSender)
    }

    @Test
    fun outgoingWithHeardRepeatsCanShowRepeatDetails() {
        val message = ChatFixtures.message(direction = MessageDirection.OUTGOING).copy(heardRepeats = 2)
        assertTrue(MessageActionAvailability.of(message).canShowRepeatDetails)
    }

    @Test
    fun incomingCannotShowRepeatDetails() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING).copy(heardRepeats = 2)
        assertFalse(MessageActionAvailability.of(message).canShowRepeatDetails)
    }

    @Test
    fun dmIncomingSheetMatchesIosPrimaryAndDestructiveOrder() {
        val availability = MessageActionAvailability.of(
            ChatFixtures.message(direction = MessageDirection.INCOMING, channelIndex = null),
        )
        assertEquals(
            listOf(MessageAction.REPLY, MessageAction.COPY, MessageAction.TRANSLATE),
            availability.primaryActions,
        )
        assertEquals(listOf(MessageAction.DELETE), availability.destructiveActions)
    }

    @Test
    fun channelIncomingNamedSenderSheetMatchesIosButtonOrder() {
        val availability = MessageActionAvailability.of(
            ChatFixtures.message(
                direction = MessageDirection.INCOMING,
                channelIndex = 0,
                senderNodeName = "RemoteNode",
            ),
        )
        assertEquals(
            listOf(
                MessageAction.REPLY,
                MessageAction.SEND_DM,
                MessageAction.COPY,
                MessageAction.TRANSLATE,
            ),
            availability.primaryActions,
        )
        assertEquals(
            listOf(MessageAction.BLOCK_SENDER, MessageAction.DELETE),
            availability.destructiveActions,
        )
    }

    @Test
    fun failedOutgoingStillOffersSendAgainOnTheSheet() {
        val availability = MessageActionAvailability.of(
            ChatFixtures.message(direction = MessageDirection.OUTGOING, status = MessageStatus.FAILED),
        )
        assertTrue(availability.canSendAgain)
        assertEquals(
            listOf(MessageAction.COPY, MessageAction.TRANSLATE, MessageAction.SEND_AGAIN),
            availability.primaryActions,
        )
        assertEquals(listOf(MessageAction.DELETE), availability.destructiveActions)
    }
}
