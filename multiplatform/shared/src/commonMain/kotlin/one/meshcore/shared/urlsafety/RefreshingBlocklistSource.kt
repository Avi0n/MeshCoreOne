package one.meshcore.shared.urlsafety

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * I/O and clock seams for [RefreshingBlocklistSource]. Keeps the production source constructor short
 * while remaining fully injectable for unit tests.
 */
data class BlocklistSourceDeps(
    val readDiskCache: suspend () -> String?,
    val writeDiskCache: suspend (String) -> Unit,
    val readBundled: suspend () -> String?,
    val fetchText: suspend (url: String) -> String?,
    val lastRefreshEpochMs: suspend () -> Long?,
    val setLastRefreshEpochMs: suspend (Long) -> Unit,
    val nowEpochMs: () -> Long,
    val scope: CoroutineScope,
    val onRefreshed: (Set<String>) -> Unit = {},
    val primaryUrl: String = MalwareDomainFilter.PRIMARY_URL,
    val fallbackUrl: String = MalwareDomainFilter.FALLBACK_URL,
    val refreshIntervalMs: Long = RefreshingBlocklistSource.defaultRefreshIntervalMs(),
)

/**
 * Production [BlocklistSource]: disk cache → bundled snapshot, then a background network refresh when
 * the list is stale. Mirrors the Swift `MalwareDomainFilter` load order. Network failures are silent —
 * the local list (or an empty set) keeps serving until the next attempt.
 *
 * [BlocklistSourceDeps.onRefreshed] is invoked after a successful download so the owning
 * [MalwareDomainFilter] can replace its in-memory set mid-session (Swift actor parity).
 */
class RefreshingBlocklistSource(private val deps: BlocklistSourceDeps) : BlocklistSource {
    private val refreshMutex = Mutex()
    private var refreshScheduled = false

    override suspend fun load(): Set<String>? {
        val local = loadLocal()
        scheduleRefreshIfNeeded()
        return local
    }

    private suspend fun loadLocal(): Set<String>? {
        val text = deps.readDiskCache() ?: deps.readBundled() ?: return null
        return MalwareDomainFilter.parseHostsFile(text).takeIf { it.isNotEmpty() }
    }

    private fun scheduleRefreshIfNeeded() {
        if (refreshScheduled) return
        refreshScheduled = true
        deps.scope.launch {
            if (!MalwareBlocklistRefresh.isStale(
                    lastRefreshEpochMs = deps.lastRefreshEpochMs(),
                    nowEpochMs = deps.nowEpochMs(),
                    refreshIntervalMs = deps.refreshIntervalMs,
                )
            ) {
                return@launch
            }
            refreshMutex.withLock { refreshFromNetwork() }
        }
    }

    private suspend fun refreshFromNetwork() {
        val urls = listOf(deps.primaryUrl, deps.fallbackUrl)
        val text = urls.firstNotNullOfOrNull { url ->
            deps.fetchText(url)?.takeIf { MalwareDomainFilter.parseHostsFile(it).isNotEmpty() }
        } ?: return
        val domains = MalwareDomainFilter.parseHostsFile(text)
        deps.writeDiskCache(text)
        deps.setLastRefreshEpochMs(deps.nowEpochMs())
        deps.onRefreshed(domains)
    }

    companion object {
        /** Seven days in milliseconds — [MalwareDomainFilter.REFRESH_INTERVAL_DAYS]. */
        fun defaultRefreshIntervalMs(): Long = MalwareDomainFilter.REFRESH_INTERVAL_DAYS * MS_PER_DAY

        private const val MS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}

/** Pure staleness helper for the malware blocklist refresh cadence. */
object MalwareBlocklistRefresh {
    /**
     * Returns true when no prior successful refresh is recorded, or when [nowEpochMs] is at least
     * [refreshIntervalMs] after [lastRefreshEpochMs].
     */
    fun isStale(lastRefreshEpochMs: Long?, nowEpochMs: Long, refreshIntervalMs: Long): Boolean {
        if (lastRefreshEpochMs == null) return true
        return nowEpochMs - lastRefreshEpochMs >= refreshIntervalMs
    }
}
