package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Incoming channel cluster-end flags: avatar on the last row of a same-sender run. Mirrors iOS
 * `IncomingAvatarClusterTests`. DMs never show an incoming gutter.
 */
class IncomingAvatarClusterTest {
    @Test
    fun twoIncomingSameSenderSixtySecondsFirstHidesSecondShows() {
        val first = ChatFixtures.channelMessage(timestamp = 1000, senderName = "Alice")
        val second = ChatFixtures.channelMessage(timestamp = 1060, senderName = "Alice")
        assertFalse(computeDisplayFlags(first, previous = null, next = second).isClusterEnd)
        assertTrue(computeDisplayFlags(second, previous = first, next = null).isClusterEnd)
    }

    @Test
    fun senderChangeBothShow() {
        val first = ChatFixtures.channelMessage(timestamp = 1000, senderName = "Alice")
        val second = ChatFixtures.channelMessage(timestamp = 1060, senderName = "Bob")
        assertTrue(computeDisplayFlags(first, previous = null, next = second).isClusterEnd)
        assertTrue(computeDisplayFlags(second, previous = first, next = null).isClusterEnd)
    }

    @Test
    fun incomingThenOutgoingIncomingShows() {
        val incoming = ChatFixtures.channelMessage(timestamp = 1000, senderName = "Alice")
        val outgoing = ChatFixtures.channelMessage(
            timestamp = 1060,
            senderName = null,
            direction = MessageDirection.OUTGOING,
        )
        assertTrue(computeDisplayFlags(incoming, previous = null, next = outgoing).isClusterEnd)
    }

    @Test
    fun threeHundredSecondGapStillClusters() {
        val first = ChatFixtures.channelMessage(timestamp = 1000, senderName = "Alice")
        val second = ChatFixtures.channelMessage(timestamp = 1300, senderName = "Alice")
        assertFalse(computeDisplayFlags(first, previous = null, next = second).isClusterEnd)
        assertTrue(computeDisplayFlags(second, previous = first, next = null).isClusterEnd)
    }

    @Test
    fun threeHundredOneSecondGapBreaksCluster() {
        val first = ChatFixtures.channelMessage(timestamp = 1000, senderName = "Alice")
        val second = ChatFixtures.channelMessage(timestamp = 1301, senderName = "Alice")
        assertTrue(computeDisplayFlags(first, previous = null, next = second).isClusterEnd)
    }

    @Test
    fun twoNilNameIncomingBothShow() {
        val first = ChatFixtures.channelMessage(timestamp = 1000, senderName = null)
        val second = ChatFixtures.channelMessage(timestamp = 1060, senderName = null)
        assertTrue(computeDisplayFlags(first, previous = null, next = second).isClusterEnd)
        assertTrue(computeDisplayFlags(second, previous = first, next = null).isClusterEnd)
    }

    @Test
    fun twoEmptyNameIncomingFirstHidesSecondShows() {
        val first = ChatFixtures.channelMessage(timestamp = 1000, senderName = "")
        val second = ChatFixtures.channelMessage(timestamp = 1060, senderName = "")
        assertFalse(computeDisplayFlags(first, previous = null, next = second).isClusterEnd)
        assertTrue(computeDisplayFlags(second, previous = first, next = null).isClusterEnd)
    }

    @Test
    fun incomingDmsNeverShowAvatar() {
        val first = ChatFixtures.dmMessage(timestamp = 1000)
        val second = ChatFixtures.dmMessage(timestamp = 1060)
        assertFalse(computeDisplayFlags(first, previous = null, next = second).isClusterEnd)
        assertFalse(computeDisplayFlags(second, previous = first, next = null).isClusterEnd)
    }

    @Test
    fun clusterEndResolvesUniqueNameIdentity() {
        val alice = ChatFixtures.contact(name = "Alice").copy(avatarImageData = byteArrayOf(7, 7))
        val table = IncomingAvatarIdentity.identitiesFrom(listOf(alice))
        val message = ChatFixtures.channelMessage(timestamp = 1000, senderName = "Alice")
        val inputs = buildInputsForMessage(
            message = message,
            flags = computeDisplayFlags(message, previous = null, next = null),
            dividerMessageID = null,
            resolveSender = SenderNameResolver { NodeNameResolution("Alice", NodeNameMatchKind.EXACT) },
            context = MessageBuildContext(incomingAvatarTable = { table }),
        )
        assertEquals(alice.id, inputs.incomingAvatar?.matchedContactID)
        assertEquals("Alice", inputs.incomingAvatar?.name)
    }

    @Test
    fun outgoingChannelHidesAvatar() {
        val message = ChatFixtures.channelMessage(
            timestamp = 1000,
            senderName = null,
            direction = MessageDirection.OUTGOING,
        )
        assertFalse(computeDisplayFlags(message, previous = null, next = null).isClusterEnd)
    }
}
