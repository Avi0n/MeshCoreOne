package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mirrors the iOS `SenderContactMatcherTests`: case-insensitive, no trimming, blocked exclusion. */
class SenderContactMatcherTest {
    @Test
    fun exactNameMatchReturnsTheContact() {
        val alice = ChatFixtures.contact(name = "Alice")
        val result = SenderContactMatcher.filter(contacts = listOf(alice), senderName = "Alice")
        assertEquals(listOf(alice.id), result.map { it.id })
    }

    @Test
    fun caseInsensitiveMatchReturnsTheContact() {
        val alice = ChatFixtures.contact(name = "Alice")
        val result = SenderContactMatcher.filter(contacts = listOf(alice), senderName = "ALICE")
        assertEquals(listOf(alice.id), result.map { it.id })
    }

    @Test
    fun nonMatchingNameReturnsEmpty() {
        val alice = ChatFixtures.contact(name = "Alice")
        assertTrue(SenderContactMatcher.filter(contacts = listOf(alice), senderName = "Bob").isEmpty())
    }

    @Test
    fun multipleContactsSharingANameAllMatch() {
        val alice1 = ChatFixtures.contact(name = "Alice")
        val alice2 = ChatFixtures.contact(name = "alice")
        val bob = ChatFixtures.contact(name = "Bob")
        val result = SenderContactMatcher.filter(contacts = listOf(alice1, alice2, bob), senderName = "Alice")
        assertEquals(setOf(alice1.id, alice2.id), result.map { it.id }.toSet())
    }

    @Test
    fun leadingOrTrailingWhitespaceDoesNotMatch() {
        val alice = ChatFixtures.contact(name = "Alice")
        assertTrue(SenderContactMatcher.filter(contacts = listOf(alice), senderName = " Alice ").isEmpty())
    }

    @Test
    fun excludeBlockedDropsBlockedContacts() {
        val unblocked = ChatFixtures.contact(name = "Alice", isBlocked = false)
        val blocked = ChatFixtures.contact(name = "Alice", isBlocked = true)
        val result = SenderContactMatcher.filter(
            contacts = listOf(unblocked, blocked),
            senderName = "Alice",
            excludeBlocked = true,
        )
        assertEquals(listOf(unblocked.id), result.map { it.id })
    }

    @Test
    fun excludeBlockedFalseKeepsBlockedContacts() {
        val unblocked = ChatFixtures.contact(name = "Alice", isBlocked = false)
        val blocked = ChatFixtures.contact(name = "Alice", isBlocked = true)
        val result = SenderContactMatcher.filter(contacts = listOf(unblocked, blocked), senderName = "Alice")
        assertEquals(setOf(unblocked.id, blocked.id), result.map { it.id }.toSet())
    }

    @Test
    fun emptyContactListReturnsEmpty() {
        assertTrue(SenderContactMatcher.filter(contacts = emptyList(), senderName = "Alice").isEmpty())
    }
}
