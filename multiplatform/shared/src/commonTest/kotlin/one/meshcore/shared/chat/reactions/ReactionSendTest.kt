package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Covers the outgoing [ReactionService.react] path: dedup, routing, send, and optimistic persist. */
class ReactionSendTest {
    private val ts = 1_704_067_200L

    private fun service() = ReactionService(newReactionId = { ChatFixtures.freshUuid() }, now = { NOW })

    private fun channelMessage(sender: String? = "Alice", direction: MessageDirection = MessageDirection.INCOMING) =
        ChatFixtures.message(
            text = "Hello world",
            timestamp = ts,
            direction = direction,
            status = MessageStatus.DELIVERED,
            contactID = null,
            channelIndex = 3,
            senderNodeName = sender,
        )

    @Test
    fun channelReactSendsWireTextAndPersistsOptimistically() = runTest {
        val message = channelMessage(sender = "Alice")
        val store = InMemoryReactionStore()
        val sender = RecordingReactionSender()

        val result = service().react("👍", message, "Me", store, sender)

        assertIs<ReactionSendResult.Sent>(result)
        assertEquals("👍:1", result.summary)
        assertEquals(1, sender.channelSends.size)
        assertTrue(sender.channelSends.first().first.startsWith("@[Alice]👍\n"))
        assertEquals(1, store.reactions.size)
        assertEquals("Me", store.reactions.first().senderName)
        assertEquals(3, store.reactions.first().channelIndex)
    }

    @Test
    fun dmReactAddressesTheContact() = runTest {
        val contact = ChatFixtures.contact("Alice")
        val message = deliveredDirectMessage(contact.id)
        val store = InMemoryReactionStore(contacts = listOf(contact))
        val sender = RecordingReactionSender()

        val result = service().react("❤️", message, "Me", store, sender)

        assertIs<ReactionSendResult.Sent>(result)
        assertEquals(1, sender.directSends.size)
        assertEquals(contact.id, sender.directSends.first().second.id)
        assertTrue(sender.directSends.first().first.startsWith("❤️\n"))
        assertEquals(contact.id, store.reactions.first().contactID)
    }

    @Test
    fun outgoingChannelMessageReactsAgainstLocalNodeName() = runTest {
        val message = channelMessage(sender = null, direction = MessageDirection.OUTGOING)
        val sender = RecordingReactionSender()
        val result = service().react("🔥", message, "Me", InMemoryReactionStore(), sender)

        assertIs<ReactionSendResult.Sent>(result)
        assertTrue(sender.channelSends.first().first.startsWith("@[Me]🔥\n"))
    }

    @Test
    fun alreadyReactedShortCircuitsWithoutSending() = runTest {
        val message = channelMessage(sender = "Alice")
        val store = InMemoryReactionStore()
        store.saveReaction(
            ReactionDto(
                id = ChatFixtures.freshUuid(),
                messageID = message.id,
                emoji = "👍",
                senderName = "Me",
                messageHash = "x",
                rawText = "",
                receivedAt = NOW,
                radioID = message.radioID,
                channelIndex = 3,
            ),
        )
        val sender = RecordingReactionSender()

        assertIs<ReactionSendResult.AlreadyReacted>(service().react("👍", message, "Me", store, sender))
        assertTrue(sender.channelSends.isEmpty())
    }

    @Test
    fun sendFailureReportsFailedAndPersistsNothing() = runTest {
        val message = channelMessage(sender = "Alice")
        val store = InMemoryReactionStore()
        val sender = RecordingReactionSender(error = IllegalStateException("radio offline"))

        assertIs<ReactionSendResult.Failed>(service().react("👍", message, "Me", store, sender))
        assertTrue(store.reactions.isEmpty())
    }

    @Test
    fun inFlightKeyIsReleasedAfterCompletion() = runTest {
        val message = channelMessage(sender = "Alice")
        val store = InMemoryReactionStore()
        val svc = service()
        assertIs<ReactionSendResult.Sent>(svc.react("👍", message, "Me", store, RecordingReactionSender()))
        // A second identical tap is now blocked by the persisted self-reaction, not left wedged in-flight.
        assertIs<ReactionSendResult.AlreadyReacted>(svc.react("👍", message, "Me", store, RecordingReactionSender()))
    }

    @Test
    fun aBlankNodeNameStillAddressesTheReactionAsMe() = runTest {
        val message = channelMessage(sender = "Alice")
        val store = InMemoryReactionStore()
        val sender = RecordingReactionSender()

        val result = service().react(
            "👍",
            message,
            reactionSenderName(localNodeName = ""),
            store,
            sender,
        )

        assertIs<ReactionSendResult.Sent>(result)
        assertEquals(FALLBACK_REACTION_NODE_NAME, store.reactions.single().senderName)
        assertEquals(1, sender.channelSends.size)
    }

    @Test
    fun aNullNodeNameStillAddressesTheReactionAsMe() {
        assertEquals(FALLBACK_REACTION_NODE_NAME, reactionSenderName(null))
        assertEquals(FALLBACK_REACTION_NODE_NAME, reactionSenderName(""))
        assertEquals(FALLBACK_REACTION_NODE_NAME, reactionSenderName("   ".trim()))
        assertEquals("Ops", reactionSenderName("Ops"))
    }

    private companion object {
        const val NOW = 1_704_067_500.0
    }
}
