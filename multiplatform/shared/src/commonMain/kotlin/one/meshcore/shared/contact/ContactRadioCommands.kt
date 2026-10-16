package one.meshcore.shared.contact

import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid

/** Radio-backed contact commands, separate from advert-driven refresh and discovery state. */
internal class ContactRadioCommands(private val dependencies: Dependencies) {
    data class Dependencies(
        val session: ContactSession,
        val store: ContactStore,
        val now: () -> Long,
        val syncCoordinator: ContactSyncCoordinator,
        val mapNotFound: (Int) -> ContactServiceError?,
        val mapShareError: (Int) -> ContactServiceError?,
        val addOrUpdateContact: suspend (radioID: Uuid, frame: ContactFrame) -> Unit,
    )

    suspend fun resetPath(radioID: Uuid, publicKey: ByteArray) {
        runContactSession(mapDeviceCode = dependencies.mapNotFound) { dependencies.session.resetPath(publicKey) }
        dependencies.store.fetchContact(radioID, publicKey)?.let { contact ->
            dependencies.store.saveContact(
                radioID = radioID,
                frame = contact.floodedContactFrame(asOf = dependencies.now()),
            )
        }
        dependencies.syncCoordinator.notifyContactsChanged()
    }

    suspend fun sendPathDiscovery(publicKey: ByteArray): MessageSentInfo =
        runContactSession(mapDeviceCode = dependencies.mapNotFound) {
            dependencies.session.sendPathDiscovery(publicKey)
        }

    suspend fun shareContact(publicKey: ByteArray) {
        runContactSession(mapDeviceCode = dependencies.mapShareError) {
            dependencies.session.shareContact(publicKey)
        }
    }

    suspend fun setPath(radioID: Uuid, publicKey: ByteArray, path: ByteArray, pathLength: Int) {
        val existing = dependencies.store.fetchContact(radioID, publicKey)
            ?: throw ContactServiceError.ContactNotFound()
        val frame = ContactFrame(
            publicKey = existing.publicKey,
            type = ContactType.fromRaw(existing.typeRawValue) ?: ContactType.CHAT,
            typeRawValue = existing.typeRawValue,
            flags = existing.flags,
            outPathLength = pathLength,
            outPath = path,
            name = existing.name,
            lastAdvertTimestamp = existing.lastAdvertTimestamp,
            latitude = existing.latitude,
            longitude = existing.longitude,
            lastModified = dependencies.now(),
        )
        dependencies.addOrUpdateContact(radioID, frame)
    }
}
