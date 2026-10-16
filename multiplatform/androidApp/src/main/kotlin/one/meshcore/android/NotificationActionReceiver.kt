package one.meshcore.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.notification.ConversationKey

/** Handles quick reply, mark-as-read, and spoken-status actions from app notifications. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SPEAK_STATUS) {
            val spoken = intent.getStringExtra(EXTRA_SPOKEN_STATUS)?.takeIf { it.isNotBlank() } ?: return
            val pending = goAsync()
            StatusQuerySpeaker.speak(context, spoken) { pending.finish() }
            return
        }
        val container = (context.applicationContext as MeshCoreApplication).container
        val key = intent.getStringExtra(EXTRA_CONVERSATION_KEY)?.let { ConversationKey(it) } ?: return
        val messageID = intent.getStringExtra(EXTRA_MESSAGE_ID)?.let { Uuid.normalized(it) }

        // Keep the receiver alive until the asynchronous reply or mark-read work finishes.
        val pending = goAsync()
        val job = when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY_TEXT)
                    ?.toString()
                    .orEmpty()
                if (text.isNotBlank()) container.handleNotificationQuickReply(key, text) else null
            }
            ACTION_MARK_READ -> container.handleNotificationMarkAsRead(key, messageID)
            else -> null
        }
        val notifications = NotificationManagerCompat.from(context)
        messageID?.let { notifications.cancel(it.value, MESSAGE_NOTIFICATION_ID) }
        notifications.cancel(quickReplyFailedTag(key), QUICK_REPLY_FAILED_NOTIFICATION_ID)
        if (job == null) pending.finish() else job.invokeOnCompletion { pending.finish() }
    }

    companion object {
        const val ACTION_REPLY = "one.meshcore.android.action.NOTIFICATION_REPLY"
        const val ACTION_MARK_READ = "one.meshcore.android.action.NOTIFICATION_MARK_READ"
        const val ACTION_SPEAK_STATUS = "one.meshcore.android.action.SPEAK_STATUS"
        const val EXTRA_CONVERSATION_KEY = "conversation_key"
        const val EXTRA_SPOKEN_STATUS = "spoken_status"

        // A new-contact-discovery notification body tap carries the contact id (not a conversation key,
        // since the tap opens the contact's detail rather than a chat), read by MainActivity.
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val KEY_REPLY_TEXT = "reply_text"

        /** The shared notify id; each message is distinguished by its tag under this single id. */
        const val MESSAGE_NOTIFICATION_ID = 2001

        /** Distinct from [MESSAGE_NOTIFICATION_ID] so a failure warning never collides with a message tag. */
        const val QUICK_REPLY_FAILED_NOTIFICATION_ID = 5

        /** Namespaces the tag so a failure warning never collides with a message tag under its own id. */
        const val QUICK_REPLY_FAILED_TAG_PREFIX = "quick-reply-failed-"

        fun quickReplyFailedTag(key: ConversationKey): String = QUICK_REPLY_FAILED_TAG_PREFIX + key.value
    }
}
