package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A direct reaction contact lookup. Cancellation keeps the thrown instance and sends nothing.
 * Every other lookup failure, including a thrown error, skips the reaction. A missing contact skips it.
 */
class DirectReactionContactFaultTest {
    @Test
    fun aCancelledContactLookupKeepsTheThrownInstance() = runTest {
        val stopped = CancellationException("superseded")
        val contact = ChatFixtures.contact("Alice")
        val store = ContactLookupFaultStore(contact, stopped)
        val sender = RecordingReactionSender()

        val thrown = assertFailsWith<CancellationException> {
            service().react("👍", deliveredDirectMessage(contact.id), "Me", store, sender)
        }

        assertSame(stopped, thrown)
        assertTrue(sender.directSends.isEmpty())
        assertTrue(store.reactions.isEmpty())
    }

    @Test
    fun aContactLookupExceptionSkipsTheReaction() = runTest {
        val contact = ChatFixtures.contact("Alice")
        val store = ContactLookupFaultStore(contact, ContactLookupProbeException())
        val sender = RecordingReactionSender()

        val result = service().react("👍", deliveredDirectMessage(contact.id), "Me", store, sender)

        assertIs<ReactionSendResult.Skipped>(result)
        assertTrue(sender.directSends.isEmpty())
        assertTrue(store.reactions.isEmpty())
    }

    @Test
    fun aContactLookupErrorSkipsTheReaction() = runTest {
        val contact = ChatFixtures.contact("Alice")
        val store = ContactLookupFaultStore(contact, ContactLookupProbeError())
        val sender = RecordingReactionSender()

        val result = service().react("👍", deliveredDirectMessage(contact.id), "Me", store, sender)

        assertIs<ReactionSendResult.Skipped>(result)
        assertTrue(sender.directSends.isEmpty())
        assertTrue(store.reactions.isEmpty())
    }

    @Test
    fun aMissingContactSkipsTheReaction() = runTest {
        val contact = ChatFixtures.contact("Alice")
        val store = InMemoryReactionStore()
        val sender = RecordingReactionSender()

        val result = service().react("👍", deliveredDirectMessage(contact.id), "Me", store, sender)

        assertIs<ReactionSendResult.Skipped>(result)
        assertTrue(sender.directSends.isEmpty())
        assertTrue(store.reactions.isEmpty())
    }

    private fun service() = ReactionService(newReactionId = { ChatFixtures.freshUuid() }, now = { NOW })

    private companion object {
        const val NOW = 1_704_067_500.0
    }
}

private class ContactLookupFaultStore private constructor(
    private val backing: InMemoryReactionStore,
    private val failure: Throwable,
) : ReactionStore by backing {
    constructor(contact: ContactDto, failure: Throwable) : this(
        InMemoryReactionStore(contacts = listOf(contact)),
        failure,
    )

    val reactions get() = backing.reactions

    override suspend fun fetchContact(id: Uuid): ContactDto? = throw failure
}

private class ContactLookupProbeException : Exception("db locked")

private class ContactLookupProbeError : Error("probe")
