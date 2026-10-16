package one.meshcore.shared.chat

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/**
 * Everything [ChatSendQueueService]'s drain closures read and write: the [PendingSendStore] surface plus
 * the contact lookup and the two guarded message-status writes the park/drop paths perform. Mirrors the
 * Swift `any MessagePersisting & ContactPersisting` the send queue takes. [DefaultSendQueueStore] composes
 * a [PendingSendStore] with a [MessageStore] and a contact provider so production and tests build it the
 * same way.
 */
interface SendQueueStore : PendingSendStore {
    /** The contact for a DM send, or null if it was deleted between enqueue and drain (drop the envelope). */
    suspend fun fetchContact(id: Uuid): ContactDto?

    /** Sets status unconditionally; used by the missing-contact drop path that owns terminal safety. */
    suspend fun updateMessageStatus(id: Uuid, status: MessageStatus)

    /** Sets status unless the row already reached `.delivered`. Returns whether a row changed. */
    suspend fun updateMessageStatusUnlessDelivered(id: Uuid, status: MessageStatus): Boolean

    /**
     * Atomically replaces any pending rows for a message, flips the message back to `.pending` (unless
     * already `.delivered`), and inserts the new row with a fresh per-radio sequence. The manual-retry
     * entry point ([ChatSendQueueService.signalDMEnqueued]) writes the row this way; returns the sequence.
     * A durable store commits the three writes together: a crash between the delete and the insert would
     * strand the message `.pending` with no queue row, so it never sends and offers no retry.
     */
    suspend fun replacePendingSendForRetry(messageID: Uuid, dto: PendingSendDto): Int
}

/**
 * Composes the durable [pendingSends] store with a [messageStore] (for the status writes and the retry
 * status flip) and a [contactProvider] (the live-connection contact lookup). Delegates the pending-send
 * surface to [pendingSends]; the queue reads live values through the injected provider so a disconnect
 * surfaces as a null contact.
 */
class DefaultSendQueueStore(
    private val pendingSends: PendingSendStore,
    private val messageStore: MessageStore,
    private val contactProvider: suspend (Uuid) -> ContactDto?,
) : SendQueueStore,
    PendingSendStore by pendingSends {
    override suspend fun fetchContact(id: Uuid): ContactDto? = contactProvider(id)

    override suspend fun updateMessageStatus(id: Uuid, status: MessageStatus) =
        messageStore.updateMessageStatus(id, status)

    override suspend fun updateMessageStatusUnlessDelivered(id: Uuid, status: MessageStatus): Boolean =
        messageStore.updateMessageStatusUnlessDelivered(id, status)

    // A late ACK that landed while the user reached for retry must not be downgraded; the guard blocks the
    // `.pending` write on a `.delivered` row while still inserting the row for the drain gate.
    override suspend fun replacePendingSendForRetry(messageID: Uuid, dto: PendingSendDto): Int =
        messageStore.replacePendingSendForRetry(dto) ?: run {
            pendingSends.deletePendingSendsForMessage(messageID)
            messageStore.updateMessageStatusUnlessDelivered(messageID, MessageStatus.PENDING)
            pendingSends.insertPendingSendAssigningSequence(dto)
        }
}
