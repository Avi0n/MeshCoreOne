package one.meshcore.shared.room

import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.RemoteNodeStore

/**
 * In-memory [RoomStore] and [RemoteNodeStore] behind one [InMemoryRoomParts.mutex]. One instance backs both
 * the room services and the [one.meshcore.shared.remotenode.RemoteNodeService] they drive, so a login's
 * connection flip is visible to the room reads — the single session table a Room-backed adapter must
 * reproduce, and the test backing. Contact display names and routing are settable maps so author resolution
 * and history-sync path checks are testable in isolation.
 */
class InMemoryRoomStore internal constructor(private val parts: InMemoryRoomParts) :
    RoomStore by parts.roomStore,
    RemoteNodeStore by parts.remoteStore {
    constructor() : this(InMemoryRoomParts())

    /** Seeds a session row. */
    suspend fun seedSession(dto: RemoteNodeSessionDto) = parts.remoteSessions.seedSession(dto)

    /** Seeds a contact whose public key starts with [prefix] and resolves to [name]; absent means "unknown". */
    suspend fun setContactName(prefix: ByteArray, name: String) = parts.contacts.setContactName(prefix, name)

    /** Seeds a contact's flood-routed flag for `(radioID, publicKey)`; absent means "contact not found". */
    suspend fun setContactRouting(radioID: Uuid, publicKey: ByteArray, isFloodRouted: Boolean) =
        parts.contacts.setContactRouting(radioID, publicKey, isFloodRouted)

    override suspend fun fetchSessionByPrefix(prefix: ByteArray): RemoteNodeSessionDto? =
        parts.roomStore.fetchSessionByPrefix(prefix)

    override suspend fun contactFloodRouted(radioID: Uuid, publicKey: ByteArray): Boolean? =
        parts.roomStore.contactFloodRouted(radioID, publicKey)
}
