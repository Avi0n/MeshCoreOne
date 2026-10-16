package one.meshcore.shared.notification

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.model.Uuid
import kotlin.jvm.JvmInline

/**
 * In-memory quick-reply drafts, keyed by conversation. When a notification quick reply cannot send (the
 * radio is disconnected) the text is parked here and consumed the next time that conversation opens,
 * feeding the composer restore priority (§11.2). Memory-only by design — a force-quit loses drafts, as on
 * iOS (`NotificationService.pendingDrafts`). Guarded by a [Mutex] because the notification-action path and
 * the conversation-open path touch it from different coroutines.
 */
class QuickReplyDraftStore {
    private val mutex = Mutex()
    private val drafts = mutableMapOf<String, String>()

    /** Parks [text] for [key]; a later save for the same conversation overwrites the earlier draft. */
    suspend fun saveDraft(key: ConversationKey, text: String) = mutex.withLock {
        drafts[key.value] = text
    }

    /** Removes a parked draft only while its consumer still accepts it, checked under the mutex. */
    suspend fun consumeDraft(key: ConversationKey, acceptsDraft: () -> Boolean = { true }): String? = mutex.withLock {
        if (acceptsDraft()) drafts.remove(key.value) else null
    }
}

/** The stable string identifying a conversation across the notification and composer layers. */
@JvmInline
value class ConversationKey(val value: String) {
    /**
     * Decodes this key back into its typed target for the notification reply / mark-read / body-tap
     * routers. Returns null for an unrecognized or malformed key so a caller drops it rather than acting
     * on a wrong conversation. The channel form is `channel-<radioUuid>-<index>`; the index is the last
     * dash-segment and the radio UUID is everything before it.
     */
    fun target(): ConversationTarget? {
        val body = value
        return when {
            body.startsWith(DM_PREFIX) -> runCatching {
                ConversationTarget.Direct(Uuid.normalized(body.removePrefix(DM_PREFIX)))
            }.getOrNull()
            body.startsWith(CHANNEL_PREFIX) -> parseChannelKey(body.removePrefix(CHANNEL_PREFIX))
            body.startsWith(ROOM_PREFIX) -> runCatching {
                ConversationTarget.Room(Uuid.normalized(body.removePrefix(ROOM_PREFIX)))
            }.getOrNull()
            else -> null
        }
    }

    /**
     * Parses the channel body `<radioUuid>-<index>`: the index is the last dash-segment and the radio
     * UUID is everything before it. Returns null for a missing index or empty UUID so a malformed key
     * is dropped rather than acted on.
     */
    private fun parseChannelKey(rest: String): ConversationTarget.Channel? {
        val index = rest.substringAfterLast('-').toIntOrNull() ?: return null
        val radioHex = rest.substringBeforeLast('-', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
            ?: return null
        return runCatching { ConversationTarget.Channel(Uuid.normalized(radioHex), index) }.getOrNull()
    }

    companion object {
        const val DM_PREFIX = "dm-"
        const val CHANNEL_PREFIX = "channel-"
        const val ROOM_PREFIX = "room-"

        fun direct(contactID: Uuid): ConversationKey = ConversationKey("$DM_PREFIX${contactID.value}")

        fun channel(radioID: Uuid, channelIndex: Int): ConversationKey =
            ConversationKey("$CHANNEL_PREFIX${radioID.value}-$channelIndex")

        fun room(sessionID: Uuid): ConversationKey = ConversationKey("$ROOM_PREFIX${sessionID.value}")
    }
}

/**
 * Whether a "Message Not Sent" banner should carry Reply and Mark as Read. Direct messages reuse those
 * actions; a channel failure does not.
 */
fun ConversationKey.carriesDirectMessageActions(): Boolean = target() is ConversationTarget.Direct

/**
 * The notification-layer key for an open chat thread, so the composer restore consumes the very draft the
 * quick-reply action parked. The two sides derive the key from the same conversation identity rather than
 * formatting it independently, which is what keeps a parked draft reachable at all.
 */
fun ChatConversationID.notificationKey(): ConversationKey = when (val key = conversation) {
    is ChatConversationID.ConversationKey.Dm -> ConversationKey.direct(key.contactID)
    is ChatConversationID.ConversationKey.Channel -> ConversationKey.channel(radioID, key.channelIndex)
}

/** A [ConversationKey] decoded into the conversation it addresses. */
sealed interface ConversationTarget {
    data class Direct(val contactID: Uuid) : ConversationTarget

    data class Channel(val radioID: Uuid, val channelIndex: Int) : ConversationTarget

    data class Room(val sessionID: Uuid) : ConversationTarget
}
