package one.meshcore.shared.contact

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.meshcore.shared.settings.PersistentLogger

/** Owns the latest radio contact-table capacity state and its multicast change events. */
internal class ContactNodeStorage(
    private val eventBroadcaster: ContactServiceEventBroadcaster,
    private val logger: PersistentLogger?,
) {
    private val mutableIsFull = MutableStateFlow(false)
    val isFull: StateFlow<Boolean> = mutableIsFull.asStateFlow()

    suspend fun publishStorageFull(isFull: Boolean) {
        mutableIsFull.value = isFull
        eventBroadcaster.yield(ContactServiceEvent.NodeStorageFullChanged(isFull = isFull))
    }

    suspend fun publishNodeDeleted() {
        mutableIsFull.value = false
        eventBroadcaster.yield(ContactServiceEvent.NodeDeleted)
    }

    suspend fun notifyStorageFull() {
        logger?.warning(
            "Device node storage is full - if overwrite oldest is enabled, the next new node will " +
                "trigger auto-deletion of the oldest non-favorite contact",
        )
        publishStorageFull(isFull = true)
    }

    suspend fun clearStorageFull() {
        publishStorageFull(isFull = false)
    }
}
