package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.model.Uuid

/**
 * Contact routing and upserts that [RemoteNodeStore] reads for keep-alive, login heal, and flood recovery.
 * [fetchContact] and [saveContact] default to empty so a room-only store can skip them.
 */
interface RemoteNodeContactStore {
    /**
     * The contact's routing for a keep-alive direct-path check: true when flood-routed, false when
     * directly routed, or null when no contact row exists for `(radioID, publicKey)`. The service reads
     * null as [RemoteNodeError.ContactNotFound] and true as [RemoteNodeError.FloodRouted].
     */
    suspend fun contactFloodRouted(radioID: Uuid, publicKey: ByteArray): Boolean?

    /**
     * The `(radioID, publicKey)` contact row, or null when absent, for the login auto-heal and CLI
     * flood-recovery paths that re-add or re-route a contact. The default returns null so a store that never
     * drives those paths (a room-only double) need not implement it; the Room adapter overrides it.
     */
    suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? = null

    /**
     * Upserts one contact from a device [frame], matched by `(radioID, publicKey)`, so a locally-mirrored
     * flood reroute agrees with the radio. The default is a no-op for the same reason as [fetchContact].
     */
    suspend fun saveContact(radioID: Uuid, frame: ContactFrame) {}
}
