package one.meshcore.shared.remotenode

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [NodePasswordStore] behind a [Mutex], keyed by public-key bytes. Used by desktop, offline
 * graphs, and tests; Android uses its Keystore-backed adapter.
 */
class InMemoryNodePasswordStore : NodePasswordStore {
    private val mutex = Mutex()
    private val passwords = mutableMapOf<String, String>()

    override suspend fun storePassword(publicKey: ByteArray, password: String) = mutex.withLock {
        passwords[key(publicKey)] = password
        Unit
    }

    override suspend fun retrievePassword(publicKey: ByteArray): String? = mutex.withLock { passwords[key(publicKey)] }

    override suspend fun deletePassword(publicKey: ByteArray) = mutex.withLock {
        passwords.remove(key(publicKey))
        Unit
    }

    override suspend fun hasPassword(publicKey: ByteArray): Boolean =
        mutex.withLock { passwords.containsKey(key(publicKey)) }

    private fun key(publicKey: ByteArray): String = publicKey.joinToString(",")
}
