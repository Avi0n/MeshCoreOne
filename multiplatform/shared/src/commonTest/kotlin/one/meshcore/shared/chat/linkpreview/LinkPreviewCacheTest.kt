package one.meshcore.shared.chat.linkpreview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Cache tier ordering, negative caching, in-flight coalescing, persistence, and graceful store failure. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkPreviewCacheTest {
    private val url = "https://example.com/page"

    private class CountingFetcher(var result: LinkPreviewFetchResult?) : LinkMetadataFetcher {
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        var failure: Throwable? = null

        override suspend fun fetchMetadata(url: String): LinkPreviewFetchResult? {
            calls++
            gate?.await()
            failure?.let { throw it }
            return result
        }
    }

    private fun fetchResult() =
        LinkPreviewFetchResult(url, title = "Title", imageBytes = byteArrayOf(1, 2, 3), iconBytes = null)

    private fun enabledPrefs() = LinkPreviewPreferences(InMemoryLinkPreviewSettings(previewsEnabled = true))

    @Test
    fun memoryHitAvoidsSecondStoreFetch() = runTest {
        val store = InMemoryLinkPreviewStore()
        val fetcher = CountingFetcher(fetchResult())
        val cache = LinkPreviewCache(fetcher, store, enabledPrefs(), backgroundScope)

        assertIs<LinkPreviewResult.Loaded>(cache.preview(url, isChannelMessage = false))
        val fetchesAfterFirst = store.fetchCount
        // Second lookup hits the warmed memory tier, so the store is not consulted again.
        assertIs<LinkPreviewResult.Loaded>(cache.preview(url, isChannelMessage = false))
        assertEquals(fetchesAfterFirst, store.fetchCount)
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun concurrentRequestsCoalesceToOneFetch() = runTest {
        val store = InMemoryLinkPreviewStore()
        val fetcher = CountingFetcher(fetchResult()).apply { gate = CompletableDeferred() }
        val cache = LinkPreviewCache(fetcher, store, enabledPrefs(), backgroundScope)

        val first = async { cache.preview(url, isChannelMessage = false) }
        val second = async { cache.preview(url, isChannelMessage = false) }
        runCurrent()
        fetcher.gate!!.complete(Unit)

        assertIs<LinkPreviewResult.Loaded>(first.await())
        assertIs<LinkPreviewResult.Loaded>(second.await())
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun cancellingFirstCallerKeepsTheSharedFetch() = runTest {
        val fetcher = CountingFetcher(fetchResult()).apply { gate = CompletableDeferred() }
        val cache = LinkPreviewCache(fetcher, InMemoryLinkPreviewStore(), enabledPrefs(), backgroundScope)

        val first = async { cache.preview(url, isChannelMessage = false) }
        runCurrent()
        first.cancel()
        val second = async { cache.preview(url, isChannelMessage = false) }
        runCurrent()
        fetcher.gate!!.complete(Unit)

        assertIs<LinkPreviewResult.Loaded>(second.await())
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun successfulFetchPersistsToStore() = runTest {
        val store = InMemoryLinkPreviewStore()
        val cache = LinkPreviewCache(CountingFetcher(fetchResult()), store, enabledPrefs(), backgroundScope)

        cache.preview(url, isChannelMessage = false)
        assertEquals(1, store.saveCount)
        assertEquals("Title", store.fetchLinkPreview(url)?.title)
    }

    @Test
    fun nullMetadataIsNegativeCachedAndShortCircuits() = runTest {
        val store = InMemoryLinkPreviewStore()
        val fetcher = CountingFetcher(result = null)
        val cache = LinkPreviewCache(fetcher, store, enabledPrefs(), backgroundScope)

        assertIs<LinkPreviewResult.NoPreviewAvailable>(cache.preview(url, isChannelMessage = false))
        // Second lookup is short-circuited by the negative cache, no second fetch.
        assertIs<LinkPreviewResult.NoPreviewAvailable>(cache.preview(url, isChannelMessage = false))
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun scopeOffParksAsDisabledWithoutFetching() = runTest {
        val fetcher = CountingFetcher(fetchResult())
        val prefs = LinkPreviewPreferences(
            InMemoryLinkPreviewSettings(previewsEnabled = true, autoResolveChannels = false),
        )
        val cache = LinkPreviewCache(fetcher, InMemoryLinkPreviewStore(), prefs, backgroundScope)

        assertIs<LinkPreviewResult.Disabled>(cache.preview(url, isChannelMessage = true))
        assertEquals(0, fetcher.calls)
    }

    @Test
    fun manualFetchBypassesGateAndClearsNegativeCache() = runTest {
        val fetcher = CountingFetcher(result = null)
        val prefs = LinkPreviewPreferences(InMemoryLinkPreviewSettings(previewsEnabled = false))
        val cache = LinkPreviewCache(fetcher, InMemoryLinkPreviewStore(), prefs, backgroundScope)

        // Auto path is disabled and does not fetch.
        assertIs<LinkPreviewResult.Disabled>(cache.preview(url, isChannelMessage = false))
        assertEquals(0, fetcher.calls)

        // Manual tap-to-load bypasses the gate and fetches; a later success is now possible.
        fetcher.result = fetchResult()
        assertIs<LinkPreviewResult.Loaded>(cache.manualFetch(url))
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun storeFailuresAreSwallowed() = runTest {
        val throwingStore = object : LinkPreviewStore {
            override suspend fun fetchLinkPreview(url: String): LinkPreviewData? = error("db down")
            override suspend fun saveLinkPreview(data: LinkPreviewData) = error("db down")
        }
        val cache = LinkPreviewCache(CountingFetcher(fetchResult()), throwingStore, enabledPrefs(), backgroundScope)

        // A failing fetch tier and a failing save must not crash the lookup.
        val result = cache.preview(url, isChannelMessage = false)
        assertIs<LinkPreviewResult.Loaded>(result)
        assertTrue(result.data.title == "Title")
    }

    @Test
    fun cancelledStoreReadStopsBeforeNetworkFetch() = runTest {
        val stopped = CancellationException("read stopped")
        val store = object : LinkPreviewStore {
            override suspend fun fetchLinkPreview(url: String): LinkPreviewData? = throw stopped
            override suspend fun saveLinkPreview(data: LinkPreviewData) = Unit
        }
        val fetcher = CountingFetcher(fetchResult())
        val cache = LinkPreviewCache(fetcher, store, enabledPrefs(), backgroundScope)

        val thrown = assertFailsWith<CancellationException> { cache.preview(url, isChannelMessage = false) }
        assertSame(stopped, thrown)
        assertEquals(0, fetcher.calls)
    }

    @Test
    fun cancelledNetworkFetchCanBeRequestedAgain() = runTest {
        val stopped = CancellationException("fetch stopped")
        val fetcher = CountingFetcher(fetchResult()).apply { failure = stopped }
        val cache = LinkPreviewCache(fetcher, InMemoryLinkPreviewStore(), enabledPrefs(), backgroundScope)

        assertFailsWith<CancellationException> { cache.preview(url, isChannelMessage = false) }
        fetcher.failure = null
        assertIs<LinkPreviewResult.Loaded>(cache.preview(url, isChannelMessage = false))
        assertEquals(2, fetcher.calls)
    }

    @Test
    fun cancelledStoreWriteStopsTheFetch() = runTest {
        val stopped = CancellationException("write stopped")
        val store = object : LinkPreviewStore {
            override suspend fun fetchLinkPreview(url: String): LinkPreviewData? = null
            override suspend fun saveLinkPreview(data: LinkPreviewData): Unit = throw stopped
        }
        val cache = LinkPreviewCache(CountingFetcher(fetchResult()), store, enabledPrefs(), backgroundScope)

        assertFailsWith<CancellationException> { cache.preview(url, isChannelMessage = false) }
    }
}
