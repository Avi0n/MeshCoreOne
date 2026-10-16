package one.meshcore.shared.remotenode

/**
 * Credential storage for remote-node passwords, keyed by the node's 32-byte public key. Mirrors the
 * Swift `KeychainService.{store,retrieve,delete,has}Password(forNodeKey:)`. Android uses
 * `KeystoreNodePasswordStore`; [InMemoryNodePasswordStore] backs desktop, offline graphs, and tests.
 *
 * The three-axis identity invariant holds: passwords key by public key (the reconciliation axis), never
 * by the volatile connection handle.
 */
interface NodePasswordStore {
    /** Stores [password] for the node identified by [publicKey], overwriting any existing entry. */
    suspend fun storePassword(publicKey: ByteArray, password: String)

    /** The stored password for [publicKey], or null if none. */
    suspend fun retrievePassword(publicKey: ByteArray): String?

    /** Deletes any stored password for [publicKey]; a no-op if absent. */
    suspend fun deletePassword(publicKey: ByteArray)

    /** Whether a password is stored for [publicKey]. */
    suspend fun hasPassword(publicKey: ByteArray): Boolean
}
