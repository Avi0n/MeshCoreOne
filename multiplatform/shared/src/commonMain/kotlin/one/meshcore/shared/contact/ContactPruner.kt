package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.settings.PersistentLogger

/** Ed25519 public key size. Mirrors iOS `ProtocolLimits.publicKeySize`. */
private const val PUBLIC_KEY_SIZE: Int = 32

/** Reconciles local contacts after a complete full-table snapshot from the radio. */
internal class ContactPruner(
    private val store: ContactStore,
    private val selfPublicKey: suspend (Uuid) -> ByteArray?,
    private val logger: PersistentLogger?,
    private val cleanupCoordinator: ContactCleanupHandling,
) {
    suspend fun pruneOrphans(radioID: Uuid, devicePublicKeys: Set<String>, reportedTotal: Int?) {
        if (reportedTotal == null) {
            logger?.notice("Full sync prune skipped: device sent no contact total (missing contactsStart header)")
            return
        }
        if (devicePublicKeys.size < reportedTotal) {
            logger?.notice(
                "Full sync prune skipped: received ${devicePublicKeys.size} of $reportedTotal " +
                    "reported device contacts (incomplete snapshot)",
            )
            return
        }
        val selfKey = runSelfPublicKeyRead { selfPublicKey(radioID) }
        if (selfKey == null || selfKey.size != PUBLIC_KEY_SIZE) {
            logger?.notice(
                "Full sync prune skipped: self public key unavailable or invalid, cannot exclude the V-contact",
            )
            return
        }
        val localContacts = store.fetchContacts(radioID)
        val orphans = localContacts.filter { contact ->
            if (contact.publicKey.toUppercaseHex() in devicePublicKeys) return@filter false
            !VContactIdentity.isVContact(contact.publicKey, selfKey)
        }
        if (orphans.isNotEmpty()) {
            logger?.notice(
                fullSyncPruneSummaryLine(
                    orphanCount = orphans.size,
                    deviceCount = devicePublicKeys.size,
                    localCount = localContacts.size,
                ),
            )
        }
        for (orphan in orphans) {
            logger?.notice(
                fullSyncPruneDeletingLine(
                    name = orphan.name,
                    publicKey = orphan.publicKey,
                    isFavorite = orphan.isFavorite,
                    typeRawValue = orphan.typeRawValue,
                    lastModified = orphan.lastModified,
                ),
            )
            // Keep the contacts phase (and Ready) alive; a leftover orphan retries next full sync.
            runLoggedContactEffect(
                onFailure = { error ->
                    logger?.error("Full sync prune failed for ${orphan.id}: ${error.message ?: error}")
                },
                fallback = {},
            ) {
                store.deleteContact(orphan.id)
                cleanupCoordinator.handleCleanup(orphan.id, ContactCleanupReason.DELETED, orphan.publicKey)
            }
        }
    }
}
