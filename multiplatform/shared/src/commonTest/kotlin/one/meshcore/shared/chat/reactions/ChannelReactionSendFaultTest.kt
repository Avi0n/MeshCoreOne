package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A channel reaction send. Cancellation keeps the thrown instance and persists nothing. Every other
 * failure, including a thrown error, is returned as [ReactionSendResult.Failed] with that instance.
 */
class ChannelReactionSendFaultTest {
    @Test
    fun aCancelledChannelReactionKeepsTheThrownInstance() = runTest {
        val stopped = CancellationException("superseded")
        val store = InMemoryReactionStore()
        val sender = RecordingReactionSender(error = stopped)

        val thrown = assertFailsWith<CancellationException> {
            service().react("👍", channelMessage(), "Me", store, sender)
        }

        assertSame(stopped, thrown)
        assertTrue(sender.channelSends.isEmpty())
        assertTrue(store.reactions.isEmpty())
    }

    @Test
    fun aChannelReactionExceptionIsReportedFailed() = runTest {
        val failure = ChannelReactionProbeException()
        val store = InMemoryReactionStore()
        val sender = RecordingReactionSender(error = failure)

        val result = service().react("👍", channelMessage(), "Me", store, sender)

        val failed = assertIs<ReactionSendResult.Failed>(result)
        assertSame(failure, failed.error)
        assertTrue(sender.channelSends.isEmpty())
        assertTrue(store.reactions.isEmpty())
    }

    @Test
    fun aChannelReactionErrorIsReportedFailed() = runTest {
        val probe = ChannelReactionProbeError()
        val store = InMemoryReactionStore()
        val sender = RecordingReactionSender(error = probe)

        val result = service().react("👍", channelMessage(), "Me", store, sender)

        val failed = assertIs<ReactionSendResult.Failed>(result)
        assertSame(probe, failed.error)
        assertEquals(0, store.reactions.size)
    }

    private fun service() = ReactionService(newReactionId = { ChatFixtures.freshUuid() }, now = { NOW })

    private fun channelMessage() = ChatFixtures.message(
        text = "Hello world",
        timestamp = 1_704_067_200L,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED,
        contactID = null,
        channelIndex = 3,
        senderNodeName = "Alice",
    )

    private companion object {
        const val NOW = 1_704_067_500.0
    }
}

private class ChannelReactionProbeException : Exception("radio offline")

private class ChannelReactionProbeError : Error("probe")
