package one.meshcore.android

import android.app.Notification
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.notification.ChannelMessageNotification
import one.meshcore.shared.notification.ConversationKey
import one.meshcore.shared.notification.DirectMessageNotification
import one.meshcore.shared.notification.LowBatteryNotification
import one.meshcore.shared.notification.NewContactNotification
import one.meshcore.shared.notification.NotificationChannelKey
import one.meshcore.shared.notification.PlatformNotificationPosts
import one.meshcore.shared.notification.QuickReplyFailedNotification
import one.meshcore.shared.notification.ReactionNotification
import one.meshcore.shared.notification.RoomMessageNotification
import one.meshcore.shared.notification.carriesDirectMessageActions
import one.meshcore.shared.notification.conversationKey
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_notifications_discovery_contact
import one.meshcore.shared.resources.localizable_notifications_discovery_repeater
import one.meshcore.shared.resources.localizable_notifications_discovery_room
import one.meshcore.shared.resources.localizable_notifications_discovery_unknown_contact
import one.meshcore.shared.resources.localizable_notifications_low_battery_body
import one.meshcore.shared.resources.localizable_notifications_low_battery_title
import one.meshcore.shared.resources.localizable_notifications_quick_reply_failed_body
import one.meshcore.shared.resources.localizable_notifications_quick_reply_failed_title
import one.meshcore.shared.resources.localizable_notifications_reaction_body
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/** Content fields of one message notification, before the OS builder is assembled. */
private data class MessageNotificationContent(
    val messageID: Uuid,
    val key: ConversationKey,
    val channelKey: NotificationChannelKey,
    val title: String,
    val body: String,
    val playSound: Boolean,
)

private data class ConversationGroup(
    val key: String,
    val title: String,
    val body: String,
    val channelId: String,
    val tap: PendingIntent,
)

/** Builds and posts each notification kind. Tray clears live on [AndroidNotificationTray]. */
internal class AndroidNotificationPosts(private val host: AndroidNotificationHost) : PlatformNotificationPosts {
    override suspend fun postDirect(notification: DirectMessageNotification) {
        val key = ConversationKey.direct(notification.contactID)
        host.postMessage(
            MessageNotificationContent(
                messageID = notification.messageID,
                key = key,
                channelKey = notification.channelKey,
                title = notification.title,
                body = notification.body,
                playSound = notification.playSound,
            ),
        )
    }

    override suspend fun postChannel(notification: ChannelMessageNotification) {
        val key = ConversationKey.channel(notification.radioID, notification.channelIndex)
        host.postMessage(
            MessageNotificationContent(
                messageID = notification.messageID,
                key = key,
                channelKey = notification.channelKey,
                title = notification.title,
                body = notification.body,
                playSound = notification.playSound,
            ),
        )
    }

