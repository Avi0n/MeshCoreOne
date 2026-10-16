package one.meshcore.shared.navigation

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.model.Uuid

/**
 * Matches saved-shortcut ids against live channels by secret digest. A digest that matches zero or
 * more than one channel fails safe to no match, so a relocated or duplicated channel cannot
 * mis-route a send.
 */
fun resolveUniqueChannels(matchingIds: Collection<String>, channels: List<ChannelDto>): List<ChannelDto> {
    val wanted = matchingIds.toSet()
    val matchesById = mutableMapOf<String, MutableList<ChannelDto>>()
    for (channel in channels) {
        val id = MessageTarget(channel).id
        if (id !in wanted) continue
        matchesById.getOrPut(id) { mutableListOf() }.add(channel)
    }
    return wanted.mapNotNull { id -> matchesById[id]?.singleOrNull() }
}

fun resolveMessageTargets(
    matchingIds: Collection<String>,
    contacts: List<ContactDto>,
    channels: List<ChannelDto>,
): List<MessageTarget> {
    val wanted = matchingIds.toSet()
    val contactIds = wanted.filter { parseCompositeId(it)?.second == MessageTargetKind.CONTACT }.toSet()
    val channelIds = wanted.filter { parseCompositeId(it)?.second == MessageTargetKind.CHANNEL }
    val contactMatches = contacts.map(::MessageTarget).filter { it.id in contactIds }
    val channelMatches = resolveUniqueChannels(channelIds, channels).map(::MessageTarget)
    return contactMatches + channelMatches
}

/**
 * Combined recipient-picker list: contacts then channels, each sorted by display name.
 * Channels whose secret digest collides are omitted because the send path cannot resolve them.
 */
fun buildMessageTargets(contacts: List<ContactDto>, channels: List<ChannelDto>): List<MessageTarget> {
    val contactEntities = contacts.map(::MessageTarget).sortedBy { it.displayName.lowercase() }
    val channelIds = channels.map { MessageTarget(it).id }
    val channelEntities = resolveUniqueChannels(channelIds, channels)
        .map(::MessageTarget)
        .sortedBy { it.displayName.lowercase() }
    return contactEntities + channelEntities
}

/**
 * Resolves a send token to one chat contact. Accepts a composite id, a row UUID, or an exact
 * display name. A name that matches zero or more than one contact fails safe to null.
 */
fun resolveContactTarget(token: String, contacts: List<ContactDto>): ContactDto? {
    val trimmed = token.trim()
    if (trimmed.isEmpty()) return null
    return contactForTrimmedToken(trimmed, chatContacts(contacts))
}

private fun contactForTrimmedToken(trimmed: String, chat: List<ContactDto>): ContactDto? {
    if (parseCompositeId(trimmed) != null) return contactFromCompositeId(trimmed, chat)
    if (COMPOSITE_RADIO_ID.matches(trimmed)) {
        return chat.singleOrNull { it.id == Uuid.normalized(trimmed) }
    }
    return chat.singleOrNull { it.displayName.equals(trimmed, ignoreCase = true) }
}

private fun contactFromCompositeId(trimmed: String, chat: List<ContactDto>): ContactDto? {
    val parsed = parseCompositeId(trimmed) ?: return null
    if (parsed.second != MessageTargetKind.CONTACT) return null
    val id = resolveMessageTargets(listOf(trimmed), chat, emptyList()).singleOrNull()?.id ?: return null
    return chat.firstOrNull { MessageTarget(it).id == id }
}

/**
 * Resolves a send token to one channel. Accepts a composite id, a row UUID, or an exact channel
 * name. A name that matches zero or more than one channel fails safe to null.
 */
fun resolveChannelTarget(token: String, channels: List<ChannelDto>): ChannelDto? {
    val trimmed = token.trim()
    if (trimmed.isEmpty()) return null
    return channelForTrimmedToken(trimmed, channels)
}

private fun channelForTrimmedToken(trimmed: String, channels: List<ChannelDto>): ChannelDto? {
    if (parseCompositeId(trimmed) != null) return channelFromCompositeId(trimmed, channels)
    if (COMPOSITE_RADIO_ID.matches(trimmed)) {
        return channels.singleOrNull { it.id == Uuid.normalized(trimmed) }
    }
    return channels.singleOrNull { it.name.equals(trimmed, ignoreCase = true) }
}

private fun channelFromCompositeId(trimmed: String, channels: List<ChannelDto>): ChannelDto? {
    val parsed = parseCompositeId(trimmed) ?: return null
    if (parsed.second != MessageTargetKind.CHANNEL) return null
    return resolveUniqueChannels(listOf(trimmed), channels).singleOrNull()
}

/** Case-insensitive display-name filter for a spoken or typed Assistant query. */
fun matchMessageTargets(query: String, targets: List<MessageTarget>): List<MessageTarget> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return targets
    return targets.filter { it.displayName.lowercase().contains(needle) }
}
