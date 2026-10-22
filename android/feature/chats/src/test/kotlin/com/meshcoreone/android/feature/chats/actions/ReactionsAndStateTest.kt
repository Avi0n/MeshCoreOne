// PortedFrom: MC1Tests/Views/Chats/Components/ReactionDetailsSelectionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/ChatViewModelReactionIndexingTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.actions

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test

class ReactionsAndStateTest {
    @Test
    fun `pending empty failure retry and cancellation remain distinct`() = runBlocking {
        val source = FakeActionsSource()
        val target = message()
        val holder = MessageActionsStateHolder(source, CoroutineScope(coroutineContext))
        val pending = CompletableDeferred<List<ReactionDTO>>()
        source.reactionRead = { pending.await() }
        holder.loadReactions(target.id, null)
        assertTrue(holder.state.value.isLoadingReactions)
        yield()
        assertTrue(holder.state.value.isLoadingReactions)
        pending.complete(emptyList())
        yield()
        assertEquals(false, holder.state.value.isLoadingReactions)
        assertEquals(null, holder.state.value.reactionFailure)
        assertTrue(holder.state.value.reactionGroups.isEmpty())

        source.reactionRead = { throw IllegalStateException("private") }
        holder.loadReactions(target.id, null)
        yield()
        assertEquals(MessageActionsFailure.ReactionsLoadFailed, holder.state.value.reactionFailure)
        assertEquals(false, holder.state.value.isLoadingReactions)
        source.reactionRead = { listOf(reaction(target.id, "X", "Sender")) }
        holder.loadReactions(target.id, "X")
        assertEquals(null, holder.state.value.reactionFailure)
        yield()
        assertEquals("X", holder.state.value.selectedEmoji)
        assertEquals(null, holder.state.value.failure)

        source.reactionRead = { throw CancellationException("cancel") }
        holder.loadReactions(target.id, null)
        yield()
        assertTrue(holder.state.value.isReactionLoadCancelled)
        assertEquals(false, holder.state.value.isLoadingReactions)
        assertEquals(null, holder.state.value.reactionFailure)
    }

    @Test
    fun `late previous read cannot replace a newer message selection`() = runBlocking {
        val source = FakeActionsSource()
        val old = message()
        val current = message()
        val late = CompletableDeferred<List<ReactionDTO>>()
        source.reactionRead = { id ->
            if (id == old.id) withContext(NonCancellable) { late.await() }
            else listOf(reaction(current.id, "New", "Current"))
        }
        val holder = MessageActionsStateHolder(source, CoroutineScope(coroutineContext))
        holder.loadReactions(old.id, "Old")
        yield()
        holder.loadReactions(current.id, "New")
        yield()
        late.complete(listOf(reaction(old.id, "Old", "Previous")))
        yield()
        yield()
        assertEquals("New", holder.state.value.selectedEmoji)
        assertEquals(listOf(current.id), holder.state.value.reactionGroups.single().reactions.map { it.messageID })
        assertEquals(false, holder.state.value.isLoadingReactions)
        assertEquals(false, holder.state.value.isReactionLoadCancelled)
    }

    @Test
    fun `reaction selection uses preferred or first`() {
        assertEquals("❤️", resolvedReactionSelection("❤️", listOf("👍", "❤️", "😂")))
        assertEquals("👍", resolvedReactionSelection("🎉", listOf("👍", "❤️")))
        assertEquals("👍", resolvedReactionSelection(null, listOf("👍", "❤️")))
        assertEquals(null, resolvedReactionSelection("👍", emptyList()))
    }

    @Test
    fun `reaction groups order by count then earliest receipt`() {
        val id = UUID.randomUUID()
        val groups = reactionGroups(
            listOf(
                reaction(id, "❤️", "A", TEST_TIME.plusSeconds(2)),
                reaction(id, "👍", "B", TEST_TIME),
                reaction(id, "❤️", "C", TEST_TIME.plusSeconds(3)),
                reaction(id, "😂", "D", TEST_TIME.plusSeconds(1)),
            ),
        )
        assertEquals(listOf("❤️", "👍", "😂"), groups.map { it.emoji })
    }

