package one.meshcore.shared.contact

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/**
 * Subscribes to session advertisement/path events that must update Room contacts — Kotlin port of the
 * contact-table branches in iOS `AdvertisementService`:
 * - **0x80 [MeshEvent.Advertisement]** — last-advert stamp or auto-add via [ContactService.handleAdvertisement]
 *   (unknown-contact fetches defer while [ContactService.setSyncingContacts] is true)
 * - **pathUpdate** — re-fetch path; **pathResponse** — radio out-path write, then the phone row
 *
 * Discover-list rows are handled separately by [DiscoveredNodeEventCollector], also started from
 * [one.meshcore.shared.connection.ServiceContainer.start]. Owned by
 * [one.meshcore.shared.connection.ServiceContainer] for the life of a connection.
 */
class ContactPathEventCollector(
    var radioID: Uuid,
    private val contactService: ContactService,
    private val scope: CoroutineScope,
    private val logger: PersistentLogger = PersistentLogger(scope, category = LOGGER_CATEGORY),
) {
    private var job: Job? = null

    /** Starts (or restarts) collecting [events]. Idempotent. */
    fun start(events: Flow<MeshEvent>) {
        job?.cancel()
        job = scope.launch {
            events.collect { event ->
                when (event) {
                    is MeshEvent.Advertisement -> handleAdvertisement(event)
                    is MeshEvent.PathUpdate -> handlePathUpdate(event)
                    is MeshEvent.PathResponse -> handlePathResponse(event)
                    is MeshEvent.TraceData ->
                        logger.info(receivedTraceDataLine(event.trace.tag, event.trace.path.size))
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

    private suspend fun handleAdvertisement(event: MeshEvent.Advertisement) {
        logger.loggedContactEvent("Error handling advert event") {
            contactService.handleAdvertisement(radioID, event.publicKey)
        }
    }

    private suspend fun handlePathUpdate(event: MeshEvent.PathUpdate) {
        logger.loggedContactEvent("Error refreshing contact path") {
            contactService.refreshPathFromDevice(radioID, event.publicKey)
        }
    }

    private suspend fun handlePathResponse(event: MeshEvent.PathResponse) {
        logger.loggedContactEvent("Error handling path discovery response") {
            contactService.applyPathDiscoveryResponse(radioID, event.path)
        }
    }

    private companion object {
        const val LOGGER_CATEGORY: String = "AdvertisementService"
    }
}
