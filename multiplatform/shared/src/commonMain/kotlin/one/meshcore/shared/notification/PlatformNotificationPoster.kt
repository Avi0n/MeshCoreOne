package one.meshcore.shared.notification

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid

/**
 * Which OS notification channel a post routes to. The shared coordinator picks the tier from the
 * conversation's level; the Android glue maps the tier to an actual `NotificationChannel` importance.
 * [MESSAGES] is the default alerting tier (DMs and
 * level-all channels); [MENTIONS] carries a mentions-only channel's posts, which are always self-mentions.
 */
enum class NotificationChannelKey { MESSAGES, MENTIONS }

/** A platform-neutral direct-message notification the coordinator has already decided to post. */
data class DirectMessageNotification(
    val contactID: Uuid,
    val messageID: Uuid,
    val title: String,
    val body: String,
    val playSound: Boolean,
    val channelKey: NotificationChannelKey,
)

/** A platform-neutral channel-message notification the coordinator has already decided to post. */
data class ChannelMessageNotification(
    val radioID: Uuid,
    val channelIndex: Int,
    val messageID: Uuid,
    val title: String,
    val body: String,
    val playSound: Boolean,
    val channelKey: NotificationChannelKey,
)

/**
 * A platform-neutral low-battery warning the coordinator has already decided to post. [deviceName] is the
 * dedup identity: a repeat crossing for the same device replaces its prior notification rather than
 * stacking, mirroring the iOS `"low-battery-\(deviceName)"` identifier. [playSound] follows the same
 * sound toggle iOS applies in `postLowBatteryNotification`.
 */
data class LowBatteryNotification(val deviceName: String, val batteryPercentage: Int, val playSound: Boolean)

/**
 * A platform-neutral reaction notification the coordinator has already decided to post: someone reacted
 * to a message the local node sent. [contactID] is set for a DM reaction, [channelIndex] for a channel
 * reaction (either/or, mirroring the reaction row's own shape); [reactorName] is the notification title,
 * as on iOS. [playSound] follows the iOS `soundEnabled` toggle on `postReactionNotification`.
 */
data class ReactionNotification(
    val reactorName: String,
    val emoji: String,
    val messagePreview: String,
    val messageID: Uuid,
    val contactID: Uuid?,
    val channelIndex: Int?,
    val radioID: Uuid,
    val playSound: Boolean,
)

/**
 * The conversation a reaction addresses (its DM or channel), the tap target of a reaction notification —
 * the poster routes a tap through the same conversation-open path as a message tap. Null when neither
 * side is set (a malformed reaction), so the poster attaches no tap intent. Mirrors iOS
 * `onReactionNotificationTapped`, which opens the DM or channel of the reacted message.
 */
fun ReactionNotification.conversationKey(): ConversationKey? = when {
    contactID != null -> ConversationKey.direct(contactID)
    channelIndex != null -> ConversationKey.channel(radioID, channelIndex)
    else -> null
}

/**
 * A platform-neutral room-message notification the coordinator has already decided to post: a signed
 * message arrived in a room the local node has joined. [sessionID] is the stable remote-node session id
 * (the tap and mark-read routing key); [senderName] is null for an unresolved author, in which case the
 * body is the bare message text, mirroring iOS `postRoomMessageNotification`.
 */
data class RoomMessageNotification(
    val roomName: String,
    val sessionID: Uuid,
    val senderName: String?,
    val messageText: String,
    val messageID: Uuid,
    val playSound: Boolean,
)

/**
 * A platform-neutral new-contact-discovered notification the coordinator has already decided to post.
 * [contactID] keys the OS-level identifier so re-discovering the same contact replaces rather than stacks;
 * [contactName] is null or empty for an unnamed advertisement (the poster falls back to a localized
 * "Unknown Contact" body), mirroring iOS. [playSound] follows the iOS `soundEnabled` toggle on
 * `postNewContactNotification`.
 */
data class NewContactNotification(
    val contactID: Uuid,
    val contactName: String?,
    val contactType: ContactType,
    val playSound: Boolean,
)

/**
 * A platform-neutral "Message Not Sent" warning: a notification quick reply could not be handed to the
 * radio, so the typed text was parked as a draft instead of sent. [conversationName] is the contact or
 * channel display name the body names; [conversationKey] routes a tap back to that conversation so the
 * user lands where the parked draft is restored. Mirrors iOS `postQuickReplyFailedNotification` /
 * `postChannelQuickReplyFailedNotification`, which always alert (the warning is the whole point).
 */
data class QuickReplyFailedNotification(val conversationKey: ConversationKey, val conversationName: String)

/** One post call per notification kind. */
interface PlatformNotificationPosts {
    suspend fun postDirect(notification: DirectMessageNotification)

    suspend fun postChannel(notification: ChannelMessageNotification)

    suspend fun postLowBattery(notification: LowBatteryNotification)

    suspend fun postReaction(notification: ReactionNotification)

    suspend fun postRoom(notification: RoomMessageNotification)

    suspend fun postNewContact(notification: NewContactNotification)

    suspend fun postQuickReplyFailed(notification: QuickReplyFailedNotification)
}

/** Badge count and the per-conversation tray clears. */
interface PlatformNotificationTray {
    suspend fun setBadge(count: Int)

    suspend fun removeDeliveredForContact(contactID: Uuid)

    suspend fun removeDeliveredForChannel(radioID: Uuid, channelIndex: Int)

    suspend fun removeDeliveredForRoom(sessionID: Uuid)
}

/**
 * The platform seam that surfaces a notification and the app badge to the OS. The shared
 * [NotificationCoordinator] decides whether and what to post (all the gates, the badge sum); the platform
 * implementation makes the OS call through Android `NotificationManagerCompat`. The remove-delivered calls
 * clear a conversation's tray notifications when it is opened or marked read. [NoOp] keeps desktop and unit
 * tests from touching any OS API.
 */
interface PlatformNotificationPoster :
    PlatformNotificationPosts,
    PlatformNotificationTray {
    object NoOp : PlatformNotificationPoster, PlatformNotificationPosts by Posts, PlatformNotificationTray by Tray

    private object Posts : PlatformNotificationPosts {
        override suspend fun postDirect(notification: DirectMessageNotification) = Unit
        override suspend fun postChannel(notification: ChannelMessageNotification) = Unit
        override suspend fun postLowBattery(notification: LowBatteryNotification) = Unit
        override suspend fun postReaction(notification: ReactionNotification) = Unit
        override suspend fun postRoom(notification: RoomMessageNotification) = Unit
        override suspend fun postNewContact(notification: NewContactNotification) = Unit
        override suspend fun postQuickReplyFailed(notification: QuickReplyFailedNotification) = Unit
    }

    private object Tray : PlatformNotificationTray {
        override suspend fun setBadge(count: Int) = Unit
        override suspend fun removeDeliveredForContact(contactID: Uuid) = Unit
        override suspend fun removeDeliveredForChannel(radioID: Uuid, channelIndex: Int) = Unit
        override suspend fun removeDeliveredForRoom(sessionID: Uuid) = Unit
    }
}
