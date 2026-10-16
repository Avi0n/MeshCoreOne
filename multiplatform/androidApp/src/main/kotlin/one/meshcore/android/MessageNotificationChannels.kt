package one.meshcore.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import one.meshcore.shared.notification.NotificationChannelKey

/**
 * The message NotificationChannels the poster surfaces incoming DMs and channel messages on, distinct
 * from the foreground-service [ConnectionNotification] channel. Per-conversation
 * [one.meshcore.shared.model.NotificationLevel] maps onto two OS channels: level-all and every DM route
 * to [MESSAGES_CHANNEL_ID]; a mentions-only channel's posts,
 * which are always self-mentions, route to [MENTIONS_CHANNEL_ID]. Muted never posts, so it needs no
 * channel. [LOW_BATTERY_CHANNEL_ID] carries the device-battery warning, [REACTIONS_CHANNEL_ID] a reaction
 * to the user's own message, and [DISCOVERY_CHANNEL_ID] a newly discovered contact/repeater/room, all
 * tunable independently since none is conversation-scoped. All five are high importance because anything
 * that clears the shared gate is meant to alert; the separate channels let the user independently tune or
 * silence each in system settings.
 */
internal object MessageNotificationChannels {
    const val MESSAGES_CHANNEL_ID = "messages"
    const val MENTIONS_CHANNEL_ID = "mentions"
    const val LOW_BATTERY_CHANNEL_ID = "low_battery"
    const val REACTIONS_CHANNEL_ID = "reactions"
    const val DISCOVERY_CHANNEL_ID = "discovery"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensure(manager, MESSAGES_CHANNEL_ID, context.getString(R.string.notification_channel_messages))
        ensure(manager, MENTIONS_CHANNEL_ID, context.getString(R.string.notification_channel_mentions))
        ensure(manager, LOW_BATTERY_CHANNEL_ID, context.getString(R.string.notification_channel_low_battery))
        ensure(manager, REACTIONS_CHANNEL_ID, context.getString(R.string.notification_channel_reactions))
        ensure(manager, DISCOVERY_CHANNEL_ID, context.getString(R.string.notification_channel_discovery))
    }

    fun channelId(key: NotificationChannelKey): String = when (key) {
        NotificationChannelKey.MESSAGES -> MESSAGES_CHANNEL_ID
        NotificationChannelKey.MENTIONS -> MENTIONS_CHANNEL_ID
    }

    private fun ensure(manager: NotificationManager, id: String, name: String) {
        val existing = manager.getNotificationChannel(id)
        if (existing != null && existing.name == name) return
        manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH))
    }
}
