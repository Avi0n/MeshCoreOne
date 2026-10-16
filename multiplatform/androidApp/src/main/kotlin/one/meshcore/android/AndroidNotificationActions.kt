package one.meshcore.android

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.notification.ConversationKey
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_notifications_action_mark_as_read
import one.meshcore.shared.resources.localizable_notifications_action_message_placeholder
import one.meshcore.shared.resources.localizable_notifications_action_reply
import org.jetbrains.compose.resources.getString

// [scrollToMessageID] is set only for a reaction tap, so the opened conversation scrolls to the
// reacted message; its value also keeps the reaction tap distinct from the message notification tap.
internal fun AndroidNotificationHost.tapIntent(key: ConversationKey, scrollToMessageID: Uuid? = null): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra(NotificationActionReceiver.EXTRA_CONVERSATION_KEY, key.value)
        scrollToMessageID?.let { putExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, it.value) }
    }
    return PendingIntent.getActivity(
        context,
        requestCode(scrollToMessageID?.value ?: key.value, TAP_REQUEST),
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

internal fun AndroidNotificationHost.contactDetailIntent(contactID: Uuid): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra(NotificationActionReceiver.EXTRA_CONTACT_ID, contactID.value)
    }
    return PendingIntent.getActivity(
        context,
        requestCode(contactID.value, TAP_REQUEST),
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Default tap for tray kinds that have no conversation target. */
internal fun AndroidNotificationHost.openAppIntent(): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
    }
    return PendingIntent.getActivity(
        context,
        requestCode(OPEN_APP_REQUEST_SEED, TAP_REQUEST),
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

internal suspend fun AndroidNotificationHost.replyAction(
    key: ConversationKey,
    messageID: Uuid? = null,
): NotificationCompat.Action {
    val replyTitle = getString(Res.string.localizable_notifications_action_reply)
    val placeholder = getString(Res.string.localizable_notifications_action_message_placeholder)
    val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY_TEXT)
        .setLabel(placeholder)
        .build()
    // Reply must be mutable so the system can inject the typed RemoteInput text into the intent.
    val pending = PendingIntent.getBroadcast(
        context,
        requestCode(messageID?.value ?: key.value, REPLY_REQUEST),
        actionIntent(NotificationActionReceiver.ACTION_REPLY, key, messageID),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Action.Builder(0, replyTitle, pending)
        .addRemoteInput(remoteInput)
        .setAllowGeneratedReplies(false)
        .build()
}

internal suspend fun AndroidNotificationHost.markReadAction(
    key: ConversationKey,
    messageID: Uuid? = null,
): NotificationCompat.Action {
    val pending = PendingIntent.getBroadcast(
        context,
        requestCode(messageID?.value ?: key.value, MARK_READ_REQUEST),
        actionIntent(NotificationActionReceiver.ACTION_MARK_READ, key, messageID),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val title = getString(Res.string.localizable_notifications_action_mark_as_read)
    return NotificationCompat.Action.Builder(0, title, pending).build()
}

private fun AndroidNotificationHost.actionIntent(action: String, key: ConversationKey, messageID: Uuid?): Intent =
    Intent(context, NotificationActionReceiver::class.java).apply {
        this.action = action
        putExtra(NotificationActionReceiver.EXTRA_CONVERSATION_KEY, key.value)
        messageID?.let { putExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, it.value) }
    }

private fun requestCode(seed: String, offset: Int): Int = seed.hashCode() * REQUEST_STRIDE + offset

private const val OPEN_APP_REQUEST_SEED = "open-app"
private const val TAP_REQUEST = 0
private const val REPLY_REQUEST = 1
private const val MARK_READ_REQUEST = 2
private const val REQUEST_STRIDE = 8
