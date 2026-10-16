package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/**
 * The production [ContactCleanupHandling]: the cross-service side effects of a block, unblock, or delete.
 * On block it deletes the contact's channel messages; on block or unblock it rebuilds the blocked-name
 * cache and refreshes conversations; on block or delete it clears delivered notifications; it always
 * updates the badge; on delete it tears down any remote-node session. Kotlin port of the Swift
 * `ContactCleanupCoordinator`.
 *
 * Holds its collaborators directly, never the service container, so a torn-down container cannot be
 * revived through it. ServiceContainer wires [NotifierContactNotificationCleanup] and
 * [RemoteNodeServiceSessionCleanup] in production; defaults stay no-op for isolated unit tests.
 */
class ContactCleanupCoordinator(
    private val contactStore: ContactStore,
    private val channelMessageDeleter: ChannelMessageDeleting,
    private val blockedNamesRefresher: BlockedNamesRefreshing,
    private val syncCoordinator: ContactSyncCoordinator,
    private val notifications: ContactNotificationCleanup = ContactNotificationCleanup.NoOp,
    private val remoteNodes: RemoteNodeSessionCleanup = RemoteNodeSessionCleanup.NoOp,
) : ContactCleanupHandling {
    override suspend fun handleCleanup(contactID: Uuid, reason: ContactCleanupReason, publicKey: ByteArray) {
        if (reason == ContactCleanupReason.BLOCKED || reason == ContactCleanupReason.UNBLOCKED) {
            // The contact row is already saved with the new blocked state; read it for the advertised name.
            contactStore.fetchContact(contactID)?.let { contact ->
                if (reason == ContactCleanupReason.BLOCKED) {
                    channelMessageDeleter.deleteChannelMessages(senderName = contact.name, radioID = contact.radioID)
                }
                blockedNamesRefresher.refresh(contact.radioID)
                syncCoordinator.notifyConversationsChanged()
            }
        }

        if (reason == ContactCleanupReason.BLOCKED ||
            reason == ContactCleanupReason.DELETED ||
            reason == ContactCleanupReason.CLEARED
        ) {
            notifications.removeDeliveredNotifications(contactID)
        }

        notifications.updateBadgeCount()

        if (reason == ContactCleanupReason.DELETED) {
            remoteNodes.removeSession(publicKey)
            syncCoordinator.notifyConversationsChanged()
        }
    }
}
