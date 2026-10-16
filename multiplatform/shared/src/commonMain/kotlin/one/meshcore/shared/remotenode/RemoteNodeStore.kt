package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid

/**
 * The saved-session persistence seam [RemoteNodeService] depends on: the `RemoteNodeSession` reads/writes
 * the login, keep-alive, and lifecycle paths issue. Contact routing lives on [RemoteNodeContactStore].
 * [one.meshcore.shared.persistence.RoomNodeStore] provides the production adapter. Reproduces the subset
 * of the Swift `RoomPersisting` the service calls.
 */
interface RemoteNodeStore : RemoteNodeContactStore {
    /** The session with local id [id], or null. */
    suspend fun fetchSessionById(id: Uuid): RemoteNodeSessionDto?

    /** The session whose full 32-byte [publicKey] matches, or null. */
    suspend fun fetchSessionByPublicKey(publicKey: ByteArray): RemoteNodeSessionDto?

    /** The session whose public key starts with [prefix] (6 bytes), or null. */
    suspend fun fetchSessionByPrefix(prefix: ByteArray): RemoteNodeSessionDto?

    /**
     * Every currently-connected session, for BLE-loss teardown and reconnect re-auth. The default returns
     * none so a store that never drives reconnection (a room-only double) need not implement it; the
     * Room-backed adapter overrides it with the real `isConnected` filter.
     */
    suspend fun fetchConnectedSessions(): List<RemoteNodeSessionDto> = emptyList()

    /**
     * Every session scoped to [radioID], for the repeater-session list query. The default returns none so a
     * store that never lists repeater sessions (a room-only double) need not implement it; the Room-backed
     * adapter overrides it.
     */
    suspend fun fetchSessionsByRadioId(radioID: Uuid): List<RemoteNodeSessionDto> = emptyList()

    /** Inserts or updates a session row by its id. */
    suspend fun saveSession(dto: RemoteNodeSessionDto)

    /** Deletes every session sharing [publicKey] except the one with [keepID]. */
    suspend fun cleanupDuplicateSessions(publicKey: ByteArray, keepID: Uuid)

    /** Deletes the session row with local id [id]; a no-op if absent. */
    suspend fun deleteSession(id: Uuid)

    /** Sets a session's connection state and permission level in one write. */
    suspend fun updateSessionConnection(id: Uuid, isConnected: Boolean, permissionLevel: RoomPermissionLevel)

    /**
     * Marks a session disconnected without changing its permission level, for transient drops (BLE loss,
     * keep-alive failure). A no-op if the session is absent.
     */
    suspend fun markSessionDisconnected(id: Uuid)
}
