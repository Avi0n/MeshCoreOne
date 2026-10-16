package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/**
 * Rebuilds the blocked-sender name cache for a radio after a block/unblock. Kotlin port of the Swift
 * `SyncCoordinator.refreshBlockedContactsCache`. A seam so [ContactCleanupCoordinator] and
 * [BlockedChannelSenderService] can trigger the refresh without owning the cache implementation.
 */
fun interface BlockedNamesRefreshing {
    /** Recomputes and republishes the blocked-name set for [radioID]. */
    suspend fun refresh(radioID: Uuid)
}

/** A refresher that does nothing; the stand-in for tests that do not assert on the cache. */
object NoOpBlockedNamesRefreshing : BlockedNamesRefreshing {
    override suspend fun refresh(radioID: Uuid) = Unit
}

/**
 * The blocked-name set sink the refresh publishes into. Its single method matches the signature of the
 * chat `BlockedSendersCache.refresh`, so container wiring binds this to that cache directly (a method
 * reference) without this package depending on `chat`. The ingest pipeline reads the same cache before
 * persisting a channel message.
 */
fun interface BlockedNamesCache {
    /** Replaces the cached blocked-name set (blocked contact names unioned with blocked channel senders). */
    suspend fun refresh(names: Collection<String>)
}

/**
 * Production [BlockedNamesRefreshing]: unions the radio's blocked contact names with its blocked channel
 * sender names and republishes them into the [cache]. Mirrors the Swift refresh, which reads both
 * `fetchBlockedContacts` and `fetchBlockedChannelSenders`. An empty match clears the cache, so an unblock
 * drops the name.
 */
class ContactBlockedNamesRefresher(
    private val contactStore: ContactStore,
    private val blockedSenderStore: BlockedChannelSenderStore,
    private val cache: BlockedNamesCache,
    private val logger: PersistentLogger? = null,
) : BlockedNamesRefreshing {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun refresh(radioID: Uuid) {
        val names = try {
            buildSet {
                contactStore.fetchBlockedContacts(radioID).forEach { add(it.name) }
                blockedSenderStore.list(radioID).forEach { add(it.name) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger?.error("Failed to refresh blocked names cache: ${error.message ?: error}")
            emptySet<String>()
        }
        cache.refresh(names)
    }
}
