package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/**
 * Syncs contacts from the radio into local storage. A full sync ([since] null) additionally prunes
 * local rows the device no longer reports; an incremental sync ([since] set) upserts only and never
 * prunes. [radioID] is the caller's `device.radioID`. Mirrors `syncContacts(radioID:since:)`.
 *
 * Defers advert-driven unknown-contact fetches for the duration of the bulk pull (iOS
 * `setSyncingContacts` around contact sync). Nested inside a connect-window hold, the inner
 * false does not re-arm delta GET_CONTACTS until the outer hold drops.
 */
suspend fun ContactService.syncContacts(radioID: Uuid, since: Long? = null): ContactSyncResult =
    withContactSyncOperation { contactBulkSync.syncContacts(radioID, since) }

/**
 * User-initiated Nodes refresh. Raises [setSyncingContacts] then claims the advert-delta
 * mutex so a 0x80 round returns BUSY instead of interleaving GET_CONTACTS. Mirrors iOS
 * `syncContactsForRefresh`.
 */
suspend fun ContactService.syncContactsForRefresh(radioID: Uuid): ContactSyncResult =
    withContactSyncOperation { contactBulkSync.syncContactsForRefresh(radioID) }

/**
 * Removes local contacts a full fetch proves are gone from the device.
 *
 * Prunes only on a complete snapshot: received count must meet the `contactsStart` total. A
 * truncated stream leaves keys missing without a real device deletion. A nil total means the
 * header never arrived. Without a usable self public key the V-contact cannot be identified, so
 * the prune skips rather than risk deleting a local V-contact row firmware omitted.
 */
internal suspend fun ContactService.pruneOrphans(radioID: Uuid, devicePublicKeys: Set<String>, reportedTotal: Int?) =
    contactPruner.pruneOrphans(radioID, devicePublicKeys, reportedTotal)
