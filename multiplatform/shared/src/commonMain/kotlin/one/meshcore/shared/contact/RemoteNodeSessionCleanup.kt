package one.meshcore.shared.contact

/**
 * Removes any remote-node session associated with a deleted contact's public key. Production uses
 * [RemoteNodeServiceSessionCleanup]. Mirrors the Swift coordinator's
 * `fetchRemoteNodeSession` + `removeSession` on delete.
 */
fun interface RemoteNodeSessionCleanup {
    /** Removes the remote-node session keyed by [publicKey], if one exists. */
    suspend fun removeSession(publicKey: ByteArray)

    /** Stand-in for isolated service tests that do not own the remote-node graph. */
    object NoOp : RemoteNodeSessionCleanup {
        override suspend fun removeSession(publicKey: ByteArray) = Unit
    }
}