    override suspend fun postLowBattery(notification: LowBatteryNotification) {
        val title = getString(Res.string.localizable_notifications_low_battery_title)
        val body = formattedString(
            Res.string.localizable_notifications_low_battery_body,
            notification.deviceName,
            notification.batteryPercentage,
        )
        val builder = NotificationCompat.Builder(host.context, MessageNotificationChannels.LOW_BATTERY_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setSilent(!notification.playSound)
            .setContentIntent(host.openAppIntent())
        // Keyed by device name (not a per-post id) so a repeat crossing replaces rather than stacks,
        // mirroring the iOS "low-battery-\(deviceName)" notification identifier.
        host.post(notification.deviceName, LOW_BATTERY_NOTIFICATION_ID, builder)
    }

    override suspend fun postReaction(notification: ReactionNotification) {
        val body = formattedString(
            Res.string.localizable_notifications_reaction_body,
            notification.emoji,
            notification.messagePreview,
        )
        val conversation = notification.conversationKey()
        val group = conversation?.value ?: "reaction"
        val builder = NotificationCompat.Builder(host.context, MessageNotificationChannels.REACTIONS_CHANNEL_ID)
            .setContentTitle(notification.reactorName)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setSilent(!notification.playSound)
            .setGroup(group)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        // Tapping opens the reacted message's conversation (its DM or channel) and scrolls to the reacted
        // message, mirroring iOS `onReactionNotificationTapped`.
        conversation?.let { builder.setContentIntent(host.tapIntent(it, notification.messageID)) }
        // Tag encodes (message, reactor, emoji) — the same triple ReactionService dedups new persists on,
        // so a genuinely new reaction never collides with (and silently replaces) a distinct prior one.
        val tag = "reaction-${notification.messageID.value}-${notification.reactorName}-${notification.emoji}"
        host.post(
            tag,
            REACTION_NOTIFICATION_ID,
            builder,
            conversation?.let {
                ConversationGroup(
                    key = it.value,
                    title = notification.reactorName,
                    body = body,
                    channelId = MessageNotificationChannels.REACTIONS_CHANNEL_ID,
                    tap = host.tapIntent(it, notification.messageID),
                )
            },
        )
    }

    override suspend fun postRoom(notification: RoomMessageNotification) {
        val key = ConversationKey.room(notification.sessionID)
        val body = notification.senderName
            ?.let { "$it: ${notification.messageText}" }
            ?: notification.messageText
        // Rooms have no end-to-end reply target, so (unlike DMs/channels) the notification carries the tap
        // and mark-read actions only, mirroring the iOS room-message category. Posted under the shared
        // message id so the mark-read/open cancel path (keyed by message tag) clears it.
        val builder = NotificationCompat.Builder(
            host.context,
            MessageNotificationChannels.channelId(NotificationChannelKey.MESSAGES),
        )
            .setContentTitle(notification.roomName)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setSilent(!notification.playSound)
            .setGroup(key.value)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(host.tapIntent(key))
            .addAction(host.markReadAction(key, notification.messageID))
        host.post(
            notification.messageID.value,
            NotificationActionReceiver.MESSAGE_NOTIFICATION_ID,
            builder,
            host.conversationGroup(
                key,
                notification.roomName,
                body,
                MessageNotificationChannels.channelId(NotificationChannelKey.MESSAGES),
            ),
        )
    }

    override suspend fun postNewContact(notification: NewContactNotification) {
        val title = getString(
            when (notification.contactType) {
                ContactType.CHAT -> Res.string.localizable_notifications_discovery_contact
                ContactType.REPEATER -> Res.string.localizable_notifications_discovery_repeater
                ContactType.ROOM -> Res.string.localizable_notifications_discovery_room
            },
        )
        val body = notification.contactName?.takeIf { it.isNotEmpty() }
            ?: getString(Res.string.localizable_notifications_discovery_unknown_contact)
        val builder = NotificationCompat.Builder(host.context, MessageNotificationChannels.DISCOVERY_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setSilent(!notification.playSound)
            .setGroup(DISCOVERY_GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(host.contactDetailIntent(notification.contactID))
        // Tagged by contact id (not a per-post id) so re-discovering the same contact replaces rather than
        // stacks, mirroring the iOS "new-contact-\(contactID)" notification identifier.
        host.post(
            notification.contactID.value,
            NEW_CONTACT_NOTIFICATION_ID,
            builder,
            ConversationGroup(
                key = DISCOVERY_GROUP,
                title = title,
                body = body,
                channelId = MessageNotificationChannels.DISCOVERY_CHANNEL_ID,
                tap = host.contactDetailIntent(notification.contactID),
            ),
        )
    }

    override suspend fun postQuickReplyFailed(notification: QuickReplyFailedNotification) {
        val title = getString(Res.string.localizable_notifications_quick_reply_failed_title)
        val body = formattedString(
            Res.string.localizable_notifications_quick_reply_failed_body,
            notification.conversationName,
        )
        val key = notification.conversationKey
        val builder = NotificationCompat.Builder(
            host.context,
            MessageNotificationChannels.channelId(NotificationChannelKey.MESSAGES),
        )
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(host.tapIntent(key))
        if (key.carriesDirectMessageActions()) {
            builder.addAction(host.replyAction(key))
            builder.addAction(host.markReadAction(key))
        }
        // Deliberately outside the conversation's group: opening that conversation clears its group rows,
        // and the warning must survive that open so the restored draft is explained rather than mysterious.
        // Keyed per conversation so a second failed reply replaces the first instead of stacking.
        host.post(
            NotificationActionReceiver.quickReplyFailedTag(key),
            NotificationActionReceiver.QUICK_REPLY_FAILED_NOTIFICATION_ID,
            builder,
        )
    }
}

private fun AndroidNotificationHost.post(
    tag: String,
    id: Int,
    builder: NotificationCompat.Builder,
    group: ConversationGroup? = null,
) {
    if (!notifySafely(tag, id, builder.build())) return
    if (group != null) postGroupSummary(group)
}

private fun AndroidNotificationHost.notifySafely(tag: String, id: Int, notification: Notification): Boolean = try {
    manager.notify(tag, id, notification)
    true
} catch (_: SecurityException) {
    // Authorization is checked upstream, but it can be revoked before the OS call completes.
    false
}

private suspend fun AndroidNotificationHost.postMessage(content: MessageNotificationContent) {
    val channelId = MessageNotificationChannels.channelId(content.channelKey)
    post(
        content.messageID.value,
        NotificationActionReceiver.MESSAGE_NOTIFICATION_ID,
        messageBuilder(content, channelId),
        conversationGroup(content.key, content.title, content.body, channelId),
    )
}

private suspend fun AndroidNotificationHost.messageBuilder(
    content: MessageNotificationContent,
    channelId: String,
): NotificationCompat.Builder = NotificationCompat.Builder(context, channelId)
    .setContentTitle(content.title)
    .setContentText(content.body)
    .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
    .setSmallIcon(android.R.drawable.stat_notify_chat)
    .setAutoCancel(true)
    .setSilent(!content.playSound)
    .setGroup(content.key.value)
    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
    .setContentIntent(tapIntent(content.key))
    .addAction(replyAction(content.key, content.messageID))
    .addAction(markReadAction(content.key, content.messageID))

// iOS groups by threadIdentifier; Android only stacks a conversation when a summary is posted.
private fun AndroidNotificationHost.conversationGroup(
    key: ConversationKey,
    title: String,
    body: String,
    channelId: String,
) = ConversationGroup(
    key = key.value,
    title = title,
    body = body,
    channelId = channelId,
    tap = tapIntent(key),
)

private fun AndroidNotificationHost.postGroupSummary(group: ConversationGroup) {
    val builder = NotificationCompat.Builder(context, group.channelId)
        .setContentTitle(group.title)
        .setContentText(group.body)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setGroup(group.key)
        .setGroupSummary(true)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        .setAutoCancel(true)
        .setSilent(true)
        .setContentIntent(group.tap)
    notifySafely(GROUP_SUMMARY_TAG_PREFIX + group.key, GROUP_SUMMARY_NOTIFICATION_ID, builder.build())
}

// Compose `getString(resource, args)` only honors positional `%N$s`; the pipeline still emits
// bare `%s`/`%d`. formatTemplate substitutes both.
private suspend fun formattedString(resource: StringResource, vararg args: Any): String =
    formatTemplate(getString(resource), args.asList())

// Distinct from NotificationActionReceiver.MESSAGE_NOTIFICATION_ID so a low-battery post never
// collides with a message post that happens to share a tag.
private const val LOW_BATTERY_NOTIFICATION_ID = 2
private const val REACTION_NOTIFICATION_ID = 3
private const val NEW_CONTACT_NOTIFICATION_ID = 4
private const val GROUP_SUMMARY_NOTIFICATION_ID = 6

// Summary rows share the conversation group so cancelGroup drops them with the children.
private const val GROUP_SUMMARY_TAG_PREFIX = "group-summary-"

// Groups every discovery post so they visually stack in the tray, mirroring iOS threadIdentifier.
private const val DISCOVERY_GROUP = "discovery"
