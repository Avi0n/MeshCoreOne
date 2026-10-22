// PortedFrom: MC1Tests/Services/InlineImagePrefetcherTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-501 Supplementary fan-out/join/cancellation and independent frozen Giphy cache-key witnesses.
package com.meshcoreone.android.parity

import com.meshcoreone.android.core.contracts.domain.LinkPreviewPersisting
import com.meshcoreone.android.core.model.LinkPreviewDataDTO
import com.meshcoreone.android.core.services.content.InlineImageDimensionProbing
import com.meshcoreone.android.core.services.content.InlineImageDimensionsStore
import com.meshcoreone.android.core.services.content.InlineImagePrefetcher
import com.meshcoreone.android.core.services.content.LinkPreviewCaching
import com.meshcoreone.android.core.services.content.LinkPreviewResult
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class InlineImagePrefetcherOriginalPredicateTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun originalMultipleUrlsReallyFanOutAndJoinBothProductionBranches() = runTest {
        for (imageFirst in listOf(true, false)) {
            val dimensions = dimensions("fanout-$imageFirst.json")
            val held = HeldPrefetch(dimensions)
            val request = async {
                held.subject.prefetch(MIXED_TEXT, isChannelMessage = false, allowImageProbes = true)
            }
            try {
                runCurrent()
                held.assertOriginalCalls()
                assertTrue(request.isActive)
                assertTrue(held.returned.isEmpty())
                assertTrue(held.exited.isEmpty())

                val first = if (imageFirst) held.releaseImage else held.releasePreview
                val second = if (imageFirst) held.releasePreview else held.releaseImage
                val firstName = if (imageFirst) "image" else "preview"
                first.complete(Unit)
                runCurrent()
                assertEquals(listOf(firstName), held.returned)
                assertEquals(listOf(firstName), held.exited)
                assertTrue(request.isActive)

                second.complete(Unit)
                request.await()
                assertTrue(request.isCompleted)
                assertFalse(request.isCancelled)
                held.assertOriginalCalls()
                assertEquals(setOf("image", "preview"), held.returned.toSet())
                assertEquals(2, held.returned.size)
                assertEquals(setOf("image", "preview"), held.exited.toSet())
                assertEquals(2, held.exited.size)
                assertNull(dimensions.aspect(IMAGE_URL))
                assertNull(dimensions.aspect(ARTICLE_URL))
            } finally {
                request.cancelAndJoin()
            }
        }
    }

    @Test fun cancelledFanoutJoinsOwnedChildrenWithoutLateDimensionsAndKeepsLiveWritesObservable() = runTest {
        val file = File(temporary.root, "cancelled.json")
        val dimensions = InlineImageDimensionsStore(file, FIXED_CLOCK)
        val held = HeldPrefetch(
            dimensions,
            LinkPreviewResult.Loaded(
                LinkPreviewDataDTO(
                    url = ARTICLE_URL, imageWidth = 200L, imageHeight = 100L, fetchedAt = FIXED_TIME,
                ),
            ),
        )
        val request = async {
            held.subject.prefetch(MIXED_TEXT, isChannelMessage = false, allowImageProbes = true)
        }
        try {
            runCurrent()
            held.assertOriginalCalls()
            assertTrue(request.isActive)

            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertTrue(request.isCompleted)
            assertFailsWith<CancellationException> { request.await() }
            assertEquals(setOf("image", "preview"), held.exited.toSet())
            assertEquals(2, held.exited.size)
            assertTrue(held.returned.isEmpty())
            assertFalse(held.releaseImage.isCompleted)
            assertFalse(held.releasePreview.isCompleted)
            assertNull(dimensions.aspect(ARTICLE_URL))
            assertFalse(file.exists())

            held.releaseImage.complete(Unit)
            held.releasePreview.complete(Unit)
            runCurrent()
            held.assertOriginalCalls()
            assertTrue(held.returned.isEmpty())
            assertEquals(2, held.exited.size)
            assertNull(dimensions.aspect(ARTICLE_URL))
            assertFalse(file.exists())

            held.subject.prefetch(MIXED_TEXT, isChannelMessage = false, allowImageProbes = false)
            assertEquals(listOf(IMAGE_URL), held.imageCalls)
            assertEquals(
                listOf(
                    PreviewCall(ARTICLE_URL, held.persistence, false),
                    PreviewCall(ARTICLE_URL, held.persistence, false),
                ),
                held.previews.calls,
            )
            held.previews.calls.forEach { assertSame(held.persistence, it.persistence) }
            assertEquals(listOf("preview"), held.returned)
            assertEquals(2.0, dimensions.aspect(ARTICLE_URL))
            assertEquals(2.0, InlineImageDimensionsStore(file, FIXED_CLOCK).aspect(ARTICLE_URL))
            assertNull(dimensions.aspect(IMAGE_URL))
        } finally {
            request.cancelAndJoin()
        }
    }

    @Test fun originalGiphyProbeUsesIndependentFrozenMediaUrlAndOnlyTheResolvedCacheEntry() = runTest {
        val file = File(temporary.root, "giphy.json")
        val dimensions = InlineImageDimensionsStore(file, FIXED_CLOCK)
        val probed = mutableListOf<String>()
        val previews = RecordingPreview()
        val subject = InlineImagePrefetcher(
            InlineImageDimensionProbing { url -> probed += url; null },
            previews,
            dimensions,
            UnexpectedPreviewStore(),
        )
        dimensions.save(GIPHY_PAGE, 200.0, 100.0)

        subject.prefetch("look at $GIPHY_PAGE", isChannelMessage = false, allowImageProbes = true)
        assertEquals(listOf(FROZEN_GIPHY_DIRECT), probed)
        assertTrue(previews.calls.isEmpty())
        assertEquals(2.0, dimensions.aspect(GIPHY_PAGE))
        assertNull(dimensions.aspect(FROZEN_GIPHY_DIRECT))

        dimensions.save(FROZEN_GIPHY_DIRECT, 200.0, 100.0)
        subject.prefetch("look at $GIPHY_PAGE", isChannelMessage = false, allowImageProbes = true)
        assertEquals(listOf(FROZEN_GIPHY_DIRECT), probed)
        assertTrue(previews.calls.isEmpty())
        val reloaded = InlineImageDimensionsStore(file, FIXED_CLOCK)
        assertEquals(2.0, reloaded.aspect(GIPHY_PAGE))
        assertEquals(2.0, reloaded.aspect(FROZEN_GIPHY_DIRECT))
    }

    private fun dimensions(name: String) = InlineImageDimensionsStore(File(temporary.root, name), FIXED_CLOCK)

    private class HeldPrefetch(
        dimensions: InlineImageDimensionsStore,
        previewResult: LinkPreviewResult = LinkPreviewResult.NoPreviewAvailable,
    ) {
        val persistence = UnexpectedPreviewStore()
        val releaseImage = CompletableDeferred<Unit>()
        val releasePreview = CompletableDeferred<Unit>()
        val imageCalls = mutableListOf<String>()
        val returned = mutableListOf<String>()
        val exited = mutableListOf<String>()
        val previews = RecordingPreview {
            try {
                releasePreview.await()
                returned += "preview"
                previewResult
            } finally {
                exited += "preview"
            }
        }
        val subject = InlineImagePrefetcher(
            InlineImageDimensionProbing { url ->
                imageCalls += url
                try {
                    releaseImage.await()
                    returned += "image"
                    null
                } finally {
                    exited += "image"
                }
            },
            previews,
            dimensions,
            persistence,
        )

        fun assertOriginalCalls() {
            assertEquals(listOf(IMAGE_URL), imageCalls)
            assertEquals(listOf(PreviewCall(ARTICLE_URL, persistence, false)), previews.calls)
            assertSame(persistence, previews.calls.single().persistence)
        }
    }

    private data class PreviewCall(val url: String, val persistence: LinkPreviewPersisting, val channel: Boolean)

    private class RecordingPreview(
        private val reply: suspend () -> LinkPreviewResult = { LinkPreviewResult.NoPreviewAvailable },
    ) : LinkPreviewCaching {
        val calls = mutableListOf<PreviewCall>()
        override suspend fun preview(url: String, dataStore: LinkPreviewPersisting, isChannelMessage: Boolean): LinkPreviewResult {
            calls += PreviewCall(url, dataStore, isChannelMessage)
            return reply()
        }
        override suspend fun manualFetch(url: String, dataStore: LinkPreviewPersisting): LinkPreviewResult =
            error("Prefetch must not invoke manualFetch")
        override suspend fun isFetching(url: String): Boolean = error("Prefetch must not invoke isFetching")
        override suspend fun cachedPreview(url: String): LinkPreviewDataDTO? = error("Prefetch must not invoke cachedPreview")
    }

    private class UnexpectedPreviewStore : LinkPreviewPersisting {
        override suspend fun fetchLinkPreview(url: String): LinkPreviewDataDTO? =
            error("The role stub must not perform a persistence read")
        override suspend fun saveLinkPreview(dto: LinkPreviewDataDTO): Unit =
            error("The role stub must not perform a persistence write")
    }

    private companion object {
        const val IMAGE_URL = "https://example.com/cat.png"
        const val ARTICLE_URL = "https://example.com/article"
        const val MIXED_TEXT = "image https://example.com/cat.png and article https://example.com/article"
        const val GIPHY_PAGE = "https://giphy.com/gifs/abc123"
        const val FROZEN_GIPHY_DIRECT = "https://i.giphy.com/media/abc123/giphy.gif"
        val FIXED_TIME: Instant = Instant.parse("2024-01-01T00:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_TIME, ZoneOffset.UTC)
    }
}
