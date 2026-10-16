package one.meshcore.shared.simulator

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ImageDimensionReader
import one.meshcore.shared.chat.ImageURLClassifier
import one.meshcore.shared.chat.InlineImageDimensionsStore
import one.meshcore.shared.chat.linkpreview.InMemoryLinkPreviewSettings
import one.meshcore.shared.chat.linkpreview.InMemoryLinkPreviewStore
import one.meshcore.shared.chat.linkpreview.LinkMetadataFetcher
import one.meshcore.shared.chat.linkpreview.LinkPreviewCache
import one.meshcore.shared.chat.linkpreview.LinkPreviewFetchResult
import one.meshcore.shared.chat.linkpreview.LinkPreviewPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** iOS `DemoInlineImageSeeder` puts decoded bytes in the process cache so demo bubbles skip the shimmer. */
class DemoInlineImageSeederTest {
    @Test
    fun seedStoresDecodedBytesAndAspectForTheSummitUrl() = runTest {
        val cache = LinkPreviewCache(
            fetcher = object : LinkMetadataFetcher {
                override suspend fun fetchMetadata(url: String): LinkPreviewFetchResult? = null
            },
            store = InMemoryLinkPreviewStore(),
            preferences = LinkPreviewPreferences(InMemoryLinkPreviewSettings()),
            scope = backgroundScope,
        )
        val dimensions = InlineImageDimensionsStore()
        DemoInlineImageSeeder.seed(cache, dimensions)

        val url = ImageURLClassifier.directImageURL(MockDataProvider.INLINE_IMAGE_URL)
        val decoded = cache.inlineMedia.decodedInline(url)
        assertNotNull(decoded)
        assertTrue(decoded.bytes.contentEquals(MockDataProvider.demoImageData))
        val size = ImageDimensionReader.read(MockDataProvider.demoImageData)
        assertNotNull(size)
        assertEquals(size.width.toDouble() / size.height.toDouble(), dimensions.aspect(url))
    }
}
