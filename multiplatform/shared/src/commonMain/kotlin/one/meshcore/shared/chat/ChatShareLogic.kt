package one.meshcore.shared.chat

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactShareToken
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.containsIgnoringCaseAndDiacritics
import one.meshcore.shared.map.formatCoordinatePair
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toUppercaseHex

/**
 * Pure helpers for the chat composer "+" share menu (iOS `ChatShareMenu` + `ShareContactPickerSheet`).
 * Formats share tokens / coordinates and decides enablement; UI only presents results.
 */
object ChatShareLogic {
    /** Full-length MeshCore public key, matching firmware / `ContactShareToken`. */
    const val PUBLIC_KEY_SIZE: Int = 32

    /**
     * Appends [shared] to the compose field. A single space separates the token from existing text only
     * when the field is non-empty and does not already end in whitespace (iOS `insertShared`).
     */
    fun appendSharedText(existing: String, shared: String): String {
        if (shared.isEmpty()) return existing
        if (existing.isEmpty()) return shared
        val needsSpace = !existing.last().isWhitespace()
        return if (needsSpace) "$existing $shared" else existing + shared
    }

    /**
     * Own info is shareable only with a full-length key and a non-blank name so the emitted token always
     * carries a usable identity (iOS `canShareMyInfo`).
     */
    fun canShareMyInfo(publicKey: ByteArray?, nodeName: String?): Boolean {
        if (publicKey == null || publicKey.size != PUBLIC_KEY_SIZE) return false
        return !nodeName.orEmpty().trim().isEmpty()
    }

    /**
     * A coordinate is shareable when the phone or the node can supply one, or when location access is
     * granted (tap then fetches a fresh fix). Mirrors iOS `canShareLocation`.
     */
    fun canShareLocation(phoneCoordinate: LatLng?, nodeCoordinate: LatLng?, locationAuthorized: Boolean): Boolean =
        phoneCoordinate != null || nodeCoordinate != null || locationAuthorized

    /** Prefers a fresh phone fix, then last published phone, then node (iOS share-location order). */
    fun resolveShareCoordinate(fresh: LatLng?, phone: LatLng?, node: LatLng?): LatLng? = fresh ?: phone ?: node

    /** Locale-independent `lat, lon` with six fractional digits. */
    fun formatShareCoordinate(latitude: Double, longitude: Double): String = formatCoordinatePair(latitude, longitude)

    /** Contacts with a non-blank advertised name (picker load filter, matching iOS). */
    fun shareableContacts(contacts: List<ContactDto>): List<ContactDto> =
        contacts.filter { it.name.trim().isNotEmpty() }

    /**
     * Filters [contacts] by display name, nickname, or public-key hex prefix (case-insensitive), matching
     * iOS `ShareContactPickerSheet.filteredContacts`.
     */
    fun filterShareContacts(contacts: List<ContactDto>, query: String): List<ContactDto> {
        val q = query.trim()
        if (q.isEmpty()) return contacts
        val upper = q.uppercase()
        return contacts.filter { contact ->
            contact.name.containsIgnoringCaseAndDiacritics(q) ||
                (contact.nickname?.containsIgnoringCaseAndDiacritics(q) == true) ||
                contact.publicKey.toUppercaseHex().startsWith(upper)
        }
    }

    fun formatContactToken(contact: ContactDto): String =
        ContactShareToken.format(contact.publicKey, contact.contactType, contact.name)

    fun formatMyInfoToken(publicKey: ByteArray, nodeName: String): String =
        ContactShareToken.format(publicKey, ContactType.CHAT, nodeName)
}

/** Display helpers for share-picker rows (not localization — the UI layer localizes type labels). */
fun ContactDto.sharePickerTitle(hashSize: Int = 1): String {
    val prefix = publicKey.copyOfRange(0, minOf(hashSize, publicKey.size)).toUppercaseHex()
    return "$prefix $displayName"
}
