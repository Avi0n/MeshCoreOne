package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.settings.PersistentLogger

/** Fetches an unknown advertised contact and commits its local contact and Discover side effects. */
internal class ContactAdvertMaterializer(private val dependencies: Dependencies) {
    data class Dependencies(
        val session: ContactSession,
        val store: ContactStore,
        val heardRecorder: ContactHeardRecorder,
        val now: () -> Long,
        val eventBroadcaster: ContactServiceEventBroadcaster,
        val discoveryNotifier: ContactDiscoveryNotifier,
        val logger: PersistentLogger?,
        val discoverTrace: PersistentLogger?,
        val isCancelled: suspend (ByteArray) -> Boolean,
        val clearCancellation: suspend (ByteArray) -> Unit,
    )

    suspend fun fetchAndSave(radioID: Uuid, publicKey: ByteArray, heardAt: Long?): Boolean {
        if (dependencies.isCancelled(publicKey)) return false
        val frame = fetchFrame(publicKey)
        val cancelledAfterFetch = frame != null && dependencies.isCancelled(publicKey)
        if (cancelledAfterFetch) dependencies.clearCancellation(publicKey)
        return if (frame == null || cancelledAfterFetch) {
            false
        } else {
            persist(radioID, publicKey, frame, heardAt)
        }
    }

    suspend fun fetchFrame(publicKey: ByteArray): ContactFrame? {
        val pubKeyHex = publicKey.toUppercaseHex()
        val frame = try {
            runContactSession(mapDeviceCode = null) {
                dependencies.session.getContact(publicKey)
            }?.toContactFrame()
        } catch (error: ContactServiceError) {
            dependencies.logger?.error("Failed to fetch contact: ${error.message ?: error}")
            dependencies.discoverTrace?.error("B2 getContact THREW key=$pubKeyHex: ${error.message ?: error}")
            throw error
        }
        if (frame == null) {
            dependencies.discoverTrace?.notice("B2 getContact returned nil key=$pubKeyHex")
        }
        return frame
    }

    suspend fun persist(radioID: Uuid, publicKey: ByteArray, frame: ContactFrame, heardAt: Long?): Boolean {
        val pubKeyHex = publicKey.toUppercaseHex()
        return runLoggedContactEffect(
            onFailure = { error ->
                dependencies.logger?.error("Failed to save fetched contact: ${error.message ?: error}")
                dependencies.discoverTrace?.error(
                    "B2 0x80 saveContact FAILED key=$pubKeyHex: ${error.message ?: error}",
                )
            },
            fallback = { false },
        ) {
            val isNew = dependencies.store.fetchContact(radioID, frame.publicKey) == null
            val contactID = dependencies.store.saveContact(radioID = radioID, frame = frame)
            if (dependencies.isCancelled(publicKey)) {
                rollbackInsertIfNeeded(contactID, isNew)
                false
            } else {
                stampHeard(radioID, publicKey, heardAt, pubKeyHex)
                dependencies.eventBroadcaster.yield(
                    ContactServiceEvent.ContactAdvertisementApplied(radioID, publicKey.copyOf()),
                )
                if (isNew) dependencies.discoveryNotifier.notifyNewContact(contactID, frame.name, frame.type)
                true
            }
        }
    }

    suspend fun rollbackInsertIfNeeded(id: Uuid, isNew: Boolean) {
        if (!isNew) return
        runLoggedContactEffect(
            onFailure = { error ->
                dependencies.logger?.warning(insertOnlyRollbackFailedLine(id, error.message ?: error.toString()))
            },
            fallback = {},
        ) {
            dependencies.store.deleteContactIfUnreferenced(id)
        }
    }

    private suspend fun stampHeard(radioID: Uuid, publicKey: ByteArray, heardAt: Long?, pubKeyHex: String) {
        runLoggedContactEffect(
            onFailure = { error ->
                dependencies.logger?.error(
                    "Advert lastHeard stamp failed key=$pubKeyHex: ${error.message ?: error}",
                )
            },
            fallback = {},
        ) {
            dependencies.heardRecorder.stampHeardOnAir(
                radioID,
                publicKey,
                dependencies.heardRecorder.clampedPhoneClockTimestamp(heardAt ?: dependencies.now()),
            )
            Unit
        }
    }
}
