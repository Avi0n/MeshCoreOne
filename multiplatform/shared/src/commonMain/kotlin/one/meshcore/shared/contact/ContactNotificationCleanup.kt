package one.meshcore.shared.contact

import one.meshcore.shared.chat.IngestNotifier
import one.meshcore.shared.model.Uuid

/**
 * Notification-side cleanup of a contact lifecycle change: clearing delivered notifications and updating
 * the badge. Production uses [NotifierContactNotificationCleanup] over the app [IngestNotifier].
 * Mirrors the `NotificationService` calls the Swift `ContactCleanupCoordinator` makes.
 */
interface ContactNotificationCleanup {
    /** Removes delivered notifications for the contact (on block or delete). */
    suspend fun removeDeliveredNotifications(contactID: Uuid)

    /** Recomputes the app badge count. */
    suspend fun updateBadgeCount()

    /** Stand-in for isolated service tests that do not own the app notification graph. */
    object NoOp : ContactNotificationCleanup {
        override suspend fun removeDeliveredNotifications(contactID: Uuid) = Unit
        override suspend fun updateBadgeCount() = Unit
    }
}
