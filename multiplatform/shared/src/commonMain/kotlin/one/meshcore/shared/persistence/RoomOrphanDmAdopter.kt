package one.meshcore.shared.persistence

import one.meshcore.shared.contact.OrphanDmAdopter
import one.meshcore.shared.contact.OrphanDmContact
import one.meshcore.shared.contact.OrphanDmMessage
import one.meshcore.shared.contact.planOrphanDirectMessageAdoption
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid

/**
 * Room-backed [OrphanDmAdopter]. Prefix match stays in memory because SQL cannot express
 * `Data.prefix`. Mirrors iOS `PersistenceStore.adoptOrphanedDirectMessages`.
 */
class RoomOrphanDmAdopter(private val database: MeshDatabase) : OrphanDmAdopter {
    override suspend fun adopt(radioID: Uuid, contacts: List<OrphanDmContact>): Set<Uuid> {
        if (contacts.isEmpty()) return emptySet()
        val orphans = database.messageDao()
            .listOrphanIncomingDirect(radioID, MessageDirection.INCOMING.raw)
            .map { entity ->
                OrphanDmMessage(
                    id = entity.id,
                    senderKeyPrefix = entity.senderKeyPrefix,
                    text = entity.text,
                    timestamp = entity.timestamp,
                    senderNodeName = entity.senderNodeName,
                    sortDate = entity.sortDate,
                    isRead = entity.isRead,
                    containsSelfMention = entity.containsSelfMention,
                    mentionSeen = entity.mentionSeen,
                )
            }
        val plan = planOrphanDirectMessageAdoption(orphans, contacts)
        if (plan.matches.isEmpty()) return emptySet()
        val messageDao = database.messageDao()
        val contactDao = database.contactDao()
        for (match in plan.matches) {
            val row = messageDao.getById(match.messageId) ?: continue
            messageDao.upsert(row.copy(contactID = match.contactId, deduplicationKey = match.deduplicationKey))
        }
        for (contactId in plan.adoptedContactIDs) {
            val contact = contactDao.getById(contactId) ?: continue
            val updated = contact.withAdoptedMessages(
                newest = plan.newestDateByContact[contactId],
                unreadBump = plan.unreadByContact[contactId],
                mentionBump = plan.mentionByContact[contactId],
            )
            if (updated != contact) contactDao.upsertPreservingPin(updated)
        }
        return plan.adoptedContactIDs
    }
}

/** A newer orphan message moves the contact date. Unread counts move only for a contact that is not blocked. */
internal fun ContactEntity.withAdoptedMessages(newest: Double?, unreadBump: Int?, mentionBump: Int?): ContactEntity {
    val nextDate = adoptedMessageDate(lastMessageDate, newest)
    val nextUnread = adoptedCount(isBlocked, unreadCount, unreadBump)
    val nextMention = adoptedCount(isBlocked, unreadMentionCount, mentionBump)
    return if (nextDate == lastMessageDate && nextUnread == unreadCount && nextMention == unreadMentionCount) {
        this
    } else {
        copy(lastMessageDate = nextDate, unreadCount = nextUnread, unreadMentionCount = nextMention)
    }
}

private fun adoptedMessageDate(current: Double?, newest: Double?): Double? = when {
    newest == null -> current
    current == null -> newest
    else -> maxOf(current, newest)
}

private fun adoptedCount(blocked: Boolean, current: Int, bump: Int?): Int = when {
    blocked -> current
    bump == null || bump <= 0 -> current
    else -> current + bump
}
