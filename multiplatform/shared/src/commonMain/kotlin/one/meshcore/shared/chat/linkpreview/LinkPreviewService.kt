package one.meshcore.shared.chat.linkpreview

import one.meshcore.shared.chat.ImageDimensionReader
import one.meshcore.shared.chat.ImagePixelSize
import one.meshcore.shared.chat.InlineImageHttpResult
import one.meshcore.shared.urlsafety.HostResolver
import one.meshcore.shared.urlsafety.UrlSafetyChecker

/**
 * The bytes fetched for a link preview, mirroring the Swift `LinkPreviewMetadata` service result (distinct
 * from [LinkPreviewMetadata], the scraped URLs). [imageBytes]/[iconBytes] are the bounded, decoded,
 * size-fitted bytes the platform fetcher produced.
 */
data class LinkPreviewFetchResult(
    val url: String,
    val title: String?,
    val imageBytes: ByteArray?,
    val iconBytes: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LinkPreviewFetchResult) return false
        return url == other.url &&
            title == other.title &&
            imageBytes.contentEqualsNullable(other.imageBytes) &&
            iconBytes.contentEqualsNullable(other.iconBytes)
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (imageBytes?.contentHashCode() ?: 0)
        result = 31 * result + (iconBytes?.contentHashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this === other else contentEquals(other)

/**
 * The platform HTTP seam for link-preview content. Each method performs a bounded, mime-checked GET with
 * redirects disabled and every hop re-validated through [UrlSafetyChecker] (the OkHttp/Ktor adapter owns
 * that loop); [fetchImageBytes] additionally bounds the decode and fits the result to the size cap. Kept an
 * interface so [LinkPreviewService]'s gating is unit-testable without a real network.
 */
interface LinkContentFetcher {
    /** Bounded GET of [url]'s HTML (status 200-299, `html` mime, ≤ 512 KiB), or null on any failure. */
    suspend fun fetchHtml(url: String): String?

    /** Bounded GET + decode of [url]'s image bytes (`image/` mime, ≤ 2 MiB, fitted to 500 KiB), or null. */
    suspend fun fetchImageBytes(url: String): ByteArray?

    /** Whether [url] answers with an HTML Content-Type. Default false for test fakes. */
    suspend fun servesHtmlPage(url: String): Boolean = false

    /**
     * Bounded GET of a direct image URL without the preview fit/re-encode, matching iOS
     * `InlineImageCache.performFetch`. HTML Content-Type is [InlineImageHttpResult.HtmlPage];
     * unrecognized bytes are [InlineImageHttpResult.Failed]. Default fails closed so test fakes
     * that only implement the preview methods do not fetch.
     */
    suspend fun fetchInlineImage(url: String): InlineImageHttpResult = InlineImageHttpResult.Failed

    /** Reads the original image header; the production adapter uses a bounded range request. */
    suspend fun probeImageDimensions(url: String): ImagePixelSize? =
        fetchImageBytes(url)?.let(ImageDimensionReader::read)
}

/**
 * The link-preview fetch orchestration, the Android counterpart of the Swift `LinkPreviewService`
 * `fetchMetadata`. The WebKit `LPMetadataProvider` leg has no Android equivalent, so the HTML scrape is
 * primary. SAFETY-CRITICAL gating is ported verbatim: [UrlSafetyChecker] runs before the page fetch AND
 * again on the scraped image and icon URLs before their fetches, so a page that scrapes an `og:image`
 * pointing at a private host never has that image fetched. Returns null when neither a title nor an image
 * survives (an icon alone does not qualify), matching Swift. The malware-domain gate runs earlier, in the
 * view model, not here.
 */
class LinkPreviewService(private val resolver: HostResolver, private val fetcher: LinkContentFetcher) :
    LinkMetadataFetcher {
    override suspend fun fetchMetadata(url: String): LinkPreviewFetchResult? {
        if (!UrlSafetyChecker.isSafe(url, resolver)) return null
        val html = fetcher.fetchHtml(url) ?: return null
        val scraped = HtmlMetadataParser.parse(html, url) ?: return null

        val imageBytes = scraped.imageUrl?.let { fetchImageIfSafe(it) }
        val iconBytes = scraped.iconUrl?.let { fetchImageIfSafe(it) }

        if (scraped.title == null && imageBytes == null) return null
        return LinkPreviewFetchResult(url = url, title = scraped.title, imageBytes = imageBytes, iconBytes = iconBytes)
    }

    /**
     * The same SSRF gate for a URL the inline-image view fetches itself. This class holds the platform
     * resolver, so the gate stays in one place whether the bytes come back through [fetchMetadata] or
     * through the image loader.
     */
    override suspend fun isDirectFetchAllowed(url: String): Boolean = UrlSafetyChecker.isSafe(url, resolver)

    override suspend fun servesHtmlPage(url: String): Boolean {
        if (!UrlSafetyChecker.isSafe(url, resolver)) return false
        return fetcher.servesHtmlPage(url)
    }

    override suspend fun probeImageDimensions(url: String): ImagePixelSize? {
        if (!UrlSafetyChecker.isSafe(url, resolver)) return null
        return fetcher.probeImageDimensions(url)
    }

    override suspend fun fetchInlineImage(url: String): InlineImageHttpResult {
        if (!UrlSafetyChecker.isSafe(url, resolver)) return InlineImageHttpResult.Failed
        return fetcher.fetchInlineImage(url)
    }

    /** Re-checks a scraped image/icon URL through the SSRF gate before fetching its bytes. */
    private suspend fun fetchImageIfSafe(imageUrl: String): ByteArray? {
        if (!UrlSafetyChecker.isSafe(imageUrl, resolver)) return null
        return fetcher.fetchImageBytes(imageUrl)
    }
}
