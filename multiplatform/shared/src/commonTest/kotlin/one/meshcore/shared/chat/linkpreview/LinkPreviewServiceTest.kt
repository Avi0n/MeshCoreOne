package one.meshcore.shared.chat.linkpreview

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.urlsafety.HostResolver
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The SAFETY-CRITICAL fetch orchestration: every page and scraped-image/icon URL is SSRF-gated before its
 * fetch. The gate runs the real [one.meshcore.shared.urlsafety.UrlSafetyChecker] against a fake resolver
 * that maps chosen hosts to private addresses.
 */
class LinkPreviewServiceTest {
    /** Resolves any host in [privateHosts] to a private address (fails the SSRF gate), else a public one. */
    private fun resolver(privateHosts: Set<String>) = HostResolver { host ->
        if (host in privateHosts) listOf("10.0.0.1") else listOf("93.184.216.34")
    }

    private class RecordingFetcher(
        private val html: String?,
        private val imageBytes: ByteArray? = byteArrayOf(1, 2, 3),
    ) : LinkContentFetcher {
        val htmlFetches = mutableListOf<String>()
        val imageFetches = mutableListOf<String>()

        override suspend fun fetchHtml(url: String): String? {
            htmlFetches += url
            return html
        }

        override suspend fun fetchImageBytes(url: String): ByteArray? {
            imageFetches += url
            return imageBytes
        }
    }

    private fun page(image: String? = null, title: String? = null): String = buildString {
        append("<html><head>")
        if (image != null) append("""<meta property="og:image" content="$image">""")
        if (title != null) append("""<meta property="og:title" content="$title">""")
        append("</head><body></body></html>")
    }

    @Test
    fun fetchesTitleAndImageForSafeUrls() = runTest {
        val fetcher = RecordingFetcher(page(image = "https://cdn.example/hero.png", title = "Hello"))
        val service = LinkPreviewService(resolver(emptySet()), fetcher)

        val result = service.fetchMetadata("https://example.com/page")

        assertEquals("Hello", result?.title)
        assertContentEquals(byteArrayOf(1, 2, 3), result?.imageBytes)
        assertEquals(listOf("https://cdn.example/hero.png"), fetcher.imageFetches)
    }

    @Test
    fun unsafePageUrlIsBlockedBeforeAnyFetch() = runTest {
        val fetcher = RecordingFetcher(page(title = "Hello"))
        val service = LinkPreviewService(resolver(privateHosts = setOf("example.com")), fetcher)

        assertNull(service.fetchMetadata("https://example.com/page"))
        assertTrue(fetcher.htmlFetches.isEmpty())
    }

    @Test
    fun scrapedImageOnPrivateHostIsDroppedButTitleSurvives() = runTest {
        val fetcher = RecordingFetcher(page(image = "https://intranet.local/hero.png", title = "Hello"))
        val service = LinkPreviewService(resolver(privateHosts = setOf("intranet.local")), fetcher)

        val result = service.fetchMetadata("https://example.com/page")

        assertEquals("Hello", result?.title)
        assertNull(result?.imageBytes)
        // The image URL was SSRF-rejected, so its bytes were never fetched.
        assertTrue(fetcher.imageFetches.isEmpty())
    }

    @Test
    fun nullHtmlYieldsNoPreview() = runTest {
        val service = LinkPreviewService(resolver(emptySet()), RecordingFetcher(html = null))
        assertNull(service.fetchMetadata("https://example.com/page"))
    }

    @Test
    fun pageWithoutTitleOrImageYieldsNoPreview() = runTest {
        val service = LinkPreviewService(resolver(emptySet()), RecordingFetcher(page()))
        assertNull(service.fetchMetadata("https://example.com/page"))
    }

    @Test
    fun titleOnlyPageReturnsWithoutImage() = runTest {
        val service = LinkPreviewService(resolver(emptySet()), RecordingFetcher(page(title = "Only title")))
        val result = service.fetchMetadata("https://example.com/page")
        assertEquals("Only title", result?.title)
        assertNull(result?.imageBytes)
    }
}
