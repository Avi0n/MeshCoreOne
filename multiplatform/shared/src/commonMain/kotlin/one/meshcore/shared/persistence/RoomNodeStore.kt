package one.meshcore.shared.persistence

import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.RemoteNodeStore
import one.meshcore.shared.room.RoomStore

/**
 * The Room-backed unified session store, the persistence adapter behind both the remote-node login seam
 * ([RemoteNodeStore]) and the room seam ([RoomStore]) — one instance over one `remote_node_session` table,
 * so a login's connection flip is visible to the room reads and a saved repeater/room session surfaces in
 * the conversation list (which reads the same DAO). It reproduces `InMemoryRoomStore`'s semantics against
 * Room; the read-modify-write mutations are serialized behind [RoomNodeParts.mutex] so an interleaved
 * connection flip and unread bump can't lose a write.
 *
 * A byte-prefix match has no portable SQL form, so the by-public-key / by-prefix / connected reads and the
 * duplicate cleanup filter `getAll()` in Kotlin (the session table holds one row per saved node). Room
 * messages have no by-id DAO read, so the three by-id message paths locate the row by scanning this
 * connection's sessions — rare paths (retry / status update) over a small per-session message set.
 */
class RoomNodeStore internal constructor(private val parts: RoomNodeParts) :
    RoomStore by parts.roomStore,
    RemoteNodeStore by parts.remoteStore {
    constructor(
        radioId: Uuid,
        sessionDao: RemoteNodeSessionDao,
        roomMessageDao: RoomMessageDao,
        contactDao: ContactDao,
        now: () -> Double,
    ) : this(
        RoomNodeParts(
            initialRadioId = radioId,
            sessionDao = sessionDao,
            roomMessageDao = roomMessageDao,
            contactDao = contactDao,
            now = now,
        ),
    )

    internal var radioId: Uuid
        get() = parts.radioId
        set(value) {
            parts.radioId = value
        }

    override suspend fun fetchSessionByPrefix(prefix: ByteArray): RemoteNodeSessionDto? =
        parts.roomStore.fetchSessionByPrefix(prefix)

    override suspend fun contactFloodRouted(radioID: Uuid, publicKey: ByteArray): Boolean? =
        parts.roomStore.contactFloodRouted(radioID, publicKey)
}
