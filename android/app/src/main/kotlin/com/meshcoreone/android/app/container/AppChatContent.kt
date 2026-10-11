// AndroidOnly: WP-407 Compose chat content adapters reuse the bounded, SSRF-guarded process content services.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import com.meshcoreone.android.app.content.*
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.LinkPreviewDataDTO
import com.meshcoreone.android.core.services.content.*
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.PreferenceStore
import com.meshcoreone.android.core.datastore.PreferenceSnapshot
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.feature.chats.composer.InlineImageSource
import com.meshcoreone.android.feature.chats.composer.LinkPreviewPort
import com.meshcoreone.android.feature.chats.composer.LinkPreviewOutcome
import com.meshcoreone.android.feature.chats.composer.LinkPreviewData
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal sealed interface ChatImage {
    data class Decoded(val handle: DecodedImageHandle) : ChatImage
    data object HtmlPage : ChatImage
}

class AppChatContent internal constructor(
    context: Context,
    private val store: PersistenceStoreProtocol,
    http: BoundedHttpFetching,
    preferences: LinkPreviewPreferencesSource,
    private val preferenceStore: PreferenceStore,
    initialPreferences: PreferenceSnapshot,
    scope: CoroutineScope,
) : AutoCloseable {
    val rendering = preferenceStore.snapshots.catch { failure ->
        java.util.logging.Logger.getLogger("com.mc1.ChatContent").log(java.util.logging.Level.WARNING, "Chat rendering preferences unavailable", failure)
        throw failure
    }.stateIn(scope, SharingStarted.Eagerly, initialPreferences)
    private val previewCache = LinkPreviewCache(NativeLinkMetadataFetching(http), LinkPreviewPreferences(preferences), scope)
    private val imageCache = InlineImageCache(http)
    private val dimensions = inlineImageDimensionsStore(context).also(imageCache::attachDimensionsStore)
    private val decoder = InlineImageDecoder()
    private val thumbnails = BitmapImageDecoder()
    private val emojiCatalog = AppEmojiCatalog(context.assets)
    internal val emojiPreferences = AppEmojiPreferences(preferenceStore, scope)
    internal fun emojiPicker(screenScope: CoroutineScope) =
        com.meshcoreone.android.feature.chats.composer.EmojiPickerStateHolder(
            com.meshcoreone.android.feature.chats.composer.EmojiCatalogSource {
                emojiPreferences.refresh()
                emojiCatalog.load()
            }, emojiPreferences, screenScope,
        )
    val malware = AndroidMalwareDomainFilter(context, com.meshcoreone.android.core.datastore.MeshCoreStorage.get(context).preferences, http, scope = scope)

    private val previews = object : LinkPreviewPort {
        override suspend fun cachedPreview(url: String) = previewCache.cachedPreview(url)?.forUi()
        override suspend fun preview(url: String, isChannelMessage: Boolean) = previewCache.preview(url, store, isChannelMessage).forUi()
        override suspend fun manualFetch(url: String) = previewCache.manualFetch(url, store).forUi()
    }
    internal fun previews(isChannelMessage: Boolean): LinkPreviewPort = PolicyPreviewPort(
        previews,
        enabled = { rendering.value[AppStorageKey.linkPreviewsEnabled] },
        autoResolve = { rendering.value[if (isChannelMessage) AppStorageKey.linkPreviewsAutoResolveChannels else AppStorageKey.linkPreviewsAutoResolveDM] },
    )
    val images = InlineImageSource { reference ->
        imageCache.decoded(reference)?.handle?.let { handle ->
            if (handle is BitmapDecodedImageHandle) return@InlineImageSource handle.bitmap.asImageBitmap()
        }
        val data = when {
            reference.startsWith(HERO) -> previewCache.cachedPreview(reference.removePrefix(HERO))?.imageData?.toByteArray()
            reference.startsWith(ICON) -> previewCache.cachedPreview(reference.removePrefix(ICON))?.iconData?.toByteArray()
            else -> throw IllegalArgumentException("Unknown preview image reference")
        } ?: return@InlineImageSource null
        when (val result = thumbnails.decode(data, 900)) {
            is ImageDecodeOutcome.Decoded -> {
                val handle = result.handle
                check(handle is BitmapDecodedImageHandle) { "Unexpected thumbnail decoder handle" }
                imageCache.storeDecoded(CachedDecodedImage(handle, false, null), reference)
                handle.bitmap.asImageBitmap()
            }
            is ImageDecodeOutcome.Failed -> throw IllegalStateException(result.reason)
            is ImageDecodeOutcome.Unsupported -> throw IllegalStateException(result.reason)
            ImageDecodeOutcome.Cancelled -> throw CancellationException("Image decode cancelled")
        }
    }
    fun isImage(url: String) = ImageUrlClassifier.isImageUrl(URI(url))
    fun aspect(url: String) = dimensions.aspect(url)
    internal fun shareData(url: String): ByteArray = requireNotNull(
        imageCache.decoded(ImageUrlClassifier.directImageUrl(URI(url)).toString())?.data,
    ) { "The displayed image is no longer cached; reload it before sharing" }
    suspend fun recordReaction(emoji: String) {
        preferenceStore.update {
            this[AppStorageKey.recentReactionEmojis] =
                com.meshcoreone.android.feature.chats.composer.RecentEmojis.record(
                    snapshot[AppStorageKey.recentReactionEmojis].ifEmpty {
                        com.meshcoreone.android.feature.chats.composer.RecentEmojis.DEFAULTS
                    }, emoji,
                ).snapshot()
        }
    }
    internal suspend fun inline(url: String): ChatImage {
        check(rendering.value[AppStorageKey.linkPreviewsEnabled]) { "Link content is disabled" }
        val resolved = ImageUrlClassifier.directImageUrl(URI(url)).toString()
        imageCache.decoded(resolved)?.let { return ChatImage.Decoded(it.handle) }
        val fetch = withTimeoutOrNull(30_000) {
            var result = imageCache.fetchImageData(resolved)
            while (result == InlineImageResult.Loading) {
                delay(100)
                result = imageCache.fetchImageData(resolved)
            }
            result
        } ?: throw IllegalStateException("Inline image fetch timed out")
        return when (fetch) {
            is InlineImageResult.Loaded -> when (val decoded = decoder.decode(fetch.data, 900)) {
                is ImageDecodeOutcome.Decoded -> {
                    imageCache.storeDecoded(CachedDecodedImage(decoded.handle, decoded.handle is GifDecodedImageHandle, fetch.data), resolved)
                    ChatImage.Decoded(decoded.handle)
                }
                is ImageDecodeOutcome.Failed -> throw IllegalStateException(decoded.reason)
                is ImageDecodeOutcome.Unsupported -> throw IllegalStateException(decoded.reason)
                ImageDecodeOutcome.Cancelled -> throw CancellationException("Image decode cancelled")
            }
            InlineImageResult.NotImage -> ChatImage.HtmlPage
            InlineImageResult.Failed -> throw IllegalStateException("Image fetch failed")
            InlineImageResult.Loading -> error("Image fetch is still in flight")
        }
    }
    internal suspend fun retryInline(url: String) {
        imageCache.clearFailure(ImageUrlClassifier.directImageUrl(URI(url)).toString())
    }
    private fun LinkPreviewDataDTO.forUi() = LinkPreviewData(url, title,
        imageData?.let { HERO + url }, iconData?.let { ICON + url },
        imageWidth?.let(Math::toIntExact), imageHeight?.let(Math::toIntExact))
    private fun LinkPreviewResult.forUi(): LinkPreviewOutcome = when (this) {
        is LinkPreviewResult.Loaded -> LinkPreviewOutcome.Loaded(data.forUi())
        LinkPreviewResult.Loading -> LinkPreviewOutcome.Loading
        LinkPreviewResult.NoPreviewAvailable -> LinkPreviewOutcome.NoPreviewAvailable
        LinkPreviewResult.Disabled -> LinkPreviewOutcome.Disabled
        LinkPreviewResult.Failed -> LinkPreviewOutcome.Failed
    }
    fun trimMemory() { imageCache.clearDecodedMirror(); imageCache.clearRawMemory() }
    override fun close() { previewCache.close(); malware.close(); trimMemory() }
    companion object {
        private const val HERO = "mc1-preview-hero:"
        private const val ICON = "mc1-preview-icon:"
    }

    internal class PolicyPreviewPort(
        private val delegate: LinkPreviewPort,
        private val enabled: () -> Boolean,
        private val autoResolve: () -> Boolean,
    ) : LinkPreviewPort {
        override suspend fun cachedPreview(url: String) =
            if (enabled() && autoResolve()) delegate.cachedPreview(url) else null
        override suspend fun preview(url: String, isChannelMessage: Boolean): LinkPreviewOutcome = when {
            !enabled() -> LinkPreviewOutcome.NoPreviewAvailable
            !autoResolve() -> LinkPreviewOutcome.Disabled
            else -> delegate.preview(url, isChannelMessage)
        }
        override suspend fun manualFetch(url: String) =
            if (enabled()) delegate.manualFetch(url) else LinkPreviewOutcome.NoPreviewAvailable
    }
}
