package one.meshcore.shared.chat.linkpreview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import one.meshcore.shared.chat.CachedDecodedImage
import one.meshcore.shared.chat.ImageDimensionReader
import one.meshcore.shared.chat.ImagePixelSize
import one.meshcore.shared.chat.InlineImageCache
import one.meshcore.shared.chat.InlineImageHttpResult
import one.meshcore.shared.chat.InlineImageResult

/** The outcome of a link-preview lookup, mirroring the resolved cases of the Swift `LinkPreviewResult`. */
sealed interface LinkPreviewResult {
    data class Loaded(val data: LinkPreviewData) : LinkPreviewResult
    data object NoPreviewAvailable : LinkPreviewResult
    data object Disabled : LinkPreviewResult
}

/** The network metadata fetch the cache coalesces, mirroring the Swift `LinkMetadataFetching`. */
fun interface LinkMetadataFetcher {
    suspend fun fetchMetadata(url: String): LinkPreviewFetchResult?

    /**
     * Whether [url] clears the SSRF gate, for a URL the view fetches itself instead of through
     * [fetchMetadata] (the inline-image path, where the image loader owns the request). The implementation
     * holds the platform host resolver. Fails closed: a fetcher that does not answer permits nothing.
     */
    suspend fun isDirectFetchAllowed(url: String): Boolean = false

    /**
     * Whether [url] serves an HTML landing page. Default false so test fetchers that only
     * implement [fetchMetadata] keep treating image-extension URLs as images.
     */
    suspend fun servesHtmlPage(url: String): Boolean = false

    /**
     * Width and height of a direct image URL, or null when the probe is unavailable or fails.
     * Test fetchers that only implement [fetchMetadata] skip the probe.
     */
    suspend fun probeImageDimensions(url: String): ImagePixelSize? = null

    /**
     * Direct-image GET for the inline-image cache. Default fails closed so test fetchers that only
     * implement [fetchMetadata] do not fetch.
     */
    suspend fun fetchInlineImage(url: String): InlineImageHttpResult = InlineImageHttpResult.Failed
}

/**
 * The two-tier link-preview cache with URL-keyed de-duplication, a verbatim port of the Swift
 * `LinkPreviewCache`. Lookup order (§6.3): negative cache → memory LRU → persistent store → preference gate
 * → coalesced network fetch. Concurrent callers for the same URL await one shared fetch [Deferred] and all
 * receive its resolved result rather than a stranded `loading` placeholder; the shared task runs on the
 * injected app-scope [scope], so it is independent of any caller's cancellation and still warms the cache
 * if the requesting bubble scrolls away. A [Semaphore] caps concurrent fetches at [maxConcurrentFetches].
 * Actor isolation is reproduced with [mutex]-guarded mutable state.
 */