    @Test
    fun `reaction indexing scopes summaries and drops duplicates`() {
        val dm = message()
        val duplicate = reaction(dm.id, "👍", "Bob")
        val dmResult = ReactionIndexingPolicy.index(dm, listOf(duplicate, duplicate), null)
        assertEquals(1, dmResult.reactions.size)
        assertEquals("👍:1", dmResult.summary)
        val channel = message(channelIndex = 3u, senderNodeName = "Alice")
        val channelResult = ReactionIndexingPolicy.index(channel, listOf(reaction(channel.id, "🔥", "Bob")), "Me")
        assertEquals("🔥:1", channelResult.summary)
        val outgoing = channel.copy(direction = MessageDirection.OUTGOING)
        assertTrue(ReactionIndexingPolicy.index(outgoing, listOf(reaction(channel.id, "🔥", "Bob")), null).reactions.isEmpty())
    }

    @Test
    fun `state holder loads durable details and reactions`() = runBlocking {
        val source = FakeActionsSource()
        val target = message(channelIndex = 0u, heardRepeats = 1)
        source.contacts = listOf(contact(0xAA, name = "Ridge"))
        source.discovered = listOf(discovered(0xBB))
        source.repeats = listOf(repeat(target.id))
        source.reactions = listOf(reaction(target.id, "👍", "Bob"))
        val holder = MessageActionsStateHolder(source, CoroutineScope(coroutineContext))
        holder.loadDetails(target)
        holder.loadReactions(target.id, "👍")
        yield()
        assertEquals(1, holder.state.value.repeats?.size)
        assertEquals(1, holder.state.value.directory.repeaters.size)
        assertEquals("👍", holder.state.value.selectedEmoji)
    }

    @Test
    fun `typed load block and mute failures are retained`() = runBlocking {
        val source = FakeActionsSource().apply { failure = IllegalStateException("private") }
        val target = message(channelIndex = 0u, heardRepeats = 1)
        val holder = MessageActionsStateHolder(source, CoroutineScope(coroutineContext))
        holder.loadDetails(target)
        yield()
        assertIs<MessageActionsFailure.DetailsLoadFailed>(holder.state.value.failure)
        holder.blockSender(target, emptySet())
        yield()
        assertIs<MessageActionsFailure.BlockFailed>(holder.state.value.failure)
        holder.setMuted(contact(0xAA), true)
        yield()
        assertIs<MessageActionsFailure.MuteFailed>(holder.state.value.failure)
        Unit
    }

    @Test
    fun `cancellation does not become a typed failure`() = runBlocking {
        val source = FakeActionsSource().apply { failure = CancellationException("cancel") }
        val job = Job()
        val holder = MessageActionsStateHolder(source, CoroutineScope(Dispatchers.Unconfined + job))
        holder.loadDetails(message(channelIndex = 0u, heardRepeats = 1))
        yield()
        assertEquals(null, holder.state.value.failure)
        job.cancel()
    }

    @Test
    fun `block and mute invoke real data source actions`() = runBlocking {
        val source = FakeActionsSource()
        val target = message(channelIndex = 0u)
        val selected = contact(0xAA)
        val holder = MessageActionsStateHolder(source, CoroutineScope(coroutineContext))
        holder.blockSender(target, setOf(selected.id))
        holder.setMuted(selected, true)
        yield()
        assertEquals("RemoteNode", source.blockedSender)
        assertEquals(setOf(selected.id), source.blockedContacts)
        assertEquals(selected.id to true, source.muted)
    }
}

private class FakeActionsSource : MessageActionsDataSource {
    var contacts: List<ContactDTO> = emptyList()
    var discovered: List<DiscoveredNodeDTO> = emptyList()
    var repeats: List<MessageRepeatDTO> = emptyList()
    var reactions: List<ReactionDTO> = emptyList()
    var reactionRead: (suspend (UUID) -> List<ReactionDTO>)? = null
    var failure: Exception? = null
    var blockedSender: String? = null
    var blockedContacts: Set<UUID> = emptySet()
    var muted: Pair<UUID, Boolean>? = null

    private fun fail() {
        failure?.let { throw it }
    }

    override suspend fun contacts(radioId: RadioId): List<ContactDTO> {
        fail()
        return contacts
    }

    override suspend fun discoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO> {
        fail()
        return discovered
    }

    override suspend fun repeats(messageId: UUID): List<MessageRepeatDTO> {
        fail()
        return repeats
    }

    override suspend fun reactions(messageId: UUID): List<ReactionDTO> {
        reactionRead?.let { return it(messageId) }
        fail()
        return reactions
    }

    override suspend fun blockChannelSender(radioId: RadioId, senderName: String) {
        fail()
        blockedSender = senderName
    }

    override suspend fun blockContacts(contactIds: Set<UUID>) {
        fail()
        blockedContacts = contactIds
    }

    override suspend fun setMuted(contactId: UUID, muted: Boolean) {
        fail()
        this.muted = contactId to muted
    }
}
