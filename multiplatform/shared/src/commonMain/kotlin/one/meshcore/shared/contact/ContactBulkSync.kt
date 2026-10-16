package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex

/** Fetches contact snapshots from the radio and commits them to the local radio partition. */
internal class ContactBulkSync(
    private val session: ContactSession,
    private val store: ContactStore,
    private val eventBroadcaster: ContactServiceEventBroadcaster,
    private val advertSyncClaim: AdvertContactSyncClaim,
    private val pruner: ContactPruner,
    private val setSyncingContacts: suspend (radioID: Uuid, isSyncing: Boolean) -> Unit,
) {
    suspend fun syncContacts(radioID: Uuid, since: Long? = null): ContactSyncResult {
        setSyncingContacts(radioID, true)
        try {
            val fetch = runContactSession(mapDeviceCode = null) { session.getContactsReportingTotal(since) }
            val meshContacts = fetch.contacts
            eventBroadcaster.yield(ContactServiceEvent.SyncProgress(received = 0, total = meshContacts.size))

            val devicePublicKeys = meshContacts.map { it.publicKey.toUppercaseHex() }.toSet()
            val frames = meshContacts.map { it.toContactFrame() }
            // radioID flows straight from the caller into the keyed batch upsert; never minted here.
            val receivedCount = store.batchSaveContacts(radioID = radioID, frames = frames)
            val lastTimestamp = meshContacts.maxOfOrNull { it.lastModified } ?: 0L

            eventBroadcaster.yield(
                ContactServiceEvent.SyncProgress(received = receivedCount, total = meshContacts.size),
            )

            if (since == null) {
                pruner.pruneOrphans(
                    radioID = radioID,
                    devicePublicKeys = devicePublicKeys,
                    reportedTotal = fetch.reportedTotal,
                )
            }

            return ContactSyncResult(
                contactsReceived = receivedCount,
                lastSyncTimestamp = lastTimestamp,
                isIncremental = since != null,
            )
        } finally {
            setSyncingContacts(radioID, false)
        }
    }

    suspend fun syncContactsForRefresh(radioID: Uuid): ContactSyncResult {
        setSyncingContacts(radioID, true)
        try {
            if (!advertSyncClaim.claimManualContactSync()) {
                throw ContactServiceError.SyncInterrupted()
            }
            return syncContacts(radioID)
        } finally {
            advertSyncClaim.setManualSyncActive(false)
            setSyncingContacts(radioID, false)
        }
    }
}
