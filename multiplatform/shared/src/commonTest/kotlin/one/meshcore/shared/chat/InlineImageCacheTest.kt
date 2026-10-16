package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Gated fetch, decoded rehydrate, HTML-page verdict, and FIFO eviction for [InlineImageCache]. */
@OptIn(ExperimentalCoroutinesApi::class)
class InlineImageCacheTest {
    private val gif = byteArrayOf(
        'G'.code.toByte(),
        'I'.code.toByte(),
        'F'.code.toByte(),
        '8'.code.toByte(),
        '9'.code.toByte(),
        'a'.code.toByte(),
        1,
        0,
        1,
        0,
    )

    @Test
    fun fetchReturnsLoadedBytesAndDecodedLookupSurvives() = runTest {
        val cache = InlineImageCache(
            fetchBody = { InlineImageHttpResult.Image(gif) },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        val result = cache.fetchImageData("https://cdn.example/a.gif")
        assertIs<InlineImageResult.Loaded>(result)
        assertTrue(result.data.contentEquals(gif))
        cache.storeDecoded(CachedDecodedImage(gif, isGIF = true), "https://cdn.example/a.gif")
        assertTrue(cache.decoded("https://cdn.example/a.gif")?.bytes.contentEquals(gif))
    }

    @Test
    fun htmlBodyIsNotImageAndSticksAcrossLookups() = runTest {
        val cache = InlineImageCache(
            fetchBody = { InlineImageHttpResult.HtmlPage },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        assertEquals(InlineImageResult.NotImage, cache.fetchImageData("https://i.imgur.com/x.jpg"))
        assertTrue(cache.servesHtmlPage("https://i.imgur.com/x.jpg"))
    }

    @Test
    fun failedUrlIsNegativeCachedUntilCleared() = runTest {
        var calls = 0
        val cache = InlineImageCache(
            fetchBody = {
                calls += 1
                InlineImageHttpResult.Failed
            },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        val url = "https://cdn.example/missing.png"
        assertEquals(InlineImageResult.Failed, cache.fetchImageData(url))
        assertEquals(InlineImageResult.Failed, cache.fetchImageData(url))
        assertEquals(1, calls)
        cache.clearFailure(url)
        assertEquals(InlineImageResult.Failed, cache.fetchImageData(url))
        assertEquals(2, calls)
    }

    @Test
    fun decodedFifoKeepsTheNewestWhenOverEntryCap() = runTest {
        val cache = InlineImageCache(
            fetchBody = { InlineImageHttpResult.Failed },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        repeat(InlineImageCache.MAX_DECODED_ENTRY_COUNT + 1) { index ->
            cache.storeDecoded(
                CachedDecodedImage(byteArrayOf(index.toByte()), isGIF = false),
                "https://cdn.example/$index.png",
            )
        }
        assertNull(cache.decoded("https://cdn.example/0.png"))
        assertTrue(
            cache.decoded("https://cdn.example/${InlineImageCache.MAX_DECODED_ENTRY_COUNT}.png") != null,
        )
    }
}
