package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of the Swift `ReactionServiceTests`: wire build, cache lookup/recency, pending match/scope/clear. */
class ReactionServiceTest {
    private var clock = 0.0

    private fun service() = ReactionService(newReactionId = { ChatFixtures.freshUuid() }, now = { clock })

    private fun channelReactionText(emoji: String, sender: String, text: String, ts: Long) =
        ReactionParser.buildReactionText(emoji, sender, text, ts)

    // MARK: - Wire format

    @Test
    fun buildsChannelWireFormat() {
        val text = service().buildReactionText("👍", "AlphaNode", "What's the situation?", 1_704_067_200)
        assertTrue(text.startsWith("@[AlphaNode]👍\n"))
        val hash = text.substringAfterLast('\n')
        assertEquals(8, hash.length)
        assertTrue(CrockfordBase32.isValid(hash))
    }

    @Test
    fun generatedIdentifierIsConsistent() {
        val s = service()
        assertEquals(
            s.buildReactionText("👍", "Node", "Hello world", 1_704_067_200),
            s.buildReactionText("👍", "Node", "Hello world", 1_704_067_200),
        )
    }

    @Test
    fun differentTimestampsProduceDifferentIdentifiers() {
        val s = service()
        assertTrue(
            s.buildReactionText("👍", "Node", "Hello world", 1_704_067_200) !=
                s.buildReactionText("👍", "Node", "Hello world", 1_704_067_201),
        )
    }

    @Test
    fun buildsDmWireFormat() {
        val text = service().buildDmReactionText("👍", "Hello world", 1_704_067_200)
        assertTrue(text.startsWith("👍\n"))
        assertTrue(!text.contains("@["))
    }

    // MARK: - Cache lookup

    @Test
    fun findsIndexedMessageByHashAndSender() = runTest {
        val s = service()
        val messageID = ChatFixtures.freshUuid()
        s.indexMessage(messageID, 0, "Node", "Hello world", 1_704_067_200)
        val text = channelReactionText("👍", "Node", "Hello world", 1_704_067_200)
        val parsed = assertNotNull(ReactionParser.parse(text))
        assertEquals(messageID, s.findTargetMessage(parsed, 0))
    }

    @Test
    fun returnsNullWhenNoCandidatesExist() = runTest {
        val parsed = ParsedReaction("👍", "Node", "abcd1234")
        assertNull(service().findTargetMessage(parsed, 0))
    }

    @Test
    fun returnsMostRecentlyIndexedOnHashCollision() = runTest {
        val s = service()
        val id1 = ChatFixtures.freshUuid()
        val id2 = ChatFixtures.freshUuid()
        s.indexMessage(id1, 0, "Node", "Same message", 1_704_067_200)
        s.indexMessage(id2, 0, "Node", "Same message", 1_704_067_200)
        val text = channelReactionText("👍", "Node", "Same message", 1_704_067_200)
        val parsed = assertNotNull(ReactionParser.parse(text))
        assertEquals(id2, s.findTargetMessage(parsed, 0))
    }

    // MARK: - Pending queue

    @Test
    fun queuedReactionMatchesWhenMessageIndexed() = runTest {
        val s = service()
        val text = s.buildReactionText("👍", "AlphaNode", "Hello world", 1_704_067_200)
        val parsed = assertNotNull(ReactionParser.parse(text))
        s.queuePendingReaction(parsed, 0, "BetaNode", text, ChatFixtures.RADIO)

        val matches = s.indexMessage(ChatFixtures.freshUuid(), 0, "AlphaNode", "Hello world", 1_704_067_200)
        assertEquals(1, matches.size)
        assertEquals("👍", matches.first().parsed.emoji)
        assertEquals("BetaNode", matches.first().senderNodeName)
    }

    @Test
    fun multipleReactionsForSameTargetAllMatch() = runTest {
        val s = service()
        for (emoji in listOf("👍", "❤️", "😂")) {
            val text = s.buildReactionText(emoji, "AlphaNode", "Hello world", 1_704_067_200)
            s.queuePendingReaction(assertNotNull(ReactionParser.parse(text)), 0, "BetaNode", text, ChatFixtures.RADIO)
        }
        val matches = s.indexMessage(ChatFixtures.freshUuid(), 0, "AlphaNode", "Hello world", 1_704_067_200)
        assertEquals(setOf("👍", "❤️", "😂"), matches.map { it.parsed.emoji }.toSet())
    }

