package one.meshcore.shared.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.text.MentionUtilities
import one.meshcore.shared.fixtures.loadRepoFixture
import one.meshcore.shared.model.ContactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Channel mention candidates: a participant with no contact row still becomes selectable, every heard name
 * contributes recency, and the ranking hands the most recently heard sender to the autocomplete first.
 * Mirrors the Swift `buildChannelSenders` / `addChannelSenderIfNew` and the `mentionSuggestions` merge.
 */
class ChannelSenderIndexTest {
    private fun heard(name: String?, timestamp: Long) =
        ChatFixtures.message(channelIndex = 1, senderNodeName = name, timestamp = timestamp)

    private fun savedContact(name: String) = ContactDto(
        id = ChatFixtures.freshUuid(),
        radioID = ChatFixtures.RADIO,
        publicKey = ByteArray(0),
        name = name,
        typeRawValue = ContactType.CHAT.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )

    @Test
    fun stableIdsMatchFrozenSwiftValuesForUtf8AndHashOverflow() {
        val fixture = Json.parseToJsonElement(loadRepoFixture("identity/stable-string-uuid.json")).jsonObject
        val vectors = fixture.getValue("vectors").jsonArray
        assertTrue(vectors.size >= 6)
        for (entry in vectors) {
            val row = entry.jsonObject
            val text = row.getValue("text").jsonPrimitive.content
            val expected = row.getValue("uuid").jsonPrimitive.content
            assertEquals(expected, stableUuid(text).value, text)
        }
    }

    @Test
    fun everyDistinctSenderBecomesOneCandidateWithItsLatestTimestamp() {
        val index = ChannelSenderIndex.from(
            listOf(heard("Alpha", 100), heard("Bravo", 200), heard("Alpha", 50)),
            ChatFixtures.RADIO,
        )

        assertEquals(listOf("Alpha", "Bravo"), index.senders.map { it.name })
        assertEquals(mapOf("Alpha" to 100u, "Bravo" to 200u), index.order)
    }

    @Test
    fun blankAndOverlongSenderNamesAreDropped() {
        val index = ChannelSenderIndex.from(
            listOf(heard(null, 1), heard("   ", 2), heard("x".repeat(129), 3), heard("  Alpha  ", 4)),
            ChatFixtures.RADIO,
        )

        assertEquals(listOf("Alpha"), index.senders.map { it.name })
    }

    @Test
    fun syntheticCandidateIsAChatContactWithAStableIdAcrossRebuilds() {
        val first = ChannelSenderIndex.from(listOf(heard("Alpha", 1)), ChatFixtures.RADIO)
        val second = ChannelSenderIndex.from(listOf(heard("Alpha", 2)), ChatFixtures.RADIO)

        val other = ChannelSenderIndex.from(listOf(heard("Bravo", 1)), ChatFixtures.RADIO)

        val candidate = first.senders.single()
        assertEquals(ContactType.CHAT.raw, candidate.typeRawValue)
        assertEquals(candidate.id, second.senders.single().id)
        assertNotEquals(candidate.id, other.senders.single().id)
    }

    @Test
    fun liveArrivalAddsANewSenderAndMaxMergesAnOlderTimestamp() {
        val index = ChannelSenderIndex.from(listOf(heard("Alpha", 100)), ChatFixtures.RADIO)
            .adding("Bravo", ChatFixtures.RADIO, 300)
            .adding("Alpha", ChatFixtures.RADIO, 50)

        assertEquals(listOf("Alpha", "Bravo"), index.senders.map { it.name })
        assertEquals(300u, index.order["Bravo"])
        assertEquals(100u, index.order["Alpha"])
    }

    @Test
    fun unsavedSenderIsMentionableAndRecencyOutranksAlphabeticalOrder() {
        val saved = listOf(savedContact("Zulu"))
        val index = ChannelSenderIndex.from(listOf(heard("Zulu", 100), heard("Alpha", 900)), ChatFixtures.RADIO)

        val savedNames = saved.mapTo(mutableSetOf()) { it.name }
        val candidates = saved + index.senders.filterNot { it.name in savedNames }
        val suggestions = MentionUtilities.filterContacts(candidates, query = "", senderOrder = index.order)

        assertEquals(listOf("Alpha", "Zulu"), suggestions.map { it.name })
        assertTrue(candidates.count { it.name == "Zulu" } == 1)
    }
}
