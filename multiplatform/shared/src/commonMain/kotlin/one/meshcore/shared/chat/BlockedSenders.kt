package one.meshcore.shared.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The blocked-sender check the ingest pipeline consults before persisting a channel message and before
 * incrementing DM/channel unreads. A name-based cache because incoming channel messages carry only a
 * parsed sender name, never a public key. Mirrors Swift `SyncCoordinator.isBlockedSender`.
 */
fun interface BlockedSenders {
    /** Whether [senderName] is blocked. A null name (no parsed sender) is never blocked. */
    suspend fun isBlocked(senderName: String?): Boolean
}

/**
 * In-memory [BlockedSenders] cache refreshed from the store's blocked contacts and blocked channel
 * senders. Matching is exact and case-sensitive, mirroring the Swift set membership test. [refresh]
 * replaces the whole set so an unblock drops the name. The reference the Room-backed wiring reproduces.
 */
class BlockedSendersCache : BlockedSenders {
    private val mutex = Mutex()
    private var blockedNames: Set<String> = emptySet()

    /** Replaces the cached set with [names] (blocked contact names unioned with blocked channel senders). */
    suspend fun refresh(names: Collection<String>) = mutex.withLock {
        blockedNames = names.toSet()
    }

    override suspend fun isBlocked(senderName: String?): Boolean = mutex.withLock {
        senderName != null && blockedNames.contains(senderName)
    }

    /** A snapshot of the cached blocked names; test-only read of the cache. */
    suspend fun snapshot(): Set<String> = mutex.withLock { blockedNames }
}
