package one.meshcore.shared.chat.linkpreview

import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ImagePixelSize
import one.meshcore.shared.chat.InlineImageHttpResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkContentSafetyTest {
    @Test
    fun normalizedDowngradeSelfRedirectIsNotRequestedAgain() = runTest {
        val engine = LinkContentTestEngine { _, context ->
            contentResponse(context, status = HttpStatusCode.Found, location = "http://example.com:80/image.png")
        }
        engine.client().use { client ->
            val fetcher = KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER)
            assertNull(fetcher.fetchImageBytes("https://example.com/image.png"))
            assertEquals(listOf("https://example.com/image.png"), engine.requests.map { it.url.toString() })
        }
    }

    @Test
    fun privateRedirectAfterRelativeHopIsNeverRequested() = runTest {
        for (probe in listOf(false, true)) {
            val engine = LinkContentTestEngine { request, context ->
                val location = if (request.url.encodedPath ==
                    "/start.png"
                ) {
                    "/next.png"
                } else {
                    "https://127.0.0.1/image.png"
                }
                contentResponse(context, status = HttpStatusCode.Found, location = location)
            }
            engine.client().use { client ->
                val fetcher = KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER)
                if (probe) {
                    assertNull(fetcher.probeImageDimensions("https://example.com/start.png"))
                } else {
                    assertEquals(
                        InlineImageHttpResult.Failed,
                        fetcher.fetchInlineImage("https://example.com/start.png"),
                    )
                }
                assertEquals(
                    listOf("https://example.com/start.png", "https://example.com/next.png"),
                    engine.requests.map { it.url.toString() },
                )
            }
        }
    }

    @Test
    fun directRedirectsKeepTheirSchemeAndExplicitPort() = runTest {
        for (probe in listOf(false, true)) {
            for (destination in listOf(
                "http://cdn.example.com:80",
                "https://cdn.example.com:80",
                "https://cdn.example.com",
            )) {
                val engine = LinkContentTestEngine { request, context ->
                    if (request.url.host == "example.com") {
                        contentResponse(context, status = HttpStatusCode.Found, location = "$destination/image.png")
                    } else {
                        contentResponse(context)
                    }
                }
                engine.client().use { client ->
                    val fetcher = KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER)
                    if (probe) {
                        assertEquals(
                            ImagePixelSize(1, 1),
                            fetcher.probeImageDimensions("https://example.com/start.png"),
                        )
                    } else {
                        assertTrue(
                            fetcher.fetchInlineImage("https://example.com/start.png") is InlineImageHttpResult.Image,
                        )
                    }
                    assertEquals(2, engine.requests.size)
                    val terminal = engine.requests.last().url
                    assertEquals(
                        if (destination.startsWith("http:")) URLProtocol.HTTP else URLProtocol.HTTPS,
                        terminal.protocol,
                    )
                    assertEquals(if (destination.endsWith(":80")) 80 else 443, terminal.port)
                }
            }
        }
    }

    @Test
    fun htmlCheckRetainsHttpAndInlineRerouteDoesNotConsumeOversizedBody() = runTest {
        val streams = mutableListOf<StreamingLinkContent>()
        val engine = LinkContentTestEngine { _, context ->
            val stream = StreamingLinkContent(20 * 1024 * 1024)
            streams.add(stream)
            contentResponse(
                context,
                body = stream.start(context),
                mime = "text/html; charset=utf-8",
                length =
                20 * 1024 * 1024,
            )
        }
        engine.client().use { client ->
            val fetcher = KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER)
            assertTrue(fetcher.servesHtmlPage("http://example.com/page.png"))
            assertEquals(InlineImageHttpResult.HtmlPage, fetcher.fetchInlineImage("http://example.com/page.png"))
            assertEquals(
                listOf("http://example.com/page.png", "http://example.com/page.png"),
                engine.requests.map {
                    it.url.toString()
                },
            )
            for (stream in streams) {
                val terminal = stream.termination.await()
                assertFalse(terminal.finishedBody)
                assertTrue(terminal.emittedBytes < 20 * 1024 * 1024)
            }
        }
    }

    @Test
    fun probeAcceptsOctetStreamButRejectsHtmlAndOtherSuccessStatus() = runTest {
        for ((mime, status) in listOf(
            "application/octet-stream" to HttpStatusCode.OK,
            "text/html" to HttpStatusCode.OK,
            "image/png" to HttpStatusCode.Created,
        )) {
            val engine = LinkContentTestEngine { _, context -> contentResponse(context, mime = mime, status = status) }
            engine.client().use { client ->
                val fetcher = KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER)
                val expected = if (mime == "application/octet-stream") ImagePixelSize(1, 1) else null
                assertEquals(expected, fetcher.probeImageDimensions("https://example.com/image.png"))
                assertEquals(1, engine.requests.size)
            }
        }
    }

    @Test
    fun networkCancellationPropagates() = runTest {
        val engine = LinkContentTestEngine { _, _ -> throw CancellationException("cancel fetch") }
        engine.client().use { client ->
            val fetcher = KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER)
            assertFailsWith<CancellationException> { fetcher.fetchInlineImage("https://example.com/image.png") }
        }
    }
}
