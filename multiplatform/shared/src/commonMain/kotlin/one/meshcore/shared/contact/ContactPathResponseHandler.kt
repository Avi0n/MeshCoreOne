package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.meshcore.PathInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/**
 * Applies a path-discovery response. A decodable out path is written with [writeAcceptedPath]
 * before success is reported. The phone row changes only after the radio accepts, and a reserved length
 * byte is not written. A failed last-heard stamp is logged and the radio write still proceeds.
 * Cancellation stops the caller before that write.
 */
internal class ContactPathResponseHandler(
    private val dependencies: Dependencies,
    private val writeAcceptedPath: suspend (Uuid, ByteArray, ByteArray, Int) -> Unit,
) {
    data class Dependencies(
        val store: ContactStore,
        val heardRecorder: ContactHeardRecorder,
        val now: () -> Long,
        val syncCoordinator: ContactSyncCoordinator,
        val logger: PersistentLogger?,
        val eventBroadcaster: ContactServiceEventBroadcaster,
    )

    suspend fun apply(radioID: Uuid, path: PathInfo): Boolean {
        dependencies.logger?.info(pathDiscoveryDumpLine(path))
        val existing = matchingContact(radioID, path.publicKeyPrefix)
        val hopCount = PathEncoding.decodePathLen(path.outPathLength)?.hopCount
        val accepted = if (existing == null) {
            reportMissing(path, hopCount)
        } else {
            reportKnown(radioID, existing, path, hopCount)
        }
        return accepted
    }

    private suspend fun reportMissing(path: PathInfo, hopCount: Int?): Boolean {
        if (hopCount != null) {
            dependencies.eventBroadcaster.yield(
                ContactServiceEvent.PathDiscoveryWriteFailed(path, ContactServiceError.ContactNotFound()),
            )
            return false
        }
        dependencies.eventBroadcaster.yield(ContactServiceEvent.PathDiscoveryAccepted(path))
        return true
    }

    private suspend fun reportKnown(radioID: Uuid, existing: ContactDto, path: PathInfo, hopCount: Int?): Boolean {
        stampHeard(radioID, existing.publicKey)
        if (hopCount != null && !writePath(radioID, existing.publicKey, path)) return false
        if (hopCount == null) dependencies.syncCoordinator.notifyContactsChanged()
        dependencies.eventBroadcaster.yield(ContactServiceEvent.PathDiscoveryAccepted(path))
        return true
    }

    private suspend fun stampHeard(radioID: Uuid, publicKey: ByteArray) {
        runLoggedContactEffect(
            onFailure = { error ->
                dependencies.logger?.error("Path response lastHeard stamp failed: ${error.message ?: error}")
            },
            fallback = {},
        ) {
            dependencies.heardRecorder.stampHeardOnAir(
                radioID,
                publicKey,
                dependencies.heardRecorder.clampedPhoneClockTimestamp(dependencies.now()),
            )
            Unit
        }
    }

    private suspend fun writePath(radioID: Uuid, publicKey: ByteArray, path: PathInfo): Boolean = try {
        writeAcceptedPath(radioID, publicKey, path.outPath, path.outPathLength)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ContactServiceError) {
        dependencies.logger?.error("Path discovery radio write failed: ${error.message ?: error}")
        dependencies.eventBroadcaster.yield(ContactServiceEvent.PathDiscoveryWriteFailed(path, error))
        false
    }

    private suspend fun matchingContact(radioID: Uuid, prefix: ByteArray) = if (prefix.isEmpty()) {
        null
    } else {
        dependencies.store.fetchContacts(radioID).firstOrNull { contact ->
            contact.publicKey.size >= prefix.size &&
                contact.publicKey.copyOfRange(0, prefix.size).contentEquals(prefix)
        }
    }
}
