package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.SenderContactMatcher
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.contact.readContactsOrNull
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.pathedit.HopResolution
import one.meshcore.shared.runExceptionFallback

/**
 * Loads every contact scoped to [radioId]. Empty while disconnected or after a persist fault.
 * iOS `MessagePathViewModel.loadContacts` catches store errors and lands `[]`.
 */
suspend fun loadContacts(contactService: ContactService?, radioId: Uuid?): List<ContactDto> {
    if (contactService == null || radioId == null) return emptyList()
    return contactService.readContactsOrNull(radioId) ?: emptyList()
}

/**
 * Loads contacts scoped to [radioId] and keeps only repeaters. Path hops and heard-repeat hashes
 * can only resolve to repeater nodes (iOS MessagePathViewModel / NeighborNameResolver).
 */
suspend fun loadRepeaterContacts(contactService: ContactService?, radioId: Uuid?): List<ContactDto> =
    repeaterContacts(loadContacts(contactService, radioId))

/**
 * Loads discovered nodes for neighbor-name resolution. Empty when [radioId] is null or the store
 * throws. iOS `MessagePathViewModel.loadContacts` catches the discovered-node fetch.
 */
suspend fun loadDiscoveredNodes(
    radioId: Uuid?,
    fetch: suspend (Uuid) -> List<DiscoveredNodeRecord>,
): List<DiscoveredNodeRecord> {
    if (radioId == null) return emptyList()
    return runExceptionFallback({ emptyList() }) { fetch(radioId) }
}

/**
 * Loads discovered repeaters for path hops and heard-repeat names. Empty when [radioId] is null
 * or the store throws. iOS `MessagePathViewModel.loadContacts` catches the discovered-node fetch
 * in the same `do` as contacts.
 */
suspend fun loadDiscoveredRepeaters(
    radioId: Uuid?,
    fetch: suspend (Uuid) -> List<DiscoveredNodeRecord>,
): List<DiscoveredNodeRecord> = loadDiscoveredNodes(radioId, fetch).filter { it.contactType == ContactType.REPEATER }

/**
 * Loads one message for the path sheet. A persist fault lands null so hop resolution still
 * runs from the already-open item. iOS already holds the message DTO.
 */
suspend fun loadMessageForPath(fetch: suspend () -> MessageDto?): MessageDto? = runExceptionFallback({ null }, fetch)

/** The repeater subset of [contacts]; the only nodes a path hop hash can name. */
fun repeaterContacts(contacts: List<ContactDto>): List<ContactDto> =
    contacts.filter { it.contactType == ContactType.REPEATER }

/**
 * The coordinate of the node that sent the message — point A of the path map. Resolved against every
 * contact rather than the repeater subset: the sender of a chat message is normally an ordinary contact,
 * so matching it against repeaters alone drops point A from every path map and makes the route look as if
 * it started at the first repeater. Mirrors iOS `MessagePathViewModel.locatedSender`.
 *
 * A non-empty [senderKeyPrefix] wins, even when [senderNodeName] names a different contact. Several
 * contacts sharing that prefix resolve to nothing rather than guessing. Channel rows with no prefix
 * fall through to a unique case-insensitive [senderNodeName] match, including a unique blocked contact.
 */
fun resolveSenderLocation(
    senderKeyPrefix: ByteArray?,
    contacts: List<ContactDto>,
    senderNodeName: String? = null,
    isChannelMessage: Boolean = false,
): LatLng? {
    val prefix = senderKeyPrefix?.takeIf { it.isNotEmpty() }
    if (prefix != null) {
        return HopResolution.resolveLocation(prefix, contacts, emptyList())
    }
    val sender = senderNodeName?.takeIf { isChannelMessage && it.isNotEmpty() }
        ?.let { SenderContactMatcher.filter(contacts = contacts, senderName = it).singleOrNull() }
        ?.takeIf { it.hasLocation }
    return sender?.let { LatLng(it.latitude, it.longitude) }
}
