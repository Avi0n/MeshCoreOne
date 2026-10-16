package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReactionPersistCancellationTest {
    @Test
    fun aCancelledExistenceCheckDoesNotWrite() = runTest {
        val backing = InMemoryReactionStore()
        val cancelled = CancellationException("stopped")
        val store = object : ReactionStore by backing {
            override suspend fun reactionExists(messageID: Uuid, senderName: String, emoji: String): Boolean =
                throw cancelled
        }
        val sink = RecordingSummarySink()
        val thrown = assertFailsWith<CancellationException> {
            ReactionPersister().persistReactionIfNew(sampleReaction(), store, sink)
        }
        assertSame(cancelled, thrown)
        assertTrue(backing.reactions.isEmpty())
        assertTrue(sink.updates.isEmpty())
    }

    @Test
    fun aCancelledSaveRethrowsTheSameException() = runTest {
        val cancelled = CancellationException("stopped")
        val store = object : ReactionStore by InMemoryReactionStore() {
            override suspend fun saveReaction(reaction: ReactionDto): Unit = throw cancelled
        }
        val thrown = assertFailsWith<CancellationException> {
            ReactionPersister().persistReactionAndUpdateSummary(sampleReaction(), store)
        }
        assertSame(cancelled, thrown)
        assertEquals("stopped", thrown.message)
    }

    @Test
    fun aSaveExceptionDoesNotNotify() = runTest {
        val store = object : ReactionStore by InMemoryReactionStore() {
            override suspend fun saveReaction(reaction: ReactionDto) {
                error("room down")
            }
        }
        val sink = RecordingSummarySink()
        assertFalse(ReactionPersister().persistReactionIfNew(sampleReaction(), store, sink))
        assertTrue(sink.updates.isEmpty())
    }

    @Test
    fun aSaveErrorPropagates() = runTest {
        val probe = PersistProbeError()
        val store = object : ReactionStore by InMemoryReactionStore() {
            override suspend fun saveReaction(reaction: ReactionDto): Unit = throw probe
        }
        val thrown = assertFailsWith<PersistProbeError> {
            ReactionPersister().persistReactionAndUpdateSummary(sampleReaction(), store)
        }
        assertIs<PersistProbeError>(thrown)
    }

    private class PersistProbeError : Error("probe")
}

private fun sampleReaction(): ReactionDto {
    val id = ChatFixtures.freshUuid()
    return ReactionDto(
        id = id,
        messageID = ChatFixtures.freshUuid(),
        emoji = "👍",
        senderName = "Bob",
        messageHash = "abcd1234",
        rawText = ":+1:",
        receivedAt = 1.0,
        radioID = ChatFixtures.RADIO,
        channelIndex = 0,
    )
}
