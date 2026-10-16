package one.meshcore.shared.chat.linkpreview

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ImagePixelSize
import one.meshcore.shared.chat.InlineImageHttpResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkContentTransferLimitTest {
    @Test
    fun oversizedBodiesStopBeforeCompletionWithAnyLengthHeader() = runTest {
        for (probe in listOf(false, true)) {
            val cap = if (probe) 1024 * 1024 else 10 * 1024 * 1024
            for (length in listOf(null, 1024, cap * 2)) {
                val streams = mutableListOf<StreamingLinkContent>()
                val engine = LinkContentTestEngine { _, context ->
                    val stream = StreamingLinkContent(cap * 2)
                    streams.add(stream)
                    contentResponse(context, body = stream.start(context), length = length)
                }
                engine.client().use { client ->
                    val fetcher = KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER)
                    val service = LinkPreviewService(LINK_CONTENT_RESOLVER, fetcher)
                    if (probe) {
                        assertNull(service.probeImageDimensions("https://example.com/image.png"))
                    } else {
                        assertEquals(
                            InlineImageHttpResult.Failed,
                            service.fetchInlineImage("https://example.com/image.png"),
                        )
                    }
                    assertEquals(1, engine.requests.size)
                    val terminal = streams.single().termination.await()
                    assertFalse(terminal.finishedBody)
                    assertTrue(terminal.emittedBytes < cap * 2)
                }
            }
        }
    }

    @Test
    fun smallAndExactlyAtCapBodiesStillLoad() = runTest {
        for (probe in listOf(false, true)) {
            val cap = if (probe) 1024 * 1024 else 10 * 1024 * 1024
            for (byteCount in listOf(1024, cap)) {
                val streams = mutableListOf<StreamingLinkContent>()
                val engine = LinkContentTestEngine { _, context ->
                    val stream = StreamingLinkContent(byteCount)
                    streams.add(stream)
                    contentResponse(context, body = stream.start(context), length = byteCount)
                }
                engine.client().use { client ->
                    val fetcher = KtorLinkContentFetcher(client, LINK_CONTENT_RESOLVER)
                    val service = LinkPreviewService(LINK_CONTENT_RESOLVER, fetcher)
                    if (probe) {
                        assertEquals(
                            ImagePixelSize(1, 1),
                            service.probeImageDimensions("https://example.com/image.png"),
                        )
                    } else {
                        val result =
                            assertIs<InlineImageHttpResult.Image>(
                                service.fetchInlineImage("https://example.com/image.png"),
                            )
                        assertEquals(byteCount, result.bytes.size)
                    }
                    assertEquals(1, engine.requests.size)
                    val terminal = streams.single().termination.await()
                    assertTrue(terminal.finishedBody)
                    assertEquals(byteCount, terminal.emittedBytes)
                }
            }
        }
    }
}
