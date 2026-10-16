package one.meshcore.shared.chat.linkpreview

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ImagePixelSize
import one.meshcore.shared.chat.InlineImageHttpResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LinkContentRedirectTest {
    @Test
    fun previewRequestsUpgradeHttpAndRemovePort80() = runTest {
        val engine = LinkContentTestEngine { _, context -> contentResponse(context, mime = "text/html") }
        engine.client().use { client ->
            val fetcher = KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER, PreviewImageProcessor { it })
            fetcher.fetchHtml("http://example.com:80/page?q=1")
            assertEquals(listOf("https://example.com/page?q=1"), engine.requests.map { it.url.toString() })
        }
    }

    @Test
    fun previewImageRetainsCustomPortAndUpgradesRedirects() = runTest {
        val engine = LinkContentTestEngine { request, context ->
            if (request.url.host == "example.com") {
                contentResponse(
                    context,
                    status = HttpStatusCode.Found,
                    location = "http://cdn.example.com:80/image.png",
                )
            } else {
                contentResponse(context)
            }
        }
        engine.client().use { client ->
            val fetcher = KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER, PreviewImageProcessor { it })
            assertContentEquals(LINK_CONTENT_PNG, fetcher.fetchImageBytes("http://example.com:8080/image"))
            assertEquals(
                listOf("https://example.com:8080/image", "https://cdn.example.com/image.png"),
                engine.requests.map { it.url.toString() },
            )
        }
    }

    @Test
    fun inlineUsesOneGetAndRetainsHttpTransport() = runTest {
        val engine = LinkContentTestEngine { _, context -> contentResponse(context) }
        engine.client().use { client ->
            val fetcher = KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER)
            assertContentEquals(
                LINK_CONTENT_PNG,
                assertIs<InlineImageHttpResult.Image>(
                    fetcher.fetchInlineImage("http://example.com/image.png"),
                ).bytes,
            )
            assertEquals(listOf("http://example.com/image.png"), engine.requests.map { it.url.toString() })
        }
    }

    @Test
    fun selfRedirectStopsAfterTheRespondingRequest() = runTest {
        val engine = LinkContentTestEngine { request, context ->
            contentResponse(context, status = HttpStatusCode.Found, location = request.url.toString())
        }
        engine.client().use { client ->
            val fetcher = KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER)
            assertEquals(InlineImageHttpResult.Failed, fetcher.fetchInlineImage("https://example.com/image.png"))
            assertEquals(1, engine.requests.size)
        }
    }

    @Test
    fun directPrivateUrlNeverReachesTheEngine() = runTest {
        val engine = LinkContentTestEngine { _, context -> contentResponse(context) }
        engine.client().use { client ->
            val fetcher = KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER)
            assertEquals(InlineImageHttpResult.Failed, fetcher.fetchInlineImage("https://127.0.0.1/image.png"))
            assertEquals(0, engine.requests.size)
        }
    }

    @Test
    fun probeUsesRangeWithoutPreviewReencoding() = runTest {
        val engine =
            LinkContentTestEngine { _, context -> contentResponse(context, status = HttpStatusCode.PartialContent) }
        engine.client().use { client ->
            val fetcher =
                KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER, PreviewImageProcessor { error("preview decode") })
            val service = LinkPreviewService(LINK_CONTENT_RESOLVER, fetcher)
            assertEquals(ImagePixelSize(1, 1), service.probeImageDimensions("http://example.com/image.png"))
            assertEquals(listOf("bytes=0-65535"), engine.requests.map { it.headers[HttpHeaders.Range] })
            assertEquals(listOf("http://example.com/image.png"), engine.requests.map { it.url.toString() })
        }
    }
}
