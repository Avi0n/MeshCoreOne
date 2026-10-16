package one.meshcore.shared.settings

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.hasTelemetryPermissions
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid

/**
 * Pure helpers for the Trusted Contacts picker (telemetry permission grant list).
 * Chat contacts only; search is case-insensitive substring on display name.
 */
object TrustedContactsFilter {
    fun chatContacts(contacts: List<ContactDto>): List<ContactDto> =
        contacts.filter { it.contactType == ContactType.CHAT }

    fun filter(contacts: List<ContactDto>, searchText: String, favoritesOnly: Boolean): List<ContactDto> {
        var result = chatContacts(contacts)
        if (favoritesOnly) {
            result = result.filter { it.isFavorite }
        }
        val query = searchText.trim()
        if (query.isNotEmpty()) {
            result = result.filter { it.displayName.contains(query, ignoreCase = true) }
        }
        return result.sortedBy { it.displayName.lowercase() }
    }

    fun trustedIds(contacts: List<ContactDto>): Set<Uuid> = chatContacts(contacts)
        .filter { hasTelemetryPermissions(it.flags) }
        .map { it.id }
        .toSet()

    /** A selected row leaves the pending set; an unselected row joins it. */
    fun toggledTrustedIds(selected: Boolean, pending: Set<Uuid>, id: Uuid): Set<Uuid> =
        if (selected) pending - id else pending + id
}
