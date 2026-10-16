package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/** Clears one direct-message timeline while retaining its contact and conversation placement. */
internal class ContactMessageClearer(
    private val store: ContactStore,
    private val cleanupCoordinator: ContactCleanupHandling,
    private val syncCoordinator: ContactSyncCoordinator,
    private val eventBroadcaster: ContactServiceEventBroadcaster,
) {
    suspend fun clear(contactID: Uuid) {
        val contact = store.fetchContact(contactID) ?: throw ContactServiceError.ContactNotFound()
        store.deleteMessagesForContact(contactID)
        store.clearUnreadCount(contactID)
        store.clearUnreadMentionCount(contactID)
        cleanupCoordinator.handleCleanup(contactID, ContactCleanupReason.CLEARED, contact.publicKey)
        syncCoordinator.notifyConversationsChanged()
        eventBroadcaster.yield(ContactServiceEvent.MessagesCleared(contactID))
    }
}
