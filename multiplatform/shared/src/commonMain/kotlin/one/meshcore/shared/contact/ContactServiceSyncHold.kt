package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/**
 * Toggle deferred contact fetching during sync. Nested true/false pairs keep the deferral until
 * the outermost hold releases, so connect-window [syncContacts] cannot drain mid-channel.
 * When the last hold drops, drains any 0x80 keys queued while sync was active (or re-arms the
 * delta round when a handler is installed). Mirrors iOS `AdvertisementService.setSyncingContacts`.
 */
suspend fun ContactService.setSyncingContacts(radioID: Uuid, isSyncing: Boolean) =
    syncHold.setSyncingContacts(radioID, isSyncing)
