package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/** A contact store that can fail [ContactStore.touchContactHeard] and otherwise forwards to [inner]. */
internal class HeardStampFaultStore(private val inner: InMemoryContactStore) : ContactStore by inner {
    var touchError: Throwable? = null

    override suspend fun touchContactHeard(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean {
        touchError?.let { throw it }
        return inner.touchContactHeard(radioID, publicKey, atEpochSeconds)
    }
}
