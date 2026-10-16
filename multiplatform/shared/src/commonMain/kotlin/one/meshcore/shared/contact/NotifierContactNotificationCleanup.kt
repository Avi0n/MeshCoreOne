package one.meshcore.shared.contact

import one.meshcore.shared.chat.IngestNotifier
import one.meshcore.shared.model.Uuid

/**
 * Production [ContactNotificationCleanup]: forwards to the shared [IngestNotifier]
 * (`NotificationCoordinator` on Android implements tray clear + debounced badge).
 */
class NotifierContactNotificationCleanup(private val notifier: IngestNotifier) : ContactNotificationCleanup {
    override suspend fun removeDeliveredNotifications(contactID: Uuid) {
        notifier.removeDeliveredForContact(contactID)
    }

    override suspend fun updateBadgeCount() {
        notifier.updateBadge()
    }
}
