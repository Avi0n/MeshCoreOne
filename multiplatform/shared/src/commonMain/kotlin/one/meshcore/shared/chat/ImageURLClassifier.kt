package one.meshcore.shared.chat

/**
 * URL-level classifier for image hosting. Pure string inspection — no decoding, no Coil dependency.
 * Port of the Swift `ImageURLClassifier` in MC1Services so the fragment builder and preview controller
 * can decide inline-image vs link-preview without I/O.
 */
object ImageURLClassifier {
    private val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "gif", "webp", "heic")
    private const val HTML_MIME = "text/html"

    /** Length of the `://` separator after the scheme. */
    private const val SCHEME_SEPARATOR_LENGTH: Int = 3

    /** True when the URL path ends with a known image extension. */
    fun isDirectImageURL(url: String): Boolean {
        val path = UrlParts.pathOf(url) ?: return false
        val ext = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    /**
     * Returns a direct media URL for known hosting page URLs (currently Giphy gif/embed pages), or null
     * when the URL is not a resolvable hosting page. Already-direct giphy media hosts return null so the
     * caller keeps the original URL.
     */
    fun resolveImageURL(url: String): String? {
        val host = UrlParts.hostOf(url)?.lowercase() ?: return null
        if (host == "giphy.com" || host == "www.giphy.com") return resolveGiphyURL(url)
        if (host == "media.giphy.com" || host == "i.giphy.com") return null
        return null
    }

    /** True when the URL is a direct image or a resolvable hosting page. */
    fun isImageURL(url: String): Boolean = isDirectImageURL(url) || resolveImageURL(url) != null

    /**
     * The fetch URL for the image: the URL itself when direct, the resolved hosting URL when known, else
     * the original (caller may still try a content-type probe later).
     */
    fun directImageURL(url: String): String {
        if (isDirectImageURL(url)) return url
        return resolveImageURL(url) ?: url
    }

    /**
     * True when an HTTP Content-Type names an HTML page. An image-extension URL that serves this
     * is a landing page, not a decode failure (iOS `InlineImageCache` `htmlMimeType` check).
     */
    fun isHtmlContentType(mime: String?): Boolean {
        val type = mime?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type == HTML_MIME || type.endsWith("/html")
    }

    /**
     * True when [directImageURL] resolves to a `.gif` path — a Giphy page always resolves to one, so this
     * checks the resolved fetch URL rather than the original. Drives GIF-specific autoplay/loop handling in
     * the inline-image fragment.
     */
    fun isGifURL(url: String): Boolean {
        val path = UrlParts.pathOf(directImageURL(url)) ?: return false
        return path.substringAfterLast('.', missingDelimiterValue = "").equals("gif", ignoreCase = true)
    }

    private fun resolveGiphyURL(url: String): String? {
        val components = UrlParts.giphyComponents(url) ?: return null
        val giphyID = UrlParts.giphyId(components.section, components.last)
        if (giphyID.isEmpty()) return null
        return "https://i.giphy.com/media/$giphyID/giphy.gif"
    }

    private object UrlParts {
        fun pathOf(url: String): String? {
            val afterScheme = afterScheme(url) ?: return null
            val pathStart = afterScheme.indexOf('/')
            if (pathStart < 0) return ""
            return afterScheme.substring(pathStart).substringBefore('?').substringBefore('#')
        }

        fun hostOf(url: String): String? {
            val afterScheme = afterScheme(url) ?: return null
            val hostPort = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            if (hostPort.isEmpty()) return null
            // Strip userinfo and port; a bracketed IPv6 host stays comparable as a host.
            val host = hostPort.substringAfterLast('@')
            return if (host.startsWith('[')) {
                host.substringBefore(']').removePrefix("[") + "]"
            } else {
                host.substringBefore(':')
            }
        }

        fun giphyComponents(url: String): GiphyPath? {
            val components = pathOf(url)
                ?.split('/')
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.size >= 2 }
                ?: return null
            val section = components[0].lowercase()
            if (section != "gifs" && section != "embed") return null
            return GiphyPath(section = section, last = components[1])
        }

        fun giphyId(section: String, last: String): String =
            if (section == "gifs") last.substringAfterLast('-').ifEmpty { last } else last

        private fun afterScheme(url: String): String? {
            val schemeSep = url.indexOf("://").takeIf { it >= 0 } ?: return null
            return url.substring(schemeSep + SCHEME_SEPARATOR_LENGTH)
        }

        class GiphyPath(val section: String, val last: String)
    }
}
