package one.meshcore.shared.contact

/**
 * The outcome of a contact sync. Kotlin port of the Swift `ContactSyncResult`. [lastSyncTimestamp] is
 * the max `lastModified` (epoch seconds) across the received contacts, held as a `Long` to carry the
 * full unsigned `UInt32` range; [isIncremental] is true when the sync was filtered by a `since` instant.
 */
data class ContactSyncResult(val contactsReceived: Int, val lastSyncTimestamp: Long, val isIncremental: Boolean)
