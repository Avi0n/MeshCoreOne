// AndroidOnly: WP-216 behavior checks for ReactionService queue eviction, persistence error paths and reaction-row visibility beyond the Swift suite.
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.services.reactions.ReactionsFixtures.blocking
import com.meshcoreone.android.core.services.reactions.ReactionsFixtures.newRadioId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ReactionServiceBehaviorTest {
    private val stamp: UInt = 1_704_067_200u

    private fun channelReaction(service: ReactionService, emoji: String, target: String, text: String): ParsedReaction =
        requireNotNull(ReactionParser.parse(service.buildReactionText(emoji, target, text, stamp)))

    @TestFactory
    fun pendingQueue(): List<DynamicTest> = listOf(
        test("pending channel queue is capped at 100, evicting the oldest reaction of the oldest key") {
            val service = ReactionService(ReactionsTickingClock())
            val radioId = newRadioId()
            // Two reactions on the oldest key, then 99 single reactions on distinct keys: 101 queued.
            repeat(2) { service.queuePendingReaction(channelReaction(service, "👍", "Old", "first"), 0u, "R$it", "raw", radioId) }
            for (index in 0 until 99) service.queuePendingReaction(channelReaction(service, "👍", "N$index", "t$index"), 0u, "S", "raw", radioId)
            assertEquals(100 to 0, service.pendingCounts)
            // The oldest key keeps its newer reaction only.
            val oldest = service.indexMessage(UUID.randomUUID(), 0u, "Old", "first", stamp)
            assertEquals(listOf("R1"), oldest.map { it.senderNodeName })
            assertEquals(1, service.indexMessage(UUID.randomUUID(), 0u, "N0", "t0", stamp).size)
        },
        test("pending DM queue is capped at 100 and drops whole oldest keys first") {
            val service = ReactionService(ReactionsTickingClock())
            val contacts = List(101) { UUID.randomUUID() }
            val reaction = requireNotNull(ReactionParser.parseDM(service.buildDMReactionText("👍", "hello", stamp)))
            contacts.forEach { service.queuePendingDMReaction(reaction, it, "Alice", "raw", newRadioId()) }
            assertEquals(0 to 100, service.pendingCounts)
            assertTrue(service.indexDMMessage(UUID.randomUUID(), contacts.first(), "hello", stamp).isEmpty())
            assertEquals(1, service.indexDMMessage(UUID.randomUUID(), contacts.last(), "hello", stamp).size)
        },
        test("channel and DM caps are independent and clear empties both") {
            val service = ReactionService(ReactionsTickingClock())
            val dm = requireNotNull(ReactionParser.parseDM(service.buildDMReactionText("👍", "hello", stamp)))
            repeat(100) { service.queuePendingReaction(channelReaction(service, "👍", "N$it", "t"), 1u, "S", "raw", newRadioId()) }
            repeat(100) { service.queuePendingDMReaction(dm, UUID.randomUUID(), "A", "raw", newRadioId()) }
            assertEquals(100 to 100, service.pendingCounts)
            service.clearPendingReactions()
            assertEquals(0 to 0, service.pendingCounts)
        },
        test("queued reactions carry the injected clock's receive time and the raw wire text") {
            val start = Instant.ofEpochSecond(1_234_567)
            val service = ReactionService(ReactionsTickingClock(start))
            val radioId = newRadioId()
            val raw = service.buildReactionText("🎉", "Alpha", "hi", stamp)
            service.queuePendingReaction(requireNotNull(ReactionParser.parse(raw)), 4u, "Beta", raw, radioId)
            val match = service.indexMessage(UUID.randomUUID(), 4u, "Alpha", "hi", stamp).single()
            assertEquals(start, match.receivedAt)
            assertEquals(raw, match.rawText)
            assertEquals(radioId, match.radioId)
            assertEquals(4u.toUByte(), match.channelIndex)
        },
        test("pending and cached sender keys compare by canonical equivalence like Swift String hashing") {
            val service = ReactionService(ReactionsTickingClock())
            val raw = service.buildReactionText("👍", "José", "hola", stamp)
            service.queuePendingReaction(requireNotNull(ReactionParser.parse(raw)), 0u, "Beta", raw, newRadioId())
            val messageID = UUID.randomUUID()
            assertEquals(1, service.indexMessage(messageID, 0u, "Jose\u0301", "hola", stamp).size)
            assertEquals(messageID, service.findTargetMessage(ParsedReaction("👍", "José", ReactionParser.generateMessageHash("hola", stamp)), 0u))
        },
        test("equal-clock collisions keep the first identity in either insertion order within each channel and contact") {
            val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val contact = UUID.fromString("00000000-0000-0000-0000-000000000003")
            val otherContact = UUID.fromString("00000000-0000-0000-0000-000000000004")
            val hash = ReactionParser.generateMessageHash("Same message", stamp)
            val parsed = ParsedReaction("👍", "Node", hash)
            for (order in listOf(listOf(first, second), listOf(second, first))) {
                val service = ReactionService(Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC))
                order.forEach {
                    service.indexMessage(it, 0u, "Node", "Same message", stamp)
                    service.indexDMMessage(it, contact, "Same message", stamp)
                }
                service.indexMessage(order.last(), 1u, "Node", "Same message", stamp)
                service.indexDMMessage(order.last(), otherContact, "Same message", stamp)
                assertEquals(order.first(), service.findTargetMessage(parsed, 0u))
                assertEquals(order.last(), service.findTargetMessage(parsed, 1u))
                assertNull(service.findTargetMessage(parsed, 2u))
                assertEquals(order.first(), service.findDMTargetMessage(hash, contact))
                assertEquals(order.last(), service.findDMTargetMessage(hash, otherContact))
                assertNull(service.findDMTargetMessage(hash, first))
            }
        },
        test("distinct-clock collisions select the newest channel and DM identity in either insertion order") {
            val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val contact = UUID.fromString("00000000-0000-0000-0000-000000000003")
            val hash = ReactionParser.generateMessageHash("Same message", stamp)
            for (order in listOf(listOf(first, second), listOf(second, first))) {
                val service = ReactionService(ReactionsTickingClock())
                order.forEach {
                    service.indexMessage(it, 0u, "Node", "Same message", stamp)
                    service.indexDMMessage(it, contact, "Same message", stamp)
                }
                assertEquals(order.last(), service.findTargetMessage(ParsedReaction("👍", "Node", hash), 0u))
                assertEquals(order.last(), service.findDMTargetMessage(hash, contact))
            }
        },
        test("matched reactions leave the queue and target lookup is scoped by sender") {
            val service = ReactionService(ReactionsTickingClock())
            val raw = service.buildReactionText("👍", "Alpha", "hi", stamp)
            service.queuePendingReaction(requireNotNull(ReactionParser.parse(raw)), 0u, "Beta", raw, newRadioId())
            assertEquals(1, service.indexMessage(UUID.randomUUID(), 0u, "Alpha", "hi", stamp).size)
            assertTrue(service.indexMessage(UUID.randomUUID(), 0u, "Alpha", "hi", stamp).isEmpty())
            assertNull(service.findTargetMessage(ParsedReaction("👍", "Gamma", ReactionParser.generateMessageHash("hi", stamp)), 0u))
            assertEquals(ReactionParser.parse(raw), service.tryProcessAsReaction(raw))
            assertNull(service.tryProcessAsReaction("plain text"))
        },
    )

    private fun reaction(messageID: UUID, emoji: String, sender: String, receivedAt: Instant, radio: RadioId) =
        ReactionDTO(messageID = messageID, emoji = emoji, senderName = sender, messageHash = "abcdefgh", rawText = "raw", receivedAt = receivedAt, radioId = radio)

    @TestFactory
    fun persistence(): List<DynamicTest> = listOf(
        test("persistReactionAndUpdateSummary saves, rebuilds the summary from stored reactions and writes it back") {
            blocking {
                val store = ReactionsInMemoryReactionStore()
                val radioId = newRadioId()
                val messageID = UUID.randomUUID()
                val base = Instant.ofEpochSecond(1_700_000_000)
                store.saveReaction(reaction(messageID, "❤\uFE0F", "A", base, radioId))
                store.saveReaction(reaction(messageID, "👍", "B", base.plusSeconds(1), radioId))
                val result = ReactionService().persistReactionAndUpdateSummary(reaction(messageID, "👍", "C", base.plusSeconds(2), radioId), store)
                assertEquals(ReactionPersistResult(messageID, "👍:2,❤\uFE0F:1"), result)
                assertEquals("👍:2,❤\uFE0F:1", store.summary(EntityKey(radioId, messageID)))
            }
        },
        test("persistReactionAndUpdateSummary returns nil and stops at the failing store step") {
            blocking {
                for (failing in listOf("saveReaction", "fetchReactions", "updateMessageReactionSummary")) {
                    val store = ReactionsInMemoryReactionStore()
                    store.failOn(failing)
                    val dto = reaction(UUID.randomUUID(), "👍", "A", Instant.now(), newRadioId())
                    assertNull(ReactionService().persistReactionAndUpdateSummary(dto, store), failing)
                    assertEquals(failing, store.recordedCalls.last(), "no store call after the failure")
                    assertFalse(store.hasSummaryUpdate && failing != "updateMessageReactionSummary")
                }
            }
        },
        test("persistReactionAndUpdateSummary lets cancellation propagate") {
            blocking {
                val store = ReactionsInMemoryReactionStore()
                store.failOn("fetchReactions") { CancellationException("cancelled") }
                assertFailsWith<CancellationException> {
                    ReactionService().persistReactionAndUpdateSummary(reaction(UUID.randomUUID(), "👍", "A", Instant.now(), newRadioId()), store)
                }
            }
        },
    )

    @TestFactory
    fun visibility(): List<DynamicTest> {
        val dm = ReactionsFixtures.testChannelMessage(text = "👍\nabcdefgh", status = MessageStatus.SENT).copy(channelIndex = null, contactID = UUID.randomUUID())
        val channel = ReactionsFixtures.testChannelMessage(text = "@[Alice]👍\nabcdefgh", status = MessageStatus.SENT)
        return listOf(
            test("sent outgoing reactions are hidden only in their own conversation kind") {
                assertTrue(dm.isHiddenOutgoingReaction(isDM = true))
                assertFalse(dm.isHiddenOutgoingReaction(isDM = false))
                assertTrue(channel.isHiddenOutgoingReaction(isDM = false))
                assertFalse(channel.isHiddenOutgoingReaction(isDM = true))
                assertTrue(channel.copy(text = "👍@[Alice]\nabcdefgh").isHiddenOutgoingReaction(isDM = false))
            },
            test("failed and incoming reactions and non-reaction text stay visible") {
                assertFalse(dm.copy(status = MessageStatus.FAILED).isHiddenOutgoingReaction(isDM = true))
                for (status in MessageStatus.entries - MessageStatus.FAILED) {
                    assertTrue(dm.copy(status = status).isHiddenOutgoingReaction(isDM = true), "$status")
                }
                assertFalse(dm.copy(direction = MessageDirection.INCOMING).isHiddenOutgoingReaction(isDM = true))
                assertFalse(dm.copy(text = "hello").isHiddenOutgoingReaction(isDM = true))
                assertFalse(channel.copy(text = "@[Alice]🎉 on my way\npassword").isHiddenOutgoingReaction(isDM = false))
                // meshcore-open formats are receive-only and never hide a row.
                assertFalse(dm.copy(text = "r:a1b2:00").isHiddenOutgoingReaction(isDM = true))
            },
        )
    }

    private fun test(name: String, body: () -> Unit): DynamicTest = DynamicTest.dynamicTest("WP-216::$name", body)
}
