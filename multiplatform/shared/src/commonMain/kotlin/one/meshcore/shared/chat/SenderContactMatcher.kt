package one.meshcore.shared.chat

import one.meshcore.shared.backup.ContactDto

/**
 * Resolves a channel sender's display name to stored contacts. Channel messages carry a human-readable
 * sender name rather than a stable id, so acting on the sender (Send DM, Block Sender) relies on
 * case-insensitive name matching against the local contact list. Mirrors Swift `SenderContactMatcher`.
 */
object SenderContactMatcher {
    /**
     * Contacts whose [ContactDto.name] equals [senderName] case-insensitively. No trimming: leading or
     * trailing whitespace does not match. When [excludeBlocked] is true, blocked contacts are dropped.
     *
     * Swift uses `localizedCaseInsensitiveCompare`; this uses Kotlin's `equals(ignoreCase = true)`,
     * which agrees for the ASCII names in practice but is not locale-tailored (e.g. Turkish dotless i).
     */
    fun filter(contacts: List<ContactDto>, senderName: String, excludeBlocked: Boolean = false): List<ContactDto> =
        contacts.filter { contact ->
            (!excludeBlocked || !contact.isBlocked) && contact.name.equals(senderName, ignoreCase = true)
        }
}
