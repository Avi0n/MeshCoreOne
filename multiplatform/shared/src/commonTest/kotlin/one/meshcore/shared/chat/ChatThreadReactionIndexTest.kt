package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.reactions.InMemoryReactionStore
import one.meshcore.shared.chat.reactions.ReactionHandler
import one.meshcore.shared.chat.reactions.ReactionHandlerEdges
import one.meshcore.shared.chat.reactions.ReactionIndexScope
import one.meshcore.shared.chat.reactions.ReactionParser
import one.meshcore.shared.chat.reactions.ReactionService
import one.meshcore.shared.chat.reactions.ReactionSummarySink
import one.meshcore.shared.chat.reactions.indexMessages
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A reaction that arrives before its target message is in memory — after an app restart, or for a message
 * that only appears on an older page — is queued as pending. Loading the thread must index each fetched page
 * so those pendings drain and the badge appears. Mirrors the Swift `ChatTimelinePopulator` and
 * `ChatViewModel+Pagination`, which both call `indexMessagesForReactions` on the page they just fetched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadReactionIndexTest {
    private val contact = ChatFixtures.contact("Alice")

    private fun dm(text: String, timestamp: Long): MessageDto = ChatFixtures.message(
        text = text,
        timestamp = timestamp,
        createdAt = timestamp.toDouble(),
        direction = MessageDirection.OUTGOING,
        status = MessageStatus.DELIVERED,
        contactID = contact.id,
    )

    private fun indexer(service: ReactionService, store: InMemoryReactionStore): suspend (List<MessageDto>) -> Unit {
        val handler = ReactionHandler(
            reactionService = service,
            store = store,
            sink = ReactionSummarySink { _, _ -> },
            newReactionId = { ChatFixtures.freshUuid() },
            now = { NOW },
            edges = ReactionHandlerEdges(unknownSenderLabel = { "Localized unknown sender" }),
        )
        return { page -> handler.indexMessages(page, ReactionIndexScope.Direct(contact)) }
    }

    @Test
    fun `the initial page drains a reaction queued before the target was loaded`() = runTest {
        val target = dm("Hello world", TIMESTAMP)
        val reactionStore = InMemoryReactionStore(messages = listOf(target), contacts = listOf(contact))
        val service = ReactionService(newReactionId = { ChatFixtures.freshUuid() }, now = { NOW })
        val rawText = ReactionParser.buildDmReactionText("👍", target.text, target.reactionTimestamp)
        val parsed = assertNotNull(ReactionParser.parseDM(rawText))
        service.queuePendingDmReaction(parsed, contact.id, "Bob", rawText, contact.radioID)

        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows = listOf(target)),
            currentContact = { contact },
            indexPageForReactions = indexer(service, reactionStore),
        )
        viewModel.loadInitial()
        runCurrent()

        val persisted = reactionStore.reactions.singleOrNull()
        assertNotNull(persisted, "the queued reaction must attach to the message the load brought in")
        assertEquals(target.id, persisted.messageID)
        assertEquals("👍", persisted.emoji)
        assertEquals(contact.radioID, persisted.radioID)
    }

    @Test
    fun `an older page drains a reaction whose target was below the first page`() = runTest {
        val older = dm("Older message", TIMESTAMP)
        val newer = (1..NEWER_ROWS).map { dm("newer-$it", TIMESTAMP + it) }
        val reactionStore = InMemoryReactionStore(messages = listOf(older), contacts = listOf(contact))
        val service = ReactionService(newReactionId = { ChatFixtures.freshUuid() }, now = { NOW })
        val rawText = ReactionParser.buildDmReactionText("🎉", older.text, older.reactionTimestamp)
        val parsed = assertNotNull(ReactionParser.parseDM(rawText))
        service.queuePendingDmReaction(parsed, contact.id, "Bob", rawText, contact.radioID)

        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows = listOf(older) + newer),
            currentContact = { contact },
            indexPageForReactions = indexer(service, reactionStore),
        )
        viewModel.loadInitial()
        runCurrent()
        assertTrue(reactionStore.reactions.isEmpty(), "the first page never held the target")

        viewModel.loadOlderMessages()
        runCurrent()

        val persisted = reactionStore.reactions.singleOrNull()
        assertNotNull(persisted, "the older page must be indexed too")
        assertEquals(older.id, persisted.messageID)
    }

    @Test
    fun `pages are handed to the indexer unfiltered`() = runTest {
        val plain = dm("Hello world", TIMESTAMP)
        val outgoingReaction = dm(
            ReactionParser.buildDmReactionText("👍", plain.text, plain.reactionTimestamp),
            TIMESTAMP + 1,
        )
        val seen = mutableListOf<MessageDto>()
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows = listOf(plain, outgoingReaction)),
            currentContact = { contact },
            indexPageForReactions = { page -> seen += page },
        )

        viewModel.loadInitial()
        runCurrent()

        assertEquals(listOf(plain.id, outgoingReaction.id), seen.map { it.id })
        assertEquals(1, viewModel.renderState.value.items.size, "the outgoing reaction still renders as a badge")
    }

    private companion object {
        const val TIMESTAMP = 1_704_067_200L
        const val NOW = 1_704_067_300.0

        /** Enough newer rows that a first page of [ChatCoordinator.PAGE_SIZE] cannot reach the target. */
        const val NEWER_ROWS = ChatCoordinator.PAGE_SIZE
    }
}
