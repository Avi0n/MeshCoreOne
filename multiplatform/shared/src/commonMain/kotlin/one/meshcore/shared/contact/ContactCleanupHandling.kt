package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/** Which contact lifecycle change triggered a cleanup. Kotlin port of the Swift `ContactCleanupReason`. */
enum class ContactCleanupReason {
    DELETED,
    BLOCKED,
    UNBLOCKED,

    /** Clear Messages: notifications/badge only, no channel-message deletion or remote-node teardown. */
    CLEARED,
}

/**
 * Cross-service side effects of a contact lifecycle change (block, unblock, delete). [ContactService]
 * invokes this after its own store writes. The production coordinator handles channel-message deletion
 * on block plus notification, badge, and remote-node teardown. Kotlin port of the Swift
 * `ContactCleanupHandling`.
 */
fun interface ContactCleanupHandling {
    /**
     * Runs the cleanup chain for one contact.
     *
     * @param contactID the affected contact's local id
     * @param reason which lifecycle change triggered the cleanup
     * @param publicKey the contact's public key, used to locate any associated remote-node session
     */
    suspend fun handleCleanup(contactID: Uuid, reason: ContactCleanupReason, publicKey: ByteArray)
}

/** A cleanup handler that does nothing, for callers without cross-service cleanup. */
object NoOpContactCleanupHandling : ContactCleanupHandling {
    override suspend fun handleCleanup(contactID: Uuid, reason: ContactCleanupReason, publicKey: ByteArray) = Unit
}
