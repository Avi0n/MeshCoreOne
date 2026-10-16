package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.ChannelReactionWire
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactionTimestampWindowTest {
    private fun handler(store: ReactionStore) = ReactionHandler(
        ReactionService(newReactionId = { ChatFixtures.freshUuid() }, now = { 0.0 }),
        store,
        RecordingSummarySink(),
        newReactionId = { ChatFixtures.freshUuid() },
        now = { 0.0 },
        edges = ReactionHandlerEdges(
            unknownSenderLabel = { "Unknown" },
            notifier = ReactionNotifier.NoOp,
            localNodeName = { null },
        ),
    )

    @Test
    fun legacyMaximumTimestampQueriesOnlyUInt32DomainForBothReceivePaths() = runTest {
        val windows = mutableListOf<LongRange>()
        val backing = InMemoryReactionStore()
        val store = object : ReactionStore by backing {
            override suspend fun fetchDirectMessageCandidates(
                radioID: Uuid,
                contactID: Uuid,
                window: LongRange,
                limit: Int,
            ): List<MessageDto> {
                windows += window
                return emptyList()
            }

            override suspend fun fetchChannelMessageCandidates(
                radioID: Uuid,
                channelIndex: Int,
                window: LongRange,
                limit: Int,
            ): List<MessageDto> {
                windows += window
                return emptyList()
            }
        }
        val handler = handler(store)
        val raw = "r:4294967295000_1_1:👍"
        assertTrue(handler.handleDirectReaction(raw, ChatFixtures.contact("Alice").resolved(), ChatFixtures.RADIO))
        assertTrue(
            handler.handleChannelReaction(
                ChannelReactionWire(raw, 0, "Alice", "Me", 0.0, ChatFixtures.RADIO),
            ),
        )
        assertEquals(listOf((MAX_TIMESTAMP - WINDOW)..MAX_TIMESTAMP, (MAX_TIMESTAMP - WINDOW)..MAX_TIMESTAMP), windows)
        assertTrue(backing.reactions.isEmpty())
    }

    @Test
    fun timestampWindowsAreInclusiveBoundedAndSafeForOutOfDomainAnchors() {
        val handler = handler(InMemoryReactionStore())
        for ((anchor, expected) in listOf(
            Long.MIN_VALUE to 0L..WINDOW,
            0L to 0L..WINDOW,
            WINDOW to 0L..(WINDOW * 2),
            (MAX_TIMESTAMP - WINDOW) to (MAX_TIMESTAMP - WINDOW * 2)..MAX_TIMESTAMP,
            MAX_TIMESTAMP to (MAX_TIMESTAMP - WINDOW)..MAX_TIMESTAMP,
            Long.MAX_VALUE to (MAX_TIMESTAMP - WINDOW)..MAX_TIMESTAMP,
        )) {
            assertEquals(expected, handler.window(anchor), "anchor=$anchor")
            assertTrue(expected.first in handler.window(anchor))
            assertTrue(expected.last in handler.window(anchor))
        }
    }

    private companion object {
        const val MAX_TIMESTAMP = 0xFFFF_FFFFL
        const val WINDOW = 300L
    }
}
