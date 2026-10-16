package one.meshcore.android

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.notification.ConversationKey
import one.meshcore.shared.notification.PlatformNotificationTray

/** Badge count and the per-conversation tray clears. */
internal class AndroidNotificationTray(private val host: AndroidNotificationHost) : PlatformNotificationTray {
    override suspend fun setBadge(count: Int) {
        // Android has no portable numeric app-icon badge; the OS badges from active notifications. The
        // shared coordinator still computes the count for platform-independent state and diagnostics.
    }

    override suspend fun removeDeliveredForContact(contactID: Uuid) = cancelGroup(ConversationKey.direct(contactID))

    override suspend fun removeDeliveredForChannel(radioID: Uuid, channelIndex: Int) =
        cancelGroup(ConversationKey.channel(radioID, channelIndex))

    override suspend fun removeDeliveredForRoom(sessionID: Uuid) = cancelGroup(ConversationKey.room(sessionID))

    private fun cancelGroup(key: ConversationKey) {
        host.manager.activeNotifications
            .filter { it.notification.group == key.value }
            .forEach { host.manager.cancel(it.tag, it.id) }
    }
}
