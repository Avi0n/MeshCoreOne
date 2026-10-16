package one.meshcore.shared.contact

import one.meshcore.shared.backup.BlockedChannelSenderDto
import one.meshcore.shared.model.Uuid

/**
 * Persistence seam for blocked channel senders: name-based blocks scoped to a radio, because channel
 * messages carry only a parsed sender name and never a public key. Mirrors the subset of the Swift
 * `PersistenceStore` blocked-channel-sender methods [BlockedChannelSenderService] calls. The interface
 * keeps the service independently testable while the Room adapter provides production storage.
 */
interface BlockedChannelSenderStore {
    /** Upserts a blocked sender, matched by `(radioID, name)`; an existing row keeps its id and takes the new date. */
    suspend fun save(sender: BlockedChannelSenderDto)

    /** Removes the `(radioID, name)` block; a no-op if absent. */
    suspend fun delete(radioID: Uuid, name: String)

    /** A radio's blocked senders, newest block first. */
    suspend fun list(radioID: Uuid): List<BlockedChannelSenderDto>
}
