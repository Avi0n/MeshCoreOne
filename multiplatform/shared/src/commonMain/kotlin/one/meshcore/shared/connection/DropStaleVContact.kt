package one.meshcore.shared.connection

import one.meshcore.shared.contact.VContactIdentity
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/** iOS `ConnectionManager.dropStaleVContact` success line. */
internal const val DROPPED_STALE_V_CONTACT_LINE =
    "Dropped stale ZephCore V-contact after identity rotation"

/** iOS `Failed to drop stale V-contact after identity rotation:` dump. */
internal fun failedToDropStaleVContactLine(error: Throwable): String =
    "Failed to drop stale V-contact after identity rotation: ${error.message ?: error}"

/**
 * Removes the local ZephCore V-contact derived from a retired self public key after identity
 * rotation. Deletes through the store, not [one.meshcore.shared.contact.ContactService], so the
 * live-key remove guard cannot keep the stale row. A persist fault, including an [Error], is
 * logged and swallowed so the import can still refresh the live self key. Cancellation stops the drop.
 */
internal suspend fun dropStaleVContactAfterIdentityRotation(
    previousRadioId: Uuid,
    oldSelfPublicKey: ByteArray,
    fetchContactId: suspend (radioId: Uuid, publicKey: ByteArray) -> Uuid?,
    deleteContact: suspend (Uuid) -> Unit,
    logger: PersistentLogger?,
) {
    val oldVKey = VContactIdentity.publicKey(oldSelfPublicKey) ?: return
    runConnectionPersist(
        onFailure = { error -> logger?.warning(failedToDropStaleVContactLine(error)) },
        fallback = {},
    ) {
        val contactId = fetchContactId(previousRadioId, oldVKey) ?: return
        deleteContact(contactId)
        logger?.info(DROPPED_STALE_V_CONTACT_LINE)
    }
}
