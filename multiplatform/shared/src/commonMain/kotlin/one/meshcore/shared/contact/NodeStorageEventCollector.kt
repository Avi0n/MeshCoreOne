package one.meshcore.shared.contact

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/**
 * Subscribes to device node-storage pushes and mirrors iOS `AdvertisementService`:
 * - **0x90 [MeshEvent.ContactsFull]** — radio contact table is full; notify UI via
 *   [ContactService.notifyNodeStorageFull].
 * - **0x8F [MeshEvent.ContactDeleted]** — device overwrite-oldest deleted a contact; remove the local
 *   row and messages through [ContactService.handleDeviceContactDeleted].
 *
 * Owned by [one.meshcore.shared.connection.ServiceContainer] for the life of a connection.
 */
class NodeStorageEventCollector(
    var radioID: Uuid,
    private val contactService: ContactService,
    private val scope: CoroutineScope,
    private val logger: PersistentLogger = PersistentLogger(scope, category = "AdvertisementService"),
) {
    private var job: Job? = null

    /** Starts (or restarts) collecting [events]. Idempotent. */
    fun start(events: Flow<MeshEvent>) {
        job?.cancel()
        job = scope.launch {
            events.collect { event ->
                when (event) {
                    is MeshEvent.ContactsFull -> handleContactsFull()
                    is MeshEvent.ContactDeleted -> handleContactDeleted(event)
                    else -> Unit
                }
            }
        }
    }

    /** Cancels the collector so it releases the service capture. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun handleContactsFull() {
        logger.loggedContactEvent("Error handling contacts-full event") {
            contactService.notifyNodeStorageFull()
        }
    }

    private suspend fun handleContactDeleted(event: MeshEvent.ContactDeleted) {
        logger.loggedContactEvent("Error handling contact-deleted event") {
            contactService.handleDeviceContactDeleted(radioID, event.publicKey)
        }
    }
}
