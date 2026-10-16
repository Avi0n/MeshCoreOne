package one.meshcore.shared.persistence

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatThreadStore
import one.meshcore.shared.chat.IngestStore
import one.meshcore.shared.chat.MessageStore
import one.meshcore.shared.model.Uuid

internal class CapturedChatContactMetadataNativeStores(store: RoomChatStore, capture: (Job) -> Unit) {
    val messages: MessageStore = object : MessageStore by store {
        override suspend fun updateContactLastMessage(contactID: Uuid, dateEpochSeconds: Double) {
            capture(currentCoroutineContext().job)
            store.updateContactLastMessage(contactID, dateEpochSeconds)
        }
    }
    val ingest: IngestStore = object : IngestStore by store {
        override suspend fun incrementContactUnread(contactID: Uuid) {
            capture(currentCoroutineContext().job)
            store.incrementContactUnread(contactID)
        }
        override suspend fun incrementContactUnreadMention(contactID: Uuid) {
            capture(currentCoroutineContext().job)
            store.incrementContactUnreadMention(contactID)
        }
        override suspend fun touchContactHeard(contactID: Uuid, atEpochSeconds: Long) {
            capture(currentCoroutineContext().job)
            store.touchContactHeard(contactID, atEpochSeconds)
        }
    }
    val thread: ChatThreadStore = object : ChatThreadStore by store {
        override suspend fun clearUnread(conversation: ChatConversationID) {
            capture(currentCoroutineContext().job)
            store.clearUnread(conversation)
        }
        override suspend fun clearUnreadCount(conversation: ChatConversationID) {
            capture(currentCoroutineContext().job)
            store.clearUnreadCount(conversation)
        }
        override suspend fun recomputeContactLastMessageDate(contactID: Uuid) {
            capture(currentCoroutineContext().job)
            store.recomputeContactLastMessageDate(contactID)
        }
    }
}
