package one.meshcore.shared.urlsafety

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Load-order and refresh vectors for [RefreshingBlocklistSource]: disk wins over bundle, missing
 * sources fail open, stale lists refresh from the first successful mirror, and a mid-session
 * refresh replaces the filter's in-memory set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshingBlocklistSourceTest {
    private val diskHosts = """
        # disk
        0.0.0.0 disk.evil
    """.trimIndent()

    private val bundledHosts = """
        # bundle
        0.0.0.0 bundled.evil
    """.trimIndent()

    private val networkHosts = """
        # network
        0.0.0.0 network.evil
        0.0.0.0 second.evil
    """.trimIndent()

    @Test
    fun isStaleWhenNoPriorRefresh() {
        assertTrue(
            MalwareBlocklistRefresh.isStale(
                lastRefreshEpochMs = null,
                nowEpochMs = 1_000L,
                refreshIntervalMs = 7 * DAY_MS,
            ),
        )
    }

    @Test
    fun isFreshWithinRefreshInterval() {
        assertFalse(
            MalwareBlocklistRefresh.isStale(
                lastRefreshEpochMs = 1_000L,
                nowEpochMs = 1_000L + 6 * DAY_MS,
                refreshIntervalMs = 7 * DAY_MS,
            ),
        )
    }

    @Test
    fun isStaleAtOrAfterRefreshInterval() {
        assertTrue(
            MalwareBlocklistRefresh.isStale(
                lastRefreshEpochMs = 1_000L,
                nowEpochMs = 1_000L + 7 * DAY_MS,
                refreshIntervalMs = 7 * DAY_MS,
            ),
        )
    }

    @Test
    fun prefersDiskCacheOverBundled() = runTest {
        val source = source(
            disk = diskHosts,
            bundled = bundledHosts,
            lastRefresh = now(),
        )
        assertEquals(setOf("disk.evil"), source.load())
    }

    @Test
    fun fallsBackToBundledWhenDiskMissing() = runTest {
        val source = source(
            disk = null,
            bundled = bundledHosts,
            lastRefresh = now(),
        )
        assertEquals(setOf("bundled.evil"), source.load())
    }

    @Test
    fun returnsNullWhenNoLocalSource() = runTest {
        val source = source(
            disk = null,
            bundled = null,
            lastRefresh = now(),
        )
        assertNull(source.load())
    }

    @Test
    fun refreshesWhenStaleAndWritesCache() = runTest {
        var disk: String? = diskHosts
        var lastRefresh: Long? = null
        val fetches = mutableListOf<String>()
        val refreshed = mutableListOf<Set<String>>()
        val source = mutableSource(
            MutableSourceConfig(
                diskProvider = { disk },
                writeDisk = { disk = it },
                bundled = bundledHosts,
                fetch = { url ->
                    fetches += url
                    networkHosts
                },
                lastRefreshProvider = { lastRefresh },
                setLastRefresh = { lastRefresh = it },
                onRefreshed = { refreshed += it },
            ),
        )

        assertEquals(setOf("disk.evil"), source.load())
        advanceUntilIdle()

        assertEquals(listOf(MalwareDomainFilter.PRIMARY_URL), fetches)
        assertEquals(networkHosts, disk)
        assertEquals(now(), lastRefresh)
        assertEquals(listOf(setOf("network.evil", "second.evil")), refreshed)
    }

    @Test
    fun triesFallbackWhenPrimaryFails() = runTest {
        val fetches = mutableListOf<String>()
        var disk: String? = bundledHosts
        val source = mutableSource(
            MutableSourceConfig(
                diskProvider = { disk },
                writeDisk = { disk = it },
                bundled = bundledHosts,
                fetch = { url ->
                    fetches += url
                    if (url == MalwareDomainFilter.PRIMARY_URL) null else networkHosts
                },
                lastRefreshProvider = { null },
                setLastRefresh = {},
            ),
        )

        source.load()
        advanceUntilIdle()

        assertEquals(
            listOf(MalwareDomainFilter.PRIMARY_URL, MalwareDomainFilter.FALLBACK_URL),
            fetches,
        )
        assertEquals(networkHosts, disk)
    }

    @Test
    fun skipsNetworkWhenFresh() = runTest {
        var fetches = 0
        val source = source(
            disk = diskHosts,
            bundled = bundledHosts,
            fetch = {
                fetches++
                networkHosts
            },
            lastRefresh = now() - DAY_MS,
        )

        source.load()
        advanceUntilIdle()

        assertEquals(0, fetches)
    }

    @Test
    fun midSessionRefreshReplacesFilterDomains() = runTest {
        var disk: String? = diskHosts
        lateinit var filter: MalwareDomainFilter
        val source = mutableSource(
            MutableSourceConfig(
                diskProvider = { disk },
                writeDisk = { disk = it },
                bundled = null,
                fetch = { networkHosts },
                lastRefreshProvider = { null },
                setLastRefresh = {},
                onRefreshed = { filter.replaceDomains(it) },
            ),
        )
        filter = MalwareDomainFilter(source)

        assertTrue(filter.isBlocked("disk.evil"))
        assertFalse(filter.isBlocked("network.evil"))
        advanceUntilIdle()
        assertTrue(filter.isBlocked("network.evil"))
        assertFalse(filter.isBlocked("disk.evil"))
    }

    private fun TestScope.source(
        disk: String? = null,
        bundled: String? = null,
        fetch: suspend (String) -> String? = { null },
        lastRefresh: Long? = null,
        onRefreshed: (Set<String>) -> Unit = {},
    ): RefreshingBlocklistSource = RefreshingBlocklistSource(
        BlocklistSourceDeps(
            readDiskCache = { disk },
            writeDiskCache = {},
            readBundled = { bundled },
            fetchText = fetch,
            lastRefreshEpochMs = { lastRefresh },
            setLastRefreshEpochMs = {},
            nowEpochMs = { NOW_MS },
            // TestScope dispatcher: refresh is enqueued and drained by advanceUntilIdle.
            scope = this,
            onRefreshed = onRefreshed,
            refreshIntervalMs = 7 * DAY_MS,
        ),
    )

    private fun TestScope.mutableSource(config: MutableSourceConfig): RefreshingBlocklistSource =
        RefreshingBlocklistSource(
            BlocklistSourceDeps(
                readDiskCache = config.diskProvider,
                writeDiskCache = config.writeDisk,
                readBundled = { config.bundled },
                fetchText = config.fetch,
                lastRefreshEpochMs = config.lastRefreshProvider,
                setLastRefreshEpochMs = config.setLastRefresh,
                nowEpochMs = { NOW_MS },
                scope = this,
                onRefreshed = config.onRefreshed,
                refreshIntervalMs = 7 * DAY_MS,
            ),
        )

    private data class MutableSourceConfig(
        val diskProvider: suspend () -> String?,
        val writeDisk: suspend (String) -> Unit,
        val bundled: String?,
        val fetch: suspend (String) -> String?,
        val lastRefreshProvider: suspend () -> Long?,
        val setLastRefresh: suspend (Long) -> Unit,
        val onRefreshed: (Set<String>) -> Unit = {},
    )

    private fun now(): Long = NOW_MS

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val NOW_MS = 1_700_000_000_000L
    }
}