class LinkPreviewCache(
    private val fetcher: LinkMetadataFetcher,
    private val store: LinkPreviewStore,
    private val preferences: LinkPreviewPreferences,
    private val scope: CoroutineScope,
    private val now: () -> Double = { 0.0 },
    maxConcurrentFetches: Int = DEFAULT_MAX_CONCURRENT_FETCHES,
) {
    private val mutex = Mutex()
    private val memory = CostBoundedLruCache(MAX_ENTRY_COUNT, MAX_TOTAL_COST_BYTES)
    private val negative = mutableSetOf<String>()
    private val inFlight = mutableMapOf<String, Deferred<LinkPreviewResult>>()
    private val fetchSemaphore = Semaphore(maxConcurrentFetches)
    private val inlineImages = InlineImageCache(
        fetchBody = { fetcher.fetchInlineImage(it) },
        scope = scope,
        maxConcurrentFetches = maxConcurrentFetches,
    )

    /**
     * Resolves a preview, honouring the negative cache, both cache tiers, and the auto-resolve preference
     * gate (which parks a scope-disabled URL as [LinkPreviewResult.Disabled] with no network call).
     */
    suspend fun preview(url: String, isChannelMessage: Boolean): LinkPreviewResult {
        suspend fun negativeOrCached(): LinkPreviewResult? {
            if (mutex.withLock { url in negative }) return LinkPreviewResult.NoPreviewAvailable
            val cached = checkCaches(url) ?: return null
            return LinkPreviewResult.Loaded(cached)
        }
        val early = negativeOrCached()
            ?: if (!preferences.shouldAutoResolve(isChannelMessage)) LinkPreviewResult.Disabled else null
        return early ?: fetchCoalesced(url)
    }

    /** Tap-to-load: skips the negative cache, clears any negative entry, and fetches regardless of the gate. */
    suspend fun manualFetch(url: String): LinkPreviewResult {
        checkCaches(url)?.let { return LinkPreviewResult.Loaded(it) }
        mutex.withLock { negative.remove(url) }
        return fetchCoalesced(url)
    }

    internal val inlineMedia = InlineMedia()

    /**
     * Direct-image and HTML gates for a URL the view fetches itself. The fetcher owns the host
     * resolver; the inline cache owns decoded bytes and the process-lifetime HTML verdict.
     */
    internal inner class InlineMedia {
        /**
         * Whether [url] clears the SSRF gate before the view fetches it directly (inline images). Delegates to
         * the fetcher, which owns the host resolver; no caching, since a host's addresses can change.
         */
        suspend fun isDirectFetchAllowed(url: String): Boolean = fetcher.isDirectFetchAllowed(url)

        /** Whether [url] is an HTML landing page rather than image bytes. */
        suspend fun servesHtmlPage(url: String): Boolean =
            inlineImages.servesHtmlPage(url) || fetcher.servesHtmlPage(url)

        /** Wait-free process-lifetime HTML verdict, for the sync item-build gate. */
        fun servesHtmlPageCached(url: String): Boolean = inlineImages.servesHtmlPage(url)

        suspend fun markServesHtmlPage(url: String) {
            inlineImages.markServesHtmlPage(url)
        }

        suspend fun fetchInlineImageData(url: String): InlineImageResult = inlineImages.fetchImageData(url)

        fun decodedInline(url: String): CachedDecodedImage? = inlineImages.decoded(url)

        suspend fun storeDecodedInline(entry: CachedDecodedImage, url: String) {
            inlineImages.storeDecoded(entry, url)
        }

        suspend fun clearInlineFailure(url: String) {
            inlineImages.clearFailure(url)
        }
    }

    /** Master preview toggle: off means a page-reroute stays text-only, matching iOS `.noPreview`. */
    fun shouldShowPreview(): Boolean = preferences.shouldShowPreview

    /** Whether a preview for a message of this conversation type may auto-fetch (the scope preference). */
    fun shouldAutoResolve(isChannelMessage: Boolean): Boolean = preferences.shouldAutoResolve(isChannelMessage)

    /** Direct-image dimension probe, used by receive-time prefetch. */
    suspend fun probeImageDimensions(url: String): ImagePixelSize? = fetcher.probeImageDimensions(url)

    private suspend fun checkCaches(url: String): LinkPreviewData? {
        suspend fun loadPersisted(): LinkPreviewData? {
            val persisted = runLinkPreviewStore(onFailure = { null }) { store.fetchLinkPreview(url) } ?: return null
            mutex.withLock { memory.put(url, persisted) }
            return persisted
        }
        return mutex.withLock { memory.get(url) } ?: loadPersisted()
    }

    private suspend fun fetchCoalesced(url: String): LinkPreviewResult {
        val deferred = mutex.withLock {
            inFlight[url] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    performFetch(url)
                } finally {
                    withContext(NonCancellable) { mutex.withLock { inFlight.remove(url) } }
                }
            }.also { created ->
                inFlight[url] = created
                created.start()
            }
        }
        return deferred.await()
    }

    private suspend fun performFetch(url: String): LinkPreviewResult = fetchSemaphore.withPermit {
        val metadata = fetcher.fetchMetadata(url)
        if (metadata == null) {
            mutex.withLock { negative.add(url) }
            return@withPermit LinkPreviewResult.NoPreviewAvailable
        }
        val hero = metadata.imageBytes?.let { ImageDimensionReader.read(it) }
        val data = LinkPreviewData(
            url = url,
            title = metadata.title,
            imageBytes = metadata.imageBytes,
            iconBytes = metadata.iconBytes,
            imageWidth = hero?.width,
            imageHeight = hero?.height,
            fetchedAtEpochSeconds = now(),
        )
        mutex.withLock { memory.put(url, data) }
        runLinkPreviewStore(onFailure = {}) { store.saveLinkPreview(data) }
        LinkPreviewResult.Loaded(data)
    }

    private companion object {
        const val MAX_ENTRY_COUNT = 100
        const val MAX_TOTAL_COST_BYTES = 50 * 1024 * 1024
        const val DEFAULT_MAX_CONCURRENT_FETCHES = 3
    }
}

/**
 * A small access-order LRU bounded by both entry count and total byte cost, standing in for the Swift
 * `NSCache` count/cost limits. Eviction drops the least-recently-used entries until both bounds hold.
 */
internal class CostBoundedLruCache(private val maxCount: Int, private val maxCostBytes: Int) {
    private val entries = LinkedHashMap<String, LinkPreviewData>(0, 0.75f, true)
    private var totalCost = 0

    fun get(url: String): LinkPreviewData? = entries[url]

    fun put(url: String, data: LinkPreviewData) {
        entries.remove(url)?.let { totalCost -= it.byteCost }
        entries[url] = data
        totalCost += data.byteCost
        evictWhileOverBudget()
    }

    private fun evictWhileOverBudget() {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && (entries.size > maxCount || totalCost > maxCostBytes)) {
            val eldest = iterator.next()
            totalCost -= eldest.value.byteCost
            iterator.remove()
        }
    }
}
