// PortedFrom: MC1Services/Tests/MC1ServicesTests/BlockedChannelSenderPersistenceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/BlockedChannelSender.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.model.BlockedChannelSenderDTO
import com.meshcoreone.android.core.model.RadioId
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BlockedSenderStoreOriginalPredicateTest : RoomProcessTest() {
    private val radio = RadioId(UUID.randomUUID())
    private val now = Instant.ofEpochSecond(1_704_067_200)
    // Independent Swift Date value-type facts: WP-202/independent-vector-provenance.json.
    private val distantPast = Instant.ofEpochSecond(-62_135_769_600)

    @Test fun originalDistantPastUpsertChangesTheDateWithoutDuplicatingTroll() = runTest {
        val first = blocked("Troll", date = distantPast)
        val second = blocked("Troll")
        assertNotEquals(first.id, second.id)
        store.saveBlockedChannelSender(first)
        assertEquals(listOf(first), store.fetchBlockedChannelSenders(radio).toList())

        store.saveBlockedChannelSender(second)

        val fetched = store.fetchBlockedChannelSenders(radio)
        assertEquals(1, fetched.size)
        assertEquals(now, fetched.firstOrNull()?.dateBlocked)
        assertEquals(listOf(first.copy(dateBlocked = now)), fetched.toList())
    }

    @Test fun originalBadGuyDeletionRequiresItsActualSeedAndEmptyResult() = runTest {
        val dto = blocked("BadGuy")
        store.saveBlockedChannelSender(dto)
        assertEquals(listOf(dto), store.fetchBlockedChannelSenders(radio).toList())

        store.deleteBlockedChannelSender(radio, "BadGuy")

        assertTrue(store.fetchBlockedChannelSenders(radio).isEmpty())
    }

    @Test fun originalRadioPartitionRequiresExactlyOneAliceAndOneBob() = runTest {
        val other = RadioId(UUID.randomUUID())
        val alice = blocked("Alice")
        val bob = blocked("Bob", radioId = other)
        store.saveBlockedChannelSender(alice)
        store.saveBlockedChannelSender(bob)

        val first = store.fetchBlockedChannelSenders(radio)
        val second = store.fetchBlockedChannelSenders(other)

        assertEquals(1, first.size)
        assertEquals("Alice", first.firstOrNull()?.name)
        assertEquals(1, second.size)
        assertEquals("Bob", second.firstOrNull()?.name)
        assertEquals(listOf(alice), first.toList())
        assertEquals(listOf(bob), second.toList())
    }

    @Test fun originalStandaloneAliceIsTheFirstDisplayNameNotMerelyAnyMatch() = runTest {
        val dto = blocked("Alice")
        store.saveBlockedChannelSender(dto)

        val fetched = store.fetchBlockedChannelSenders(radio)

        assertEquals("Alice", fetched.firstOrNull()?.name)
        assertEquals(listOf(dto), fetched.toList())
    }

    @Test fun originalDifferentCaseEntriesKeepBothDistantPastAndCurrentDtos() = runTest {
        val original = blocked("Alice", date = distantPast)
        val uppercase = blocked("ALICE")
        store.saveBlockedChannelSender(original)
        assertEquals(listOf(original), store.fetchBlockedChannelSenders(radio).toList())
        store.saveBlockedChannelSender(uppercase)

        val fetched = store.fetchBlockedChannelSenders(radio)

        assertEquals(2, fetched.size)
        assertEquals(setOf(original, uppercase), fetched.toSet())
    }

    @Test fun originalMillionSecondDatesSortSecondThirdFirst() = runTest {
        val first = blocked("first", date = Instant.ofEpochSecond(1_000_000))
        val second = blocked("second", date = Instant.ofEpochSecond(3_000_000))
        val third = blocked("third", date = Instant.ofEpochSecond(2_000_000))
        for (dto in listOf(first, second, third)) store.saveBlockedChannelSender(dto)

        val fetched = store.fetchBlockedChannelSenders(radio)

        assertEquals(listOf("second", "third", "first"), fetched.map { it.name })
        assertEquals(listOf(second, third, first), fetched.toList())
    }

    private fun blocked(name: String, radioId: RadioId = radio, date: Instant = now) =
        BlockedChannelSenderDTO(id = UUID.randomUUID(), name = name, radioId = radioId, dateBlocked = date)
}
