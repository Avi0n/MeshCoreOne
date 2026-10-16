package one.meshcore.shared.contact

import one.meshcore.shared.chat.DeduplicationKey
import one.meshcore.shared.chat.reactions.ReactionParser
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.startsWithPrefix

/** A contact the delta round can attach orphan DMs to. */
data class OrphanDmContact(val id: Uuid, val publicKey: ByteArray, val isBlocked: Boolean)

/** An incoming DM persisted without a [contactID]. */
data class OrphanDmMessage(
    val id: Uuid,
    val senderKeyPrefix: ByteArray?,
    val text: String,
    val timestamp: Long,
    val senderNodeName: String?,
    val sortDate: Double,
    val isRead: Boolean,
    val containsSelfMention: Boolean,
    val mentionSeen: Boolean,
)

data class OrphanDmMatch(val messageId: Uuid, val contactId: Uuid, val deduplicationKey: String)

/**
 * Planned writes from matching orphan incoming DMs onto unique public-key prefixes.
 * Mirrors iOS `PersistenceStore.adoptOrphanedDirectMessages`.
 */
data class OrphanDmPlan(
    val matches: List<OrphanDmMatch>,
    val unreadByContact: Map<Uuid, Int>,
    val mentionByContact: Map<Uuid, Int>,
    val newestDateByContact: Map<Uuid, Double>,
) {
    val adoptedContactIDs: Set<Uuid> get() = matches.map { it.contactId }.toSet()
}

fun planOrphanDirectMessageAdoption(orphans: List<OrphanDmMessage>, contacts: List<OrphanDmContact>): OrphanDmPlan {
    if (orphans.isEmpty() || contacts.isEmpty()) {
        return OrphanDmPlan(emptyList(), emptyMap(), emptyMap(), emptyMap())
    }
    val matches = mutableListOf<OrphanDmMatch>()
    val unread = mutableMapOf<Uuid, Int>()
    val mentions = mutableMapOf<Uuid, Int>()
    val newest = mutableMapOf<Uuid, Double>()
    val blocked = contacts.filter { it.isBlocked }.map { it.id }.toSet()
    for (message in orphans) {
        val match = uniqueOrphanMatch(message, contacts)
        if (match != null) {
            matches += match
            recordNewest(newest, match.contactId, message.sortDate)
            if (match.contactId !in blocked) {
                recordReceipts(unread, mentions, match.contactId, message)
            }
        }
    }
    return OrphanDmPlan(matches, unread, mentions, newest)
}

private fun adoptablePrefix(message: OrphanDmMessage): ByteArray? {
    val prefix = message.senderKeyPrefix ?: return null
    if (prefix.isEmpty()) return null
    if (ReactionParser.isReactionText(message.text, isDM = true)) return null
    return prefix
}

private fun uniqueOrphanMatch(message: OrphanDmMessage, contacts: List<OrphanDmContact>): OrphanDmMatch? {
    val prefix = adoptablePrefix(message) ?: return null
    val hits = contacts.filter { it.publicKey.startsWithPrefix(prefix) }
    if (hits.size != 1) return null
    val match = hits.first()
    return OrphanDmMatch(
        messageId = message.id,
        contactId = match.id,
        deduplicationKey = DeduplicationKey.contentBased(
            contactID = match.id,
            channelIndex = null,
            senderNodeName = message.senderNodeName,
            timestamp = message.timestamp,
            content = message.text,
        ),
    )
}

private fun recordNewest(newest: MutableMap<Uuid, Double>, contactId: Uuid, sortDate: Double) {
    val existing = newest[contactId]
    newest[contactId] = if (existing == null) sortDate else maxOf(existing, sortDate)
}

private fun recordReceipts(
    unread: MutableMap<Uuid, Int>,
    mentions: MutableMap<Uuid, Int>,
    contactId: Uuid,
    message: OrphanDmMessage,
) {
    if (!message.isRead) unread[contactId] = (unread[contactId] ?: 0) + 1
    if (message.containsSelfMention && !message.mentionSeen) {
        mentions[contactId] = (mentions[contactId] ?: 0) + 1
    }
}

fun interface OrphanDmAdopter {
    /** Attaches orphan incoming DMs for [contacts] on [radioID]. Returns the contact ids that adopted at least one. */
    suspend fun adopt(radioID: Uuid, contacts: List<OrphanDmContact>): Set<Uuid>

    companion object {
        val NoOp = OrphanDmAdopter { _, _ -> emptySet() }
    }
}
