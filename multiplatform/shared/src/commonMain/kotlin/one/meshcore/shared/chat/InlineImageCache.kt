package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Outcome of an inline-image HTTP body, mirroring iOS `InlineImageCache.performFetch`. */
sealed interface InlineImageHttpResult {
    data class Image(val bytes: ByteArray) : InlineImageHttpResult
    data object HtmlPage : InlineImageHttpResult
    data object Failed : InlineImageHttpResult
}

/** Outcome of [InlineImageCache.fetchImageData], mirroring iOS `InlineImageResult`. */
sealed interface InlineImageResult {
    data class Loaded(val data: ByteArray) : InlineImageResult
    data object Loading : InlineImageResult
    data object Failed : InlineImageResult
    data object NotImage : InlineImageResult
}

/**
 * Encoded bytes plus the GIF flag, the Kotlin stand-in for iOS `CachedDecodedImage`. GIFs keep the
 * encoded payload so the bubble can animate; stills keep it for the full-screen viewer and share.
 */
data class CachedDecodedImage(val bytes: ByteArray, val isGIF: Boolean) {
    val cost: Int get() = bytes.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CachedDecodedImage) return false
        return isGIF == other.isGIF && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + isGIF.hashCode()
}

/**
 * Process-lifetime inline-image cache, matching iOS `InlineImageCache`: a byte LRU, a decoded FIFO
 * that survives chat teardown, a negative cache, coalesced in-flight fetches, and a concurrency cap.
 */
class InlineImageCache(
    private val fetchBody: suspend (String) -> InlineImageHttpResult,
    private val scope: CoroutineScope,
    maxConcurrentFetches: Int = DEFAULT_MAX_CONCURRENT_FETCHES,
) {
    private val mutex = Mutex()
    private val memory = CostBoundedByteCache(MAX_ENTRY_COUNT, MAX_TOTAL_COST_BYTES)
    private val failed = mutableSetOf<String>()
    private val htmlPages = LinkedHashMap<String, Unit>()
    private val inFlight = mutableMapOf<String, Deferred<InlineImageResult>>()
    private val fetchSemaphore = Semaphore(maxConcurrentFetches)
    private val decodedEntries = LinkedHashMap<String, CachedDecodedImage>()
    private var decodedCost = 0

    @Volatile
    private var decodedSnapshot: Map<String, CachedDecodedImage> = emptyMap()

    @Volatile
    private var htmlSnapshot: Set<String> = emptySet()

    /** Wait-free decoded lookup so a chat re-entry can paint Loaded on the first item build. */
    fun decoded(url: String): CachedDecodedImage? = decodedSnapshot[url]

    /** Process-lifetime HTML-page verdict so a `.jpg` landing page stays a card across re-entry. */
    fun servesHtmlPage(url: String): Boolean = url in htmlSnapshot

    suspend fun markServesHtmlPage(url: String) {
        mutex.withLock {
            if (htmlPages.put(url, Unit) == null && htmlPages.size > MAX_SERVES_PAGE_ENTRIES) {
                htmlPages.remove(htmlPages.keys.first())
            }
            htmlSnapshot = htmlPages.keys.toSet()
        }
    }

    suspend fun storeDecoded(entry: CachedDecodedImage, url: String) {
        mutex.withLock {
            decodedEntries.remove(url)?.let { decodedCost -= it.cost }
            decodedEntries[url] = entry
            decodedCost += entry.cost
            while (decodedEntries.size > 1 &&
                (decodedEntries.size > MAX_DECODED_ENTRY_COUNT || decodedCost > MAX_DECODED_TOTAL_COST_BYTES)
            ) {
                val oldest = decodedEntries.keys.first()
                decodedEntries.remove(oldest)?.let { decodedCost -= it.cost }
            }
            decodedSnapshot = HashMap(decodedEntries)
        }
    }

    suspend fun clearFailure(url: String) {
        mutex.withLock { failed.remove(url) }
    }

    suspend fun fetchImageData(url: String): InlineImageResult {
        val cached = cachedImage(url)
        if (cached != null) return cached
        val (deferred, isCreator) = mutex.withLock {
            val existing = inFlight[url]
            if (existing != null) {
                existing to false
            } else {
                val created = scope.async { performFetch(url) }
                inFlight[url] = created
                created to true
            }
        }
        val result = deferred.await()
        if (isCreator) mutex.withLock { inFlight.remove(url) }
        return result
    }

    private suspend fun cachedImage(url: String): InlineImageResult? = mutex.withLock {
        if (url in failed) return@withLock InlineImageResult.Failed
        val bytes = memory.get(url) ?: return@withLock null
        InlineImageResult.Loaded(bytes)
    }

    private suspend fun performFetch(url: String): InlineImageResult = fetchSemaphore.withPermit {
        mutex.withLock { memory.get(url) }?.let { return@withPermit InlineImageResult.Loaded(it) }
        when (val body = fetchBody(url)) {
            is InlineImageHttpResult.Image -> {
                mutex.withLock { memory.put(url, body.bytes) }
                InlineImageResult.Loaded(body.bytes)
            }
            InlineImageHttpResult.HtmlPage -> {
                markServesHtmlPage(url)
                InlineImageResult.NotImage
            }
            InlineImageHttpResult.Failed -> {
                mutex.withLock { failed.add(url) }
                InlineImageResult.Failed
            }
        }
    }

    companion object {
        const val MAX_ENTRY_COUNT = 50
        const val MAX_TOTAL_COST_BYTES = 50 * 1024 * 1024
        const val MAX_DECODED_ENTRY_COUNT = 50
        const val MAX_DECODED_TOTAL_COST_BYTES = 100 * 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024
        private const val MAX_SERVES_PAGE_ENTRIES = 200
        private const val DEFAULT_MAX_CONCURRENT_FETCHES = 3
    }
}

/** Access-order LRU of encoded image bodies, standing in for iOS `NSCache` count/cost limits. */
internal class CostBoundedByteCache(private val maxCount: Int, private val maxCostBytes: Int) {
    private val entries = LinkedHashMap<String, ByteArray>(0, 0.75f, true)
    private var totalCost = 0

    fun get(url: String): ByteArray? = entries[url]

    fun put(url: String, data: ByteArray) {
        entries.remove(url)?.let { totalCost -= it.size }
        entries[url] = data
        totalCost += data.size
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && (entries.size > maxCount || totalCost > maxCostBytes)) {
            val eldest = iterator.next()
            totalCost -= eldest.value.size
            iterator.remove()
        }
    }
}
