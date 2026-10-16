package one.meshcore.shared.persistence

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.notification.UnreadCounts

/**
 * The badge's unread source: per-category totals for one radio, the Kotlin port of the Swift
 * `PersistenceStore.getTotalUnreadCounts`. Only rows the user can actually open contribute — a muted or
 * blocked contact, a repeater contact, a muted channel, and a repeater-role remote session are all
 * unreachable from the chats list, so their unreads must not inflate the badge. A channel set to
 * mentions-only contributes its mention count rather than its full unread count.
 */
class RoomUnreadCountsStore(database: MeshDatabase) {
    private val contactDao = database.contactDao()
    private val channelDao = database.channelDao()
    private val sessionDao = database.remoteNodeSessionDao()

    suspend fun totals(radioID: Uuid): UnreadCounts {
        val contacts = contactDao
            .listUnreadForBadge(radioID, repeaterTypeRawValue = ContactType.REPEATER.raw)
            .sumOf { it.unreadCount }

        val channels = channelDao.listUnreadForBadge(radioID)
            .filter { it.notificationLevel != NotificationLevel.MUTED }
            .sumOf { channel ->
                if (channel.notificationLevel == NotificationLevel.MENTIONS_ONLY) {
                    channel.unreadMentionCount
                } else {
                    channel.unreadCount
                }
            }

        val rooms = sessionDao
            .listUnreadForBadge(radioID, roomServerRoleRawValue = RemoteNodeRole.ROOM_SERVER.raw)
            .filter { it.notificationLevel != NotificationLevel.MUTED }
            .sumOf { it.unreadCount }

        return UnreadCounts(contacts = contacts, channels = channels, rooms = rooms)
    }
}
