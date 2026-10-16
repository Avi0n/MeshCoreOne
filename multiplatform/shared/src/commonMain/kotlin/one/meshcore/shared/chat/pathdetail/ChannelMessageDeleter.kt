package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.contact.ChannelMessageDeleting
import one.meshcore.shared.model.Uuid

/**
 * The persistence seam the channel-message deletion cascade drives. The child-row deletes take an id chunk
 * so the deleter can stay under the SQLite bound-variable limit. Mirrors the `deleteChannelMessages` subset
 * of the Swift `PersistenceStore`.
 */
interface ChannelMessageDeletionStore {
    /**
     * The ids of channel messages (never DMs) from [senderName] on [radioID]: `channelIndex != null` and an
     * exact `senderNodeName == senderName` match. Rows with a null sender are excluded so system rows survive.
     */
    suspend fun channelMessageIdsFromSender(radioID: Uuid, senderName: String): List<Uuid>

    /** Deletes the pending-send rows for [messageIDs]. */
    suspend fun deletePendingSends(messageIDs: List<Uuid>)

    /** Deletes the reaction rows for [messageIDs] (one chunk). */
    suspend fun deleteReactions(messageIDs: List<Uuid>)

    /** Deletes the message-repeat rows for [messageIDs] (one chunk). */
    suspend fun deleteMessageRepeats(messageIDs: List<Uuid>)

    /** Deletes the message rows for [messageIDs]. */
    suspend fun deleteMessages(messageIDs: List<Uuid>)
}

/**
 * Deletes a blocked channel sender's existing channel messages and their dependent rows. This is the
 * chat-owned implementation of the [ChannelMessageDeleting] seam the block flow depends on
 * (`BlockedChannelSenderService` and the contact-block cleanup both call it): the deletion protects the
 * block guarantee by clearing the sender's channel history. Port of the Swift
 * `PersistenceStore.deleteChannelMessages(fromSender:radioID:)`.
 *
 * Order matters: pending sends first, then the reaction and repeat rows in [DELETION_CHUNK_SIZE] chunks
 * (a bulk delete bypasses SwiftData/Room cascade, so it is done explicitly and kept under the SQLite
 * variable limit), then the messages. DMs and null-sender rows are never touched.
 */
class ChannelMessageDeleter(private val store: ChannelMessageDeletionStore) : ChannelMessageDeleting {
    override suspend fun deleteChannelMessages(senderName: String, radioID: Uuid) {
        val messageIDs = store.channelMessageIdsFromSender(radioID = radioID, senderName = senderName)
        if (messageIDs.isEmpty()) return

        store.deletePendingSends(messageIDs)
        for (chunk in messageIDs.chunked(DELETION_CHUNK_SIZE)) {
            store.deleteReactions(chunk)
            store.deleteMessageRepeats(chunk)
        }
        store.deleteMessages(messageIDs)
    }

    companion object {
        /** Ids per bulk child-row delete, staying under the SQLite bound-variable limit (~32766). */
        const val DELETION_CHUNK_SIZE: Int = 500
    }
}
