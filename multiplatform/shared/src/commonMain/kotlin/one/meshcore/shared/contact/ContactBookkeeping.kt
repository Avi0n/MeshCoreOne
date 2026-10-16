package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/** Message cascade, unread clears, and heard stamps on [ContactStore]. */
interface ContactBookkeeping {
    /**
     * Deletes the contact only when no messages reference it. Insert-only rollback of a cancelled
     * advert fetch: prefer an orphan contact over cascade-wiping a concurrent DM. Probe and delete
     * must share one store isolation region with no suspension between them.
     */
    suspend fun deleteContactIfUnreferenced(id: Uuid)

    /** Deletes every message (and cascaded reaction/repeat/pending-send) for a contact. */
    suspend fun deleteMessagesForContact(contactID: Uuid)

    /** Zeroes a contact's unread count. */
    suspend fun clearUnreadCount(contactID: Uuid)

    /** Zeroes a contact's unread-mention count. */
    suspend fun clearUnreadMentionCount(contactID: Uuid)

    /**
     * Stamps phone-clock recency on a contact heard on air. Max-wins against any existing stamp so a
     * later GET_CONTACTS merge cannot rewind prune protection. Returns true when a Contact row existed.
     * Mirrors iOS `touchContactHeard`.
     */
    suspend fun touchContactHeard(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean
}
