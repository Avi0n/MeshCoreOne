package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.Uuid

/**
 * The persistence seam [MessageService] depends on: the message read/write and the guarded status
 * writes, plus the contact/channel last-message bookkeeping the send paths touch. A
 * narrow port so the service is unit-testable against [InMemoryMessageStore] and production can use the
 * Room-backed adapter. Mirrors the subset of the Swift `PersistenceStoreProtocol` the message service calls.
 * Every guarded method delegates its decision to [MessageStatusGuards]. Status and ACK writes live on
 * [MessageDeliveryWrites].
 */
interface MessageStore : MessageDeliveryWrites {
    /** The stored message, or null if none exists for [id]. */
    suspend fun fetchMessage(id: Uuid): MessageDto?

    /** Inserts or replaces a message row. */
    suspend fun saveMessage(message: MessageDto)

    /** Stamps a contact's last-message date (epoch seconds) so it surfaces in the conversation list. */
    suspend fun updateContactLastMessage(contactID: Uuid, dateEpochSeconds: Double)

    /**
     * The Device row's node name for [radioID], or null when no Device exists. Channel send uses this so
     * firmware-prepended `"name: "` cannot overflow the 172-byte RX-log heard-repeat budget. A missing
     * Device is the 31-byte worst case, matching iOS `fetchDevice(radioID:)?.nodeName`.
     */
    suspend fun fetchDeviceNodeName(radioID: Uuid): String? = null

    /** Current saved channel and device rows, read again for each send attempt. */
    suspend fun fetchChannelSendConfiguration(radioID: Uuid, channelIndex: Int): ChannelSendConfiguration

    /** The local channel's row id for `(radioID, channelIndex)`, or null if the app has no such channel. */
    suspend fun fetchChannelId(radioID: Uuid, channelIndex: Int): Uuid?

    /** Stamps a channel's last-message date (epoch seconds). */
    suspend fun updateChannelLastMessage(channelID: Uuid, dateEpochSeconds: Double)

    /** Persists a contact refreshed from the device after a path reset or a detected routing change. */
    suspend fun saveContact(radioID: Uuid, contact: MeshContact)

    /** Diagnostic: whether an outgoing DM row persists `.sent` with this ACK code. */
    suspend fun hasOutgoingSentDM(ackCode: Long): Boolean

    /**
     * Manual retry's durable write, when this store owns the queue table: replace the message's parked
     * queue rows with [dto], flip the message back to `.pending` unless it is already `.delivered`, and
     * return the assigned per-radio sequence — all in one commit, so process death can never leave the
     * message `.pending` with no queue row to drain it. Null means the store has no queue table (the
     * in-memory reference), leaving [SendQueueStore] to compose the steps separately.
     */
    suspend fun replacePendingSendForRetry(dto: PendingSendDto): Int? = null
}
