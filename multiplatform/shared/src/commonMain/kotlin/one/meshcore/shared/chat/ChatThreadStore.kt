package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.model.Uuid

/**
 * The persistence seam the conversation thread view model depends on, layered over [ChatCoordinatorStore]
 * and [ChatThreadUnreadStore] so the shared coordinator and its bound thread read through one instance.
 * Adds the reads pagination needs plus the writes the thread's own actions perform (delete, mention,
 * retry, last-message re-derivation). A narrow port of the Swift `PersistenceStore` reads and writes
 * `ChatViewModel` calls beyond the coordinator's; [one.meshcore.shared.persistence.RoomChatStore] provides
 * the production implementation.
 */
interface ChatThreadStore :
    ChatCoordinatorStore,
    ChatThreadUnreadStore {
    /** Heard-repeat rows for the Repeat Details sheet (ordered by receive time). */
    suspend fun fetchMessageRepeats(messageID: Uuid): List<MessageRepeatDto> = emptyList()

    /**
     * Deletes a single message row. The thread then drops it from the coordinator and re-derives the
     * conversation's last-message date from the database (older rows beyond the loaded page must keep the
     * conversation visible). Mirrors `PersistenceStore.deleteMessage`.
     */
    suspend fun deleteMessage(id: Uuid)

    /**
     * Re-derives a contact's last-message date from the stored rows after a delete, so deleting the newest
     * message shifts the conversation-list sort/preview without hiding a conversation that still has older
     * messages. Mirrors `recomputeContactLastMessageDate`.
     */
    suspend fun recomputeContactLastMessageDate(contactID: Uuid)

    /**
     * Flips a stuck outgoing row to `.failed` unless delivery already won, used when a send cannot be
     * enqueued at all (spec §9.2 send-failure fold). Returns true when a row actually changed. Mirrors
     * `updateMessageStatusUnlessDelivered(_, .failed)`.
     */
    suspend fun markMessageFailedUnlessDelivered(id: Uuid): Boolean

    /**
     * Persists that a self-mention has been seen, once its bubble scrolls into view. Mirrors iOS
     * `dataStore.markMentionSeen`; a no-op default lets fakes that don't exercise mentions skip it.
     */
    suspend fun markMentionSeen(id: Uuid) {}

    /**
     * Marks every unseen self-mention in this conversation seen. Opening the thread counts as seeing
     * them, matching iOS `markConversationMentionsSeen`. Returns the ids that flipped so the open
     * thread can update in-memory rows. Default empty for fakes that do not persist mentions.
     */
    suspend fun markConversationMentionsSeen(conversation: ChatConversationID): List<Uuid> = emptyList()

    /**
     * Parks a retry row and flips the message back to `.pending` so reconnect hydrate can send it.
     * Default no-op for fakes that do not exercise offline retry.
     */
    suspend fun replacePendingSendForRetry(dto: PendingSendDto): Int = 0

    /**
     * Marks current outgoing `.failed` rows in this conversation as seen so the chats-list badge
     * does not reappear after the user opened the thread. Mirrors iOS `markFailedSendsSeen`.
     */
    suspend fun markFailedSendsSeen(conversation: ChatConversationID) {}
}
