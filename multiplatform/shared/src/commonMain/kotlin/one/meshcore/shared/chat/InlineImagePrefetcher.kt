package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import one.meshcore.shared.chat.linkpreview.LinkPreviewCache
import one.meshcore.shared.chat.linkpreview.LinkPreviewResult
import one.meshcore.shared.chat.linkpreview.LinkPreviewUrlExtractor

/**
 * Resolves inline-image dimensions and link-preview hero aspect in parallel after message admission.
 * Callers run this in background work so fetching never delays a message row.
 */
class InlineImagePrefetcher(
    private val cache: LinkPreviewCache,
    private val dimensionsStore: InlineImageDimensionsStore?,
) {
    /**
     * Prefetches every preview-eligible URL in [text]. Image URLs probe dimensions when
     * [allowImageProbes] is true; other URLs warm [LinkPreviewCache.preview] and save a hero aspect.
     * Ignores failed probes, but propagates cancellation.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun prefetch(text: String, isChannelMessage: Boolean, allowImageProbes: Boolean) {
        val urls = LinkPreviewUrlExtractor.extractAll(text)
        if (urls.isEmpty()) return
        coroutineScope {
            urls.map { url ->
                async {
                    try {
                        if (ImageURLClassifier.isImageURL(url)) {
                            prefetchImage(url, allowImageProbes)
                        } else {
                            prefetchCard(url, isChannelMessage)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A failed probe leaves this URL without a cached aspect.
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun prefetchImage(url: String, allowImageProbes: Boolean) {
        if (!allowImageProbes) return
        val store = dimensionsStore ?: return
        val probeUrl = ImageURLClassifier.directImageURL(url)
        if (store.aspect(probeUrl) != null) return
        val size = cache.probeImageDimensions(probeUrl) ?: return
        store.save(probeUrl, size.width, size.height)
    }

    private suspend fun prefetchCard(url: String, isChannelMessage: Boolean) {
        val result = cache.preview(url, isChannelMessage)
        val loaded = result as? LinkPreviewResult.Loaded ?: return
        val size = heroPixelSize(loaded.data.imageWidth, loaded.data.imageHeight) ?: return
        dimensionsStore?.save(url, size.width, size.height)
    }

    private fun heroPixelSize(width: Int?, height: Int?): ImagePixelSize? {
        if (width == null || height == null) return null
        return ImagePixelSize(width, height)
    }
}
