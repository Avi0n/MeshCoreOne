package one.meshcore.shared.simulator

import one.meshcore.shared.chat.CachedDecodedImage
import one.meshcore.shared.chat.ImageDimensionReader
import one.meshcore.shared.chat.ImageURLClassifier
import one.meshcore.shared.chat.InlineImageDimensionsStore
import one.meshcore.shared.chat.linkpreview.LinkPreviewCache

/**
 * Pre-seeds the process-lifetime inline-image cache with the demo PNG, matching iOS
 * `DemoInlineImageSeeder`. The bubble paints Loaded once [LinkPreviewCache.decodedInline] holds
 * bytes for the summit URL, so reviewers see the image without a network fetch.
 */
object DemoInlineImageSeeder {
    suspend fun seed(cache: LinkPreviewCache, dimensions: InlineImageDimensionsStore? = null) {
        val url = ImageURLClassifier.directImageURL(MockDataProvider.INLINE_IMAGE_URL)
        val bytes = MockDataProvider.demoImageData
        cache.inlineMedia.storeDecodedInline(CachedDecodedImage(bytes = bytes, isGIF = false), url)
        val size = ImageDimensionReader.read(bytes) ?: return
        dimensions?.save(url, size.width, size.height)
    }
}
