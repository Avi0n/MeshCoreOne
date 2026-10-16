package one.meshcore.shared.chat.linkpreview

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element

/**
 * The Open Graph / Twitter Card metadata scraped from a page, mirroring the Swift
 * `LinkPreviewService.parseHTMLMetadata` result extended for Android. iOS got title and favicon from
 * WebKit's `LPMetadataProvider`, which has no Android equivalent, so this parse reads them from the HTML
 * directly ([title] also falling back to `<title>` then `og:site_name`, [iconUrl] from `<link rel=icon>`).
 */
data class LinkPreviewMetadata(val title: String?, val imageUrl: String?, val iconUrl: String?)

/**
 * Pure HTML metadata scrape over Ksoup, the Android counterpart of the Swift regex-based
 * `parseHTMLMetadata`. Runs against captured HTML with no network fetch. Rules ported verbatim:
 * `og:image` wins over `twitter:image` (first occurrence of each), relative image/icon URLs resolve
 * against the page URL, and non-http(s) image/icon URLs are dropped (blocks `file://`). Ksoup decodes HTML
 * entities natively while reading attribute values, so the Swift manual unescape table is unneeded. Returns
 * null when the page carries neither a title nor an image (an icon alone does not qualify), matching Swift.
 */
object HtmlMetadataParser {
    private const val OG_IMAGE = "og:image"
    private const val TWITTER_IMAGE = "twitter:image"
    private const val OG_TITLE = "og:title"
    private const val OG_SITE_NAME = "og:site_name"
    private const val CONTENT_ATTR = "content"
    private const val HREF_ATTR = "href"

    fun parse(html: String, baseUrl: String): LinkPreviewMetadata? {
        val document = Ksoup.parse(html = html, baseUri = baseUrl)
        return previewFrom(metaTags(document), document)
    }

    private fun metaTags(document: Document): MetaTags {
        val tags = MetaTags()
        for (meta in document.select("meta")) {
            val content = meta.attr(CONTENT_ATTR).ifEmpty { null } ?: continue
            tags.record(metaProperty(meta), content, meta)
        }
        return tags
    }

    private fun metaProperty(meta: Element): String? =
        meta.attr("property").ifEmpty { null } ?: meta.attr("name").ifEmpty { null }

    private fun previewFrom(tags: MetaTags, document: Document): LinkPreviewMetadata? {
        val imageUrl = (tags.ogImageElement ?: tags.twitterImageElement)?.let { httpUrl(it.absUrl(CONTENT_ATTR)) }
        val iconUrl = document.select("link[rel~=(?i)icon]").firstOrNull()?.let { httpUrl(it.absUrl(HREF_ATTR)) }
        val title = tags.ogTitle ?: document.title().ifEmpty { null } ?: tags.siteName
        if (title == null && imageUrl == null) return null
        return LinkPreviewMetadata(title = title, imageUrl = imageUrl, iconUrl = iconUrl)
    }

    private class MetaTags {
        var ogImageElement: Element? = null
        var twitterImageElement: Element? = null
        var ogTitle: String? = null
        var siteName: String? = null

        fun record(property: String?, content: String, meta: Element) {
            when {
                ogImageElement == null && property == OG_IMAGE -> ogImageElement = meta
                twitterImageElement == null && property == TWITTER_IMAGE -> twitterImageElement = meta
                ogTitle == null && property == OG_TITLE -> ogTitle = content
                siteName == null && property == OG_SITE_NAME -> siteName = content
            }
        }
    }

    /** The resolved absolute URL if it is http(s), else null (Ksoup returns "" when it cannot resolve). */
    private fun httpUrl(resolved: String): String? =
        resolved.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}
