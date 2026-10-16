package one.meshcore.shared.contact

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.IngestNotifier
import one.meshcore.shared.chat.ResolvedContact
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.MessageEntity

/**
 * Whether an adopted-DM contact should get a banner. Blocked contacts and contacts with no
 * unread incoming row stay silent. Mirrors iOS `orphanDirectMessagesAdopted` handling.
 */
fun adoptedDmNotificationTarget(contact: ContactDto?, unread: MessageEntity?): Pair<ResolvedContact, MessageEntity>? {
    if (contact == null || contact.isBlocked || unread == null) return null
    return ResolvedContact(
        id = contact.id,
        displayName = contact.displayName,
        isBlocked = contact.isBlocked,
        isMuted = contact.isMuted,
    ) to unread
}

/**
 * Posts the DM banner that never fired at receipt, then refreshes the badge.
 */
class AdoptedDmNotificationCollector(
    private val events: suspend () -> ReceiveChannel<ContactServiceEvent>,
    private val contacts: ContactStore,
    private val database: MeshDatabase,
    private val notifier: IngestNotifier,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            val channel = events()
            try {
                for (event in channel) {
                    if (event is ContactServiceEvent.OrphanDirectMessagesAdopted) {
                        notifyAdopted(event.contactIDs)
                    }
                }
            } finally {
                channel.cancel()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun notifyAdopted(contactIDs: List<Uuid>) {
        for (contactID in contactIDs) {
            val contact = contacts.fetchContact(contactID)
            val unread = database.messageDao().newestUnreadIncomingDirect(
                contactID,
                MessageDirection.INCOMING.raw,
            )
            val target = adoptedDmNotificationTarget(contact, unread) ?: continue
            notifier.postDirectMessage(
                contact = target.first,
                text = target.second.text,
                messageID = target.second.id,
                hasSelfMention = target.second.containsSelfMention && !target.second.mentionSeen,
            )
        }
        notifier.updateBadge()
    }
}
