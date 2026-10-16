package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A direct reaction send. Cancellation keeps the thrown instance and persists nothing. Every other
 * failure, including a thrown error, is returned as [ReactionSendResult.Failed] with that instance.
 */
class DirectReactionSendFaultTest {
    @Test
    fun aCancelledDirectReactionKeepsTheThrownInstance() = runTest {
        val stopped = CancellationException("superseded")
        val contact = ChatFixtures.contact("Alice")
        val store = InMemoryReactionStore(contacts = listOf(contact))
        val sender = RecordingReactionSender(error = stopped)

        val thrown = assertFailsWith<CancellationException> {
            service().react("👍", deliveredDirectMessage(contact.id), "Me", store, sender)
        }

        assertSame(stopped, thrown)
        assertTrue(sender.directSends.isEmpty())
        assertTrue(store.reactions.isEmpty())
    }

    @Test
    fun aDirectReactionExceptionIsReportedFailed() = runTest {
        val failure = DirectReactionProbeException()
        val contact = ChatFixtures.contact("Alice")
        val store = InMemoryReactionStore(contacts = listOf(contact))
        val sender = RecordingReactionSender(error = failure)

        val result = service().react("👍", deliveredDirectMessage(contact.id), "Me", store, sender)

        val failed = assertIs<ReactionSendResult.Failed>(result)
        assertSame(failure, failed.error)
        assertTrue(sender.directSends.isEmpty())
        assertTrue(store.reactions.isEmpty())
    }

    @Test
    fun aDirectReactionErrorIsReportedFailed() = runTest {
        val probe = DirectReactionProbeError()
        val contact = ChatFixtures.contact("Alice")
        val store = InMemoryReactionStore(contacts = listOf(contact))
        val sender = RecordingReactionSender(error = probe)

        val result = service().react("👍", deliveredDirectMessage(contact.id), "Me", store, sender)

        val failed = assertIs<ReactionSendResult.Failed>(result)
        assertSame(probe, failed.error)
        assertEquals(0, store.reactions.size)
        assertTrue(sender.directSends.isEmpty())
    }

    private fun service() = ReactionService(newReactionId = { ChatFixtures.freshUuid() }, now = { NOW })

    private companion object {
        const val NOW = 1_704_067_500.0
    }
}

private class DirectReactionProbeException : Exception("radio offline")

private class DirectReactionProbeError : Error("probe")
