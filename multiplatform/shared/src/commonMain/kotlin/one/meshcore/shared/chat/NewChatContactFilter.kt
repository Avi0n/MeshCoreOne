package one.meshcore.shared.chat

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.map.containsIgnoringCaseAndDiacritics
import one.meshcore.shared.model.ContactType

/**
 * The new-chat picker's contact filter (spec §12.1): a DM can be started only with an identity (chat)
 * contact, so repeaters and rooms are excluded along with blocked contacts, then an optional
 * case- and diacritic-insensitive name search narrows the result. Mirrors the iOS
 * `NewChatView.filteredContacts` computed property; kept a pure function so the eligibility and search
 * rules are unit-tested without a view model or Compose.
 */
object NewChatContactFilter {
    /**
     * The DM-capable contacts matching [searchText]. Eligibility drops blocked, repeater, and room
     * contacts; a blank [searchText] returns every eligible contact, otherwise it keeps those whose
     * [displayName] contains the query the way iOS `localizedStandardContains` does.
     */
    fun eligibleContacts(contacts: List<ContactDto>, searchText: String): List<ContactDto> {
        val eligible = contacts.filter {
            !it.isBlocked && it.contactType != ContactType.REPEATER && it.contactType != ContactType.ROOM
        }
        if (searchText.isEmpty()) return eligible
        return eligible.filter { it.displayName.containsIgnoringCaseAndDiacritics(searchText) }
    }
}
