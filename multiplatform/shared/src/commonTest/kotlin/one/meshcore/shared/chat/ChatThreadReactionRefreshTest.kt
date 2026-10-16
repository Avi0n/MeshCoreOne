package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.chat.reactions.InMemoryReactionStore
import one.meshcore.shared.chat.reactions.ReactionService
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reaction that lands while its thread is open must repaint that row. The reaction service publishes
 * every summary rewrite and the thread folds it into a row reload, the Kotlin shape of the iOS
 * `.reactionReceived` -> reload event.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadReactionRefreshTest {
    private val target = ChatFixtures.message(
        text = "on my way",
        timestamp = 1_000L,
        createdAt = 1_000.0,
        direction = MessageDirection.OUTGOING,
        status = MessageStatus.SENT,
        contactID = ChatFixtures.CONTACT,
    )

    @Test
    fun `a persisted reaction repaints the open row`() = runTest {
        val store = FakeChatThreadStore(rows = listOf(target))
        val reactions = ReactionService(newReactionId = { ChatFixtures.freshUuid() }, now = { NOW })
        val (viewModel, _) = makeThreadViewModel(store = store, reactionService = { reactions })

        viewModel.start(emptyFlow(), emptyFlow())
        viewModel.loadInitial()
        runCurrent()
        assertTrue(
            viewModel.renderState.value.items.single().content.none { it is MessageFragment.ReactionSummary },
            "no badge before the reaction lands",
        )

        // The persist writes the summary to the row; the thread must see it without being torn down.
        store.rows = listOf(target.copy(reactionSummary = SUMMARY))
        reactions.persistReactionAndUpdateSummary(reaction(), InMemoryReactionStore(listOf(target)))
        runCurrent()

        val fragments = viewModel.renderState.value.items.single().content
        assertEquals(
            listOf(MessageFragment.ReactionSummary(SUMMARY)),
            fragments.filterIsInstance<MessageFragment.ReactionSummary>(),
        )
    }

    private fun reaction() = ReactionDto(
        id = ChatFixtures.freshUuid(),
        messageID = target.id,
        emoji = EMOJI,
        senderName = "Alice",
        messageHash = "ABCDEFGH",
        rawText = "$EMOJI\nABCDEFGH",
        receivedAt = NOW,
        radioID = ChatFixtures.RADIO,
        channelIndex = null,
        contactID = ChatFixtures.CONTACT,
    )

    private companion object {
        const val NOW = 1_700_000_000.0
        const val EMOJI = "👍"
        const val SUMMARY = "$EMOJI 1"
    }
}