    @Test
    fun hashMismatchPreventsFalseMatch() = runTest {
        val s = service()
        val text = s.buildReactionText("👍", "AlphaNode", "Hello world", 1_704_067_200)
        s.queuePendingReaction(assertNotNull(ReactionParser.parse(text)), 0, "BetaNode", text, ChatFixtures.RADIO)
        val matches = s.indexMessage(ChatFixtures.freshUuid(), 0, "AlphaNode", "Different text", 1_704_067_200)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun pendingQueueDropsOldestTargetWhenOverCapacity() = runTest {
        val s = service()
        val base = 1_704_067_200L
        val count = ReactionConstants.MAX_PENDING_REACTIONS + 1
        repeat(count) { index ->
            val text = s.buildReactionText("👍", "AlphaNode", "Hello $index", base + index)
            s.queuePendingReaction(
                assertNotNull(ReactionParser.parse(text)),
                0,
                "BetaNode",
                text,
                ChatFixtures.RADIO,
            )
        }
        assertTrue(s.indexMessage(ChatFixtures.freshUuid(), 0, "AlphaNode", "Hello 0", base).isEmpty())
        val newest = count - 1
        assertEquals(
            1,
            s.indexMessage(ChatFixtures.freshUuid(), 0, "AlphaNode", "Hello $newest", base + newest).size,
        )
    }

    @Test
    fun pendingQueueDropsOldestEntryWhenOneTargetOverflows() = runTest {
        val s = service()
        val count = ReactionConstants.MAX_PENDING_REACTIONS + 1
        repeat(count) { index ->
            val text = s.buildReactionText("👍", "AlphaNode", "Hello world", 1_704_067_200)
            s.queuePendingReaction(
                assertNotNull(ReactionParser.parse(text)),
                0,
                "Beta$index",
                text,
                ChatFixtures.RADIO,
            )
        }
        val matches = s.indexMessage(ChatFixtures.freshUuid(), 0, "AlphaNode", "Hello world", 1_704_067_200)
        assertEquals(ReactionConstants.MAX_PENDING_REACTIONS, matches.size)
        assertTrue(matches.none { it.senderNodeName == "Beta0" })
        assertEquals("Beta${count - 1}", matches.last().senderNodeName)
    }

    @Test
    fun clearRemovesAllPendingReactions() = runTest {
        val s = service()
        val text = s.buildReactionText("👍", "AlphaNode", "Hello world", 1_704_067_200)
        s.queuePendingReaction(assertNotNull(ReactionParser.parse(text)), 0, "BetaNode", text, ChatFixtures.RADIO)
        s.clearPendingReactions()
        val matches = s.indexMessage(ChatFixtures.freshUuid(), 0, "AlphaNode", "Hello world", 1_704_067_200)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun pendingReactionsScopedByChannel() = runTest {
        val s = service()
        val text = s.buildReactionText("👍", "AlphaNode", "Hello world", 1_704_067_200)
        s.queuePendingReaction(assertNotNull(ReactionParser.parse(text)), 0, "BetaNode", text, ChatFixtures.RADIO)
        assertTrue(s.indexMessage(ChatFixtures.freshUuid(), 1, "AlphaNode", "Hello world", 1_704_067_200).isEmpty())
        assertEquals(1, s.indexMessage(ChatFixtures.freshUuid(), 0, "AlphaNode", "Hello world", 1_704_067_200).size)
    }

    // MARK: - DM

    @Test
    fun indexesDmMessageAndFindsByHash() = runTest {
        val s = service()
        val messageID = ChatFixtures.freshUuid()
        val contactID = ChatFixtures.freshUuid()
        s.indexDmMessage(messageID, contactID, "Hello world", 1_704_067_200)
        val hash = ReactionParser.generateMessageHash("Hello world", 1_704_067_200)
        assertEquals(messageID, s.findDmTargetMessage(hash, contactID))
    }

    @Test
    fun dmPendingReactionsMatchWhenMessageIndexed() = runTest {
        val s = service()
        val contactID = ChatFixtures.freshUuid()
        val text = s.buildDmReactionText("👍", "Hello world", 1_704_067_200)
        val parsed = assertNotNull(ReactionParser.parseDM(text))
        s.queuePendingDmReaction(parsed, contactID, "Alice", text, ChatFixtures.RADIO)
        val matches = s.indexDmMessage(ChatFixtures.freshUuid(), contactID, "Hello world", 1_704_067_200)
        assertEquals(1, matches.size)
        assertEquals("👍", matches.first().parsed.emoji)
    }

    @Test
    fun dmReactionsScopedByContact() = runTest {
        val s = service()
        val contact1 = ChatFixtures.freshUuid()
        val contact2 = ChatFixtures.freshUuid()
        val text = s.buildDmReactionText("👍", "Hello world", 1_704_067_200)
        val parsed = assertNotNull(ReactionParser.parseDM(text))
        s.queuePendingDmReaction(parsed, contact1, "Alice", text, ChatFixtures.RADIO)
        assertTrue(s.indexDmMessage(ChatFixtures.freshUuid(), contact2, "Hello world", 1_704_067_200).isEmpty())
        assertEquals(1, s.indexDmMessage(ChatFixtures.freshUuid(), contact1, "Hello world", 1_704_067_200).size)
    }

    @Test
    fun dmReturnsNullWhenNoCandidatesInCache() = runTest {
        assertNull(service().findDmTargetMessage("abcd1234", ChatFixtures.freshUuid()))
    }

    @Test
    fun clearRemovesDmPendingReactions() = runTest {
        val s = service()
        val contactID = ChatFixtures.freshUuid()
        val text = s.buildDmReactionText("👍", "Hello world", 1_704_067_200)
        val parsed = assertNotNull(ReactionParser.parseDM(text))
        s.queuePendingDmReaction(parsed, contactID, "Alice", text, ChatFixtures.RADIO)
        s.clearPendingReactions()
        assertTrue(s.indexDmMessage(ChatFixtures.freshUuid(), contactID, "Hello world", 1_704_067_200).isEmpty())
    }
}
