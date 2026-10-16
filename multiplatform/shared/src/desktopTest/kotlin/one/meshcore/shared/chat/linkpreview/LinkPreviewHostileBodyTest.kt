package one.meshcore.shared.chat.linkpreview

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.PreviewLoadState
import one.meshcore.shared.chat.ui.decodePreviewImageBytes
import one.meshcore.shared.urlsafety.MalwareDomainFilter
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Production preview controller over the real Ktor fetcher. A wide inline image must still decode
 * for the bubble, and an HTML body past the scrape cap must not become a loaded card.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkPreviewHostileBodyTest {
    @Test
    fun wideInlineImageStillRendersInsideTheDisplayCap() = runTest {
        val png = solidPng(width = WIDE_IMAGE_WIDTH, height = WIDE_IMAGE_HEIGHT)
        val engine = testEngine { _, context ->
            contentResponse(context, body = ByteReadChannel(png), mime = "image/png", length = png.size)
        }
        engine.client().use { client ->
            val controller = controller(this, client)
            val message = ChatFixtures.message(text = "see https://example.com/wide.png")
            controller.requestPreviewFetch(message)
            advanceUntilIdle()

            assertEquals(
                PreviewLoadState.LOADED,
                controller.previewState(message.id),
                "url=${LinkPreviewUrlExtractor.extractFirst(message.text)} " +
                    "detected=${controller.cachedUrl(message.id)} requests=${engine.requests.size}",
            )
            val bytes = assertNotNull(controller.inlineImageBytes(message.id))
            val bitmap = assertNotNull(decodePreviewImageBytes(bytes))
            val longest = maxOf(bitmap.width, bitmap.height)
            assertTrue(longest > 0 && longest <= DISPLAY_CAP_PX, "decoded ${bitmap.width}x${bitmap.height}")
        }
    }

    @Test
    fun oversizedHtmlDoesNotLoadThroughTheController() = runTest {
        val engine = testEngine { _, context ->
            contentResponse(context, body = ByteReadChannel(oversizedHtml()), mime = "text/html", length = null)
        }
        engine.client().use { client ->
            val controller = controller(this, client)
            val message = ChatFixtures.message(text = "see https://example.com/story")
            controller.requestPreviewFetch(message)
            advanceUntilIdle()

            assertEquals(PreviewLoadState.NO_PREVIEW, controller.previewState(message.id))
            assertNull(controller.loadedPreview(message.id)?.title)
        }
    }

    /** Keeps Ktor's call context on this test scheduler. The default engine dispatcher is `Dispatchers.IO`. */
    private fun TestScope.testEngine(
        respond: suspend (HttpRequestData, CoroutineContext) -> HttpResponseData,
    ): LinkContentTestEngine {
        val engine = LinkContentTestEngine(respond)
        engine.config.dispatcher = UnconfinedTestDispatcher(testScheduler)
        return engine
    }

    private fun controller(host: TestScope, client: HttpClient): LinkPreviewController {
        val scope = CoroutineScope(UnconfinedTestDispatcher(host.testScheduler))
        val service = LinkPreviewService(
            LINK_CONTENT_RESOLVER,
            KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER),
        )
        val cache = LinkPreviewCache(
            fetcher = service,
            store = InMemoryLinkPreviewStore(),
            preferences = LinkPreviewPreferences(
                InMemoryLinkPreviewSettings(previewsEnabled = true, autoResolveDM = true, autoResolveChannels = true),
            ),
            scope = scope,
        )
        return LinkPreviewController(
            cache = cache,
            malwareFilter = MalwareDomainFilter { emptySet() },
            scope = scope,
            isChannelMessage = { false },
            onRebuild = {},
        )
    }

    private fun solidPng(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }

    /** HTML whose `og:title` would load if the scrape cap were ignored. */
    private fun oversizedHtml(): ByteArray {
        val head = HTML_HEAD.encodeToByteArray()
        val body = ByteArray(KtorLinkContentFetcher.HTML_BYTE_CAP + 1)
        head.copyInto(body)
        return body
    }

    private companion object {
        /** iOS `ImageURLDetector.inlineMaxPixelSize`: the bubble thumbnail cap. */
        const val DISPLAY_CAP_PX = 900
        const val WIDE_IMAGE_WIDTH = 2400
        const val WIDE_IMAGE_HEIGHT = 4
        const val HTML_HEAD = "<html><head><meta property=\"og:title\" content=\"Hostile\"></head><body>"
    }
}
