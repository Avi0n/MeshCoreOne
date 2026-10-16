package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.linkpreview.InMemoryLinkPreviewSettings
import one.meshcore.shared.chat.linkpreview.InMemoryLinkPreviewStore
import one.meshcore.shared.chat.linkpreview.LinkMetadataFetcher
import one.meshcore.shared.chat.linkpreview.LinkPreviewCache
import one.meshcore.shared.chat.linkpreview.LinkPreviewFetchResult
import one.meshcore.shared.chat.linkpreview.LinkPreviewPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InlineImagePrefetcherTest {
    private val imageUrl = "https://example.com/pic.png"
    private val pageUrl = "https://example.com/article"

    @Test
    fun imageProbeSavesDirectUrlAspect() = runTest {
        val store = InlineImageDimensionsStore()
        val prefetcher = InlineImagePrefetcher(cache(probe = ImagePixelSize(800, 400)), store)
        prefetcher.prefetch("see $imageUrl", isChannelMessage = false, allowImageProbes = true)
        assertEquals(2.0, store.aspect(imageUrl))
    }

    @Test
    fun imageProbeIsSkippedWhenAutoResolveIsOff() = runTest {
        val store = InlineImageDimensionsStore()
        val prefetcher = InlineImagePrefetcher(cache(probe = ImagePixelSize(800, 400)), store)
        prefetcher.prefetch("see $imageUrl", isChannelMessage = false, allowImageProbes = false)
        assertNull(store.aspect(imageUrl))
    }

    @Test
    fun cardPreviewSavesHeroAspectUnderPageUrl() = runTest {
        val store = InlineImageDimensionsStore()
        val png = pngHeader(640, 320)
        val prefetcher = InlineImagePrefetcher(
            cache(metadata = LinkPreviewFetchResult(pageUrl, title = "Story", imageBytes = png, iconBytes = null)),
            store,
        )
        prefetcher.prefetch("read $pageUrl", isChannelMessage = false, allowImageProbes = true)
        assertEquals(2.0, store.aspect(pageUrl))
    }

    @Test
    fun cancelledImageProbeCancelsPrefetch() = runTest {
        val cancelled = CancellationException("probe cancelled")
        val cache = LinkPreviewCache(
            fetcher = object : LinkMetadataFetcher {
                override suspend fun fetchMetadata(url: String): LinkPreviewFetchResult? = null
                override suspend fun probeImageDimensions(url: String): ImagePixelSize? = throw cancelled
            },
            store = InMemoryLinkPreviewStore(),
            preferences = LinkPreviewPreferences(InMemoryLinkPreviewSettings(previewsEnabled = true)),
            scope = backgroundScope,
        )
        val prefetcher = InlineImagePrefetcher(cache, InlineImageDimensionsStore())

        val thrown = assertFailsWith<CancellationException> {
            prefetcher.prefetch("see $imageUrl", isChannelMessage = false, allowImageProbes = true)
        }
        assertEquals(cancelled.message, thrown.message)
    }

    private fun TestScope.cache(
        probe: ImagePixelSize? = null,
        metadata: LinkPreviewFetchResult? = null,
    ): LinkPreviewCache = LinkPreviewCache(
        fetcher = object : LinkMetadataFetcher {
            override suspend fun fetchMetadata(url: String): LinkPreviewFetchResult? = metadata
            override suspend fun probeImageDimensions(url: String): ImagePixelSize? = probe
        },
        store = InMemoryLinkPreviewStore(),
        preferences = LinkPreviewPreferences(InMemoryLinkPreviewSettings(previewsEnabled = true)),
        scope = backgroundScope,
    )

    private fun pngHeader(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(24)
        bytes[0] = 0x89.toByte()
        bytes[1] = 'P'.code.toByte()
        bytes[2] = 'N'.code.toByte()
        bytes[3] = 'G'.code.toByte()
        bytes[12] = 'I'.code.toByte()
        bytes[13] = 'H'.code.toByte()
        bytes[14] = 'D'.code.toByte()
        bytes[15] = 'R'.code.toByte()
        putU32be(bytes, 16, width)
        putU32be(bytes, 20, height)
        return bytes
    }
}
