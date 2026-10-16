package one.meshcore.shared.notification

import one.meshcore.shared.chat.ResolvedChannel
import one.meshcore.shared.chat.ResolvedContact
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid

/** Fixed identities and a base clock the notification tests read. */
internal object NotificationFixtures {
    val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    val CONTACT: Uuid = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
    val CHANNEL: Uuid = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")
    const val NOW_SECONDS: Double = 1_700_000_000.0

    fun contact(
        id: Uuid = CONTACT,
        displayName: String = "Alice",
        isBlocked: Boolean = false,
        isMuted: Boolean = false,
    ): ResolvedContact = ResolvedContact(id = id, displayName = displayName, isBlocked = isBlocked, isMuted = isMuted)

    fun channel(
        index: Int,
        name: String = "General",
        level: NotificationLevel = NotificationLevel.ALL,
    ): ResolvedChannel = ResolvedChannel(
        id = CHANNEL,
        index = index,
        radioID = RADIO,
        name = name,
        notificationLevel = level,
    )
}

/** Records every platform call so a test can assert what was posted, badged, or cleared. */
internal class RecordingPoster : PlatformNotificationPoster {
    val directPosts = mutableListOf<DirectMessageNotification>()
    val channelPosts = mutableListOf<ChannelMessageNotification>()
    val lowBatteryPosts = mutableListOf<LowBatteryNotification>()
    val reactionPosts = mutableListOf<ReactionNotification>()
    val roomPosts = mutableListOf<RoomMessageNotification>()
    val newContactPosts = mutableListOf<NewContactNotification>()
    val quickReplyFailedPosts = mutableListOf<QuickReplyFailedNotification>()
    val badges = mutableListOf<Int>()
    val clearedContacts = mutableListOf<Uuid>()
    val clearedChannels = mutableListOf<Pair<Uuid, Int>>()
    val clearedRooms = mutableListOf<Uuid>()

    override suspend fun postDirect(notification: DirectMessageNotification) {
        directPosts += notification
    }

    override suspend fun postChannel(notification: ChannelMessageNotification) {
        channelPosts += notification
    }

    override suspend fun postLowBattery(notification: LowBatteryNotification) {
        lowBatteryPosts += notification
    }

    override suspend fun postReaction(notification: ReactionNotification) {
        reactionPosts += notification
    }

    override suspend fun postRoom(notification: RoomMessageNotification) {
        roomPosts += notification
    }

    override suspend fun postNewContact(notification: NewContactNotification) {
        newContactPosts += notification
    }

    override suspend fun postQuickReplyFailed(notification: QuickReplyFailedNotification) {
        quickReplyFailedPosts += notification
    }

    override suspend fun setBadge(count: Int) {
        badges += count
    }

    override suspend fun removeDeliveredForContact(contactID: Uuid) {
        clearedContacts += contactID
    }

    override suspend fun removeDeliveredForChannel(radioID: Uuid, channelIndex: Int) {
        clearedChannels += radioID to channelIndex
    }

    override suspend fun removeDeliveredForRoom(sessionID: Uuid) {
        clearedRooms += sessionID
    }
}

/** A fixed-snapshot preferences provider for toggling individual gates in a test. */
internal class FixedPreferences(private val snapshot: NotificationPreferencesSnapshot) :
    NotificationPreferencesProvider {
    override suspend fun snapshot(): NotificationPreferencesSnapshot = snapshot
}
