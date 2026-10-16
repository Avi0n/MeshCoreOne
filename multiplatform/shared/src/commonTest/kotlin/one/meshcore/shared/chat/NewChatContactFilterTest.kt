package one.meshcore.shared.chat

import one.meshcore.shared.model.ContactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `NewChatView.filteredContacts` rules (spec §12.1): eligibility drops blocked, repeater,
 * and room contacts; a blank search returns every eligible contact; a search matches the display name
 * case-insensitively.
 */
class NewChatContactFilterTest {
    private fun repeater(name: String) = ChatFixtures.contact(name = name).copy(typeRawValue = ContactType.REPEATER.raw)

    private fun room(name: String) = ChatFixtures.contact(name = name).copy(typeRawValue = ContactType.ROOM.raw)

    @Test
    fun blankSearchReturnsEveryEligibleChatContact() {
        val alice = ChatFixtures.contact(name = "Alice")
        val bob = ChatFixtures.contact(name = "Bob")
        val result = NewChatContactFilter.eligibleContacts(listOf(alice, bob), searchText = "")
        assertEquals(listOf(alice.id, bob.id), result.map { it.id })
    }

    @Test
    fun blockedContactsAreExcluded() {
        val visible = ChatFixtures.contact(name = "Alice")
        val blocked = ChatFixtures.contact(name = "Blocked", isBlocked = true)
        val result = NewChatContactFilter.eligibleContacts(listOf(visible, blocked), searchText = "")
        assertEquals(listOf(visible.id), result.map { it.id })
    }

    @Test
    fun repeaterAndRoomContactsAreExcluded() {
        val chat = ChatFixtures.contact(name = "Chat")
        val result = NewChatContactFilter.eligibleContacts(
            listOf(chat, repeater("Repeater"), room("Room")),
            searchText = "",
        )
        assertEquals(listOf(chat.id), result.map { it.id })
    }

    @Test
    fun searchMatchesDisplayNameCaseInsensitively() {
        val alice = ChatFixtures.contact(name = "Alice")
        val bob = ChatFixtures.contact(name = "Bob")
        val result = NewChatContactFilter.eligibleContacts(listOf(alice, bob), searchText = "ali")
        assertEquals(listOf(alice.id), result.map { it.id })
    }

    @Test
    fun searchMatchesDisplayNameIgnoringDiacritics() {
        val jose = ChatFixtures.contact(name = "José")
        val bob = ChatFixtures.contact(name = "Bob")
        val result = NewChatContactFilter.eligibleContacts(listOf(jose, bob), searchText = "jose")
        assertEquals(listOf(jose.id), result.map { it.id })
    }

    @Test
    fun searchMatchesNicknameWhenSet() {
        // displayName resolves to the nickname when present, so the search must match it, not the raw name.
        val contact = ChatFixtures.contact(name = "rawname", nickname = "Zed")
        val result = NewChatContactFilter.eligibleContacts(listOf(contact), searchText = "zed")
        assertEquals(listOf(contact.id), result.map { it.id })
    }

    @Test
    fun searchWithNoMatchReturnsEmpty() {
        val alice = ChatFixtures.contact(name = "Alice")
        assertTrue(NewChatContactFilter.eligibleContacts(listOf(alice), searchText = "zzz").isEmpty())
    }

    @Test
    fun searchStillExcludesIneligibleContacts() {
        // A repeater whose name matches the query must never surface — eligibility precedes search.
        val result = NewChatContactFilter.eligibleContacts(listOf(repeater("Match")), searchText = "match")
        assertTrue(result.isEmpty())
    }
}
