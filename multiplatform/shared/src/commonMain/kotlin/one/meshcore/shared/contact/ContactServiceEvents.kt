package one.meshcore.shared.contact

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * A fresh multicast subscription to sync-progress and node-deletion events. Registration is
 * synchronous, so an event yielded after this call returns is never dropped; consumers re-subscribe
 * per connection. Mirrors `events()`.
 */
suspend fun ContactService.events(): ReceiveChannel<ContactServiceEvent> = eventBroadcaster.subscribe()

/** Ends every subscriber's stream so consumer tasks release the service. Mirrors `finishEvents`. */
suspend fun ContactService.finishEvents() = eventBroadcaster.finish()

/**
 * Device push 0x90: the radio reports its contact table is full. Yields
 * [ContactServiceEvent.NodeStorageFullChanged] so shell UI can surface the storage-full state.
 */
suspend fun ContactService.notifyNodeStorageFull() = contactNodeStorage.notifyStorageFull()

/**
 * Drops the 0x90 storage-full banner once overwrite-oldest is verified on the radio. The next
 * auto-add will evict, so a stuck full flag would keep warning after the policy that clears it.
 */
suspend fun ContactService.clearNodeStorageFull() = contactNodeStorage.clearStorageFull()

internal suspend fun ContactService.publishNodeStorageFull(isFull: Boolean) =
    contactNodeStorage.publishStorageFull(isFull)

internal suspend fun ContactService.publishNodeDeleted() = contactNodeStorage.publishNodeDeleted()
