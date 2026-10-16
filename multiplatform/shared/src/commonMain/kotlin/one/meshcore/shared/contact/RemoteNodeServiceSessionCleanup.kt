package one.meshcore.shared.contact

import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.RemoteNodeStore
import one.meshcore.shared.remotenode.removeSession

/**
 * Production [RemoteNodeSessionCleanup]: looks up the session by public key and removes it (password +
 * row + keep-alive) through [RemoteNodeService], matching iOS `ContactCleanupCoordinator` on delete.
 */
class RemoteNodeServiceSessionCleanup(
    private val store: RemoteNodeStore,
    private val remoteNodeService: RemoteNodeService,
) : RemoteNodeSessionCleanup {
    override suspend fun removeSession(publicKey: ByteArray) {
        val session = store.fetchSessionByPublicKey(publicKey) ?: return
        remoteNodeService.removeSession(session.id, publicKey)
    }
}
