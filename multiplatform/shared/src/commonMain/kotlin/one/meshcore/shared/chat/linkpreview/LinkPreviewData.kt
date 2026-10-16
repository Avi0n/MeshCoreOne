package one.meshcore.shared.chat.linkpreview

/**
 * A cached link preview with its stored hero/icon bytes, mirroring the Swift `LinkPreviewDataDTO` (§6.7).
 * This is the cache/persistence model; the render fragment carries the lighter [LinkPreviewDataDto] (no
 * bytes — the bubble references bytes out-of-band). Previews are regenerable and NOT part of the backup
 * envelope. [fetchedAtEpochSeconds] is stored but never used to invalidate (no TTL). The [imageBytes]/
 * [iconBytes] arrays compare by identity under the generated `equals`; callers compare [url]/[title].
 */
data class LinkPreviewData(
    val url: String,
    val title: String? = null,
    val imageBytes: ByteArray? = null,
    val iconBytes: ByteArray? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val fetchedAtEpochSeconds: Double = 0.0,
) {
    /** Total stored bytes, used as the memory-cache cost. */
    val byteCost: Int get() = (imageBytes?.size ?: 0) + (iconBytes?.size ?: 0)
}

/**
 * Persistence seam for the link-preview cache's tier-2 store, mirroring the Swift
 * `PersistenceStore.fetchLinkPreview`/`saveLinkPreview`. The Room adapter uses a URL-keyed entity. Fetch
 * failures are swallowed by the cache, so a store may throw.
 */
interface LinkPreviewStore {
    /** The persisted preview for [url], or null if none. */
    suspend fun fetchLinkPreview(url: String): LinkPreviewData?

    /** Inserts or replaces the preview row keyed by its url. */
    suspend fun saveLinkPreview(data: LinkPreviewData)
}
