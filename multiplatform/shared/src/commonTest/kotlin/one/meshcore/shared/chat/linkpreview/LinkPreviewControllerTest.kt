package one.meshcore.shared.chat.linkpreview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.InlineImageHttpResult
import one.meshcore.shared.chat.PreviewLoadState
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.urlsafety.MalwareDomainFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The fetch-trigger view-model layer: the malware gate runs before any cache/network work, the cache's
 * preference gate parks scope-off URLs, tap-to-load bypasses the gate, and rehydration never overwrites a
 * standing malware warning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkPreviewControllerTest {
    private class RecordingFetcher(
        private val result: LinkPreviewFetchResult?,
        private val htmlPage: Boolean = false,
        private val inlineResult: InlineImageHttpResult = InlineImageHttpResult.Failed,
    ) : LinkMetadataFetcher {
        var calls = 0
            private set
        var htmlProbes = 0
            private set
        var inlineCalls = 0
            private set

        override suspend fun fetchMetadata(url: String): LinkPreviewFetchResult? {
            calls++
            return result
        }

        override suspend fun isDirectFetchAllowed(url: String): Boolean = true

        override suspend fun servesHtmlPage(url: String): Boolean {
            htmlProbes++
            return htmlPage
        }

        override suspend fun fetchInlineImage(url: String): InlineImageHttpResult {
            inlineCalls++
            return inlineResult
        }
    }

    private fun fetchResult(url: String) =
        LinkPreviewFetchResult(url, title = "Title", imageBytes = byteArrayOf(1, 2, 3), iconBytes = null)

    private fun settings(previewsEnabled: Boolean = true, autoResolve: Boolean = true) = LinkPreviewPreferences(
        InMemoryLinkPreviewSettings(
            previewsEnabled = previewsEnabled,
            autoResolveDM = autoResolve,
            autoResolveChannels = autoResolve,
        ),
    )

    private class Harness(
        val controller: LinkPreviewController,
        val fetcher: RecordingFetcher,
        val rebuilds: MutableList<Uuid>,
    )

    private data class HarnessOptions(
        val autoResolve: Boolean = true,
        val blocked: Set<String> = emptySet(),
        val htmlPage: Boolean = false,
        val inlineResult: InlineImageHttpResult = InlineImageHttpResult.Failed,
        val previewsEnabled: Boolean = true,
    )

    private fun harness(
        scope: CoroutineScope,
        result: LinkPreviewFetchResult?,
        options: HarnessOptions = HarnessOptions(),
    ): Harness {
        val fetcher = RecordingFetcher(
            result,
            htmlPage = options.htmlPage,
            inlineResult = options.inlineResult,
        )
        val cache = LinkPreviewCache(
            fetcher,
            InMemoryLinkPreviewStore(),
            settings(previewsEnabled = options.previewsEnabled, autoResolve = options.autoResolve),
            scope,
        )
        val rebuilds = mutableListOf<Uuid>()
        val controller = LinkPreviewController(
            cache = cache,
            malwareFilter = MalwareDomainFilter { options.blocked },
            scope = scope,
            isChannelMessage = { false },
            onRebuild = { rebuilds.add(it) },
        )
        return Harness(controller, fetcher, rebuilds)
    }

    @Test
    fun malwareGateShortCircuitsBeforeAnyFetch() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val harness = harness(scope, fetchResult("https://evil.com/x"), HarnessOptions(blocked = setOf("evil.com")))
        val message = ChatFixtures.message(text = "check https://evil.com/x")

        harness.controller.requestPreviewFetch(message)

        assertEquals(PreviewLoadState.MALWARE_WARNING, harness.controller.previewState(message.id))
        assertEquals(0, harness.fetcher.calls)
    }

    @Test
    fun resolvesToLoadedWithPayloadAndBytes() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val harness = harness(scope, fetchResult("https://ok.com/p"))
        val message = ChatFixtures.message(text = "see https://ok.com/p")

        harness.controller.requestPreviewFetch(message)

        assertEquals(PreviewLoadState.LOADED, harness.controller.previewState(message.id))
        assertEquals(1, harness.fetcher.calls)
        assertEquals("Title", harness.controller.loadedPreview(message.id)?.title)
        assertNotNull(harness.controller.previewImageBytes(message.id))
    }

    @Test
    fun scopeOffParksAsDisabledWithoutFetching() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val harness = harness(scope, fetchResult("https://ok.com/p"), HarnessOptions(autoResolve = false))
        val message = ChatFixtures.message(text = "see https://ok.com/p")

        harness.controller.requestPreviewFetch(message)

        assertEquals(PreviewLoadState.DISABLED, harness.controller.previewState(message.id))
        assertEquals(0, harness.fetcher.calls)
    }

    @Test
    fun manualFetchBypassesScopeGate() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val harness = harness(scope, fetchResult("https://ok.com/p"), HarnessOptions(autoResolve = false))
        val message = ChatFixtures.message(text = "see https://ok.com/p")

        harness.controller.requestPreviewFetch(message)
        assertEquals(PreviewLoadState.DISABLED, harness.controller.previewState(message.id))

        harness.controller.manualFetch(message)
        assertEquals(PreviewLoadState.LOADED, harness.controller.previewState(message.id))
        assertEquals(1, harness.fetcher.calls)
    }

    @Test
    fun noMetadataResolvesToNoPreview() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val harness = harness(scope, result = null)
        val message = ChatFixtures.message(text = "see https://ok.com/p")

        harness.controller.requestPreviewFetch(message)

        assertEquals(PreviewLoadState.NO_PREVIEW, harness.controller.previewState(message.id))
    }

    @Test
    fun retryRefetchesAFailedRowPastTheNegativeCache() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val harness = harness(scope, result = null)
        val message = ChatFixtures.message(text = "see https://ok.com/p")

        harness.controller.requestPreviewFetch(message)
        assertEquals(PreviewLoadState.NO_PREVIEW, harness.controller.previewState(message.id))

        harness.controller.retryFetch(message)

        assertEquals(2, harness.fetcher.calls)
    }

    @Test
    fun retryNeverRefetchesAMalwareFlaggedRow() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val harness = harness(scope, fetchResult("https://evil.com/x"), HarnessOptions(blocked = setOf("evil.com")))
        val message = ChatFixtures.message(text = "check https://evil.com/x")

        harness.controller.requestPreviewFetch(message)
        harness.controller.retryFetch(message)

        assertEquals(PreviewLoadState.MALWARE_WARNING, harness.controller.previewState(message.id))
        assertEquals(0, harness.fetcher.calls)
    }

    @Test
    fun rehydrateDoesNotOverwriteMalwareWarning() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val harness = harness(scope, fetchResult("https://evil.com/x"), HarnessOptions(blocked = setOf("evil.com")))
        val message = ChatFixtures.message(text = "check https://evil.com/x")

        harness.controller.requestPreviewFetch(message)
        harness.controller.rehydrate(message.id, LinkPreviewData(url = "https://evil.com/x", title = "cached"))

        assertEquals(PreviewLoadState.MALWARE_WARNING, harness.controller.previewState(message.id))
    }

    @Test
    fun anImageUrlLoadsCachedBytes() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val url = "https://cdn.example.com/hero.jpg"
        val bytes = MIN_GIF
        val harness =
            harness(scope, fetchResult(url), HarnessOptions(inlineResult = InlineImageHttpResult.Image(bytes)))
        val message = ChatFixtures.message(text = "see $url")

        harness.controller.requestPreviewFetch(message)

        assertEquals(PreviewLoadState.LOADED, harness.controller.previewState(message.id))
        assertEquals(1, harness.fetcher.inlineCalls)
        assertEquals(0, harness.fetcher.calls)
        assertTrue(harness.controller.inlineImageBytes(message.id).contentEquals(bytes))
        assertTrue(harness.controller.inlineImageIsGIF(message.id))
        assertTrue(harness.controller.routesToInlineImage(url))
    }

    @Test
    fun aLoadedImageThatCannotDecodeReroutesToPagePreview() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val url = "https://cdn.example.com/broken.jpg"
        val harness = harness(
            scope,
            fetchResult(url),
            HarnessOptions(inlineResult = InlineImageHttpResult.Image(byteArrayOf(1, 2, 3))),
        )
        val message = ChatFixtures.message(text = "see $url")

        harness.controller.requestPreviewFetch(message)
        assertEquals(PreviewLoadState.LOADED, harness.controller.previewState(message.id))

        harness.controller.onInlineImageLoadFailed(message.id)

        assertEquals(1, harness.fetcher.calls)
        assertFalse(harness.controller.routesToInlineImage(url))
        assertEquals(PreviewLoadState.LOADED, harness.controller.previewState(message.id))
        assertNotNull(harness.controller.loadedPreview(message.id))
    }

    @Test
    fun anImageUrlThatServesHtmlReroutesToThePagePreview() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val url = "https://i.imgur.com/abc.jpg"
        val harness = harness(scope, fetchResult(url), HarnessOptions(htmlPage = true))
        val message = ChatFixtures.message(text = "see $url")

        harness.controller.requestPreviewFetch(message)

        assertEquals(1, harness.fetcher.htmlProbes)
        assertEquals(1, harness.fetcher.calls)
        assertEquals(PreviewLoadState.LOADED, harness.controller.previewState(message.id))
        assertNotNull(harness.controller.loadedPreview(message.id))
        assertFalse(harness.controller.routesToInlineImage(url))
    }

    @Test
    fun aNotImageInlineFetchReroutesToThePagePreview() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val url = "https://cdn.example.com/hero.jpg"
        val harness = harness(
            scope,
            fetchResult(url),
            HarnessOptions(inlineResult = InlineImageHttpResult.HtmlPage),
        )
        val message = ChatFixtures.message(text = "see $url")

        harness.controller.requestPreviewFetch(message)

        assertEquals(1, harness.fetcher.inlineCalls)
        assertEquals(1, harness.fetcher.calls)
        assertEquals(PreviewLoadState.LOADED, harness.controller.previewState(message.id))
        assertNotNull(harness.controller.loadedPreview(message.id))
        assertFalse(harness.controller.routesToInlineImage(url))
    }

    @Test
    fun aTwinSharingAPageUrlSkipsTheInlineImagePath() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val url = "https://cdn.example.com/shot.jpg"
        val harness = harness(
            scope,
            fetchResult(url),
            HarnessOptions(inlineResult = InlineImageHttpResult.HtmlPage),
        )
        val first = ChatFixtures.message(text = "see $url")
        val twin = ChatFixtures.message(text = "also $url")

        harness.controller.requestPreviewFetch(first)
        harness.controller.requestPreviewFetch(twin)

        assertEquals(1, harness.fetcher.inlineCalls)
        assertEquals(1, harness.fetcher.calls)
        assertEquals(PreviewLoadState.LOADED, harness.controller.previewState(twin.id))
        assertNotNull(harness.controller.loadedPreview(twin.id))
        assertFalse(harness.controller.routesToInlineImage(url))
    }

    @Test
    fun aPageUrlWithPreviewsOffSettlesTextOnly() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val url = "https://cdn.example.com/hero.jpg"
        val harness = harness(
            scope,
            fetchResult(url),
            HarnessOptions(
                inlineResult = InlineImageHttpResult.HtmlPage,
                previewsEnabled = false,
            ),
        )
        val message = ChatFixtures.message(text = "see $url")

        harness.controller.requestPreviewFetch(message)
        assertEquals(PreviewLoadState.DISABLED, harness.controller.previewState(message.id))

        harness.controller.retryFetch(message)

        assertEquals(PreviewLoadState.NO_PREVIEW, harness.controller.previewState(message.id))
        assertEquals(1, harness.fetcher.inlineCalls)
        assertEquals(0, harness.fetcher.calls)
        assertFalse(harness.controller.routesToInlineImage(url))
    }

    @Test
    fun anOrphanedLoadingStateIsResetSoASettledCacheCanPaint() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val gate = kotlinx.coroutines.CompletableDeferred<LinkPreviewFetchResult?>()
        val fetcher = object : LinkMetadataFetcher {
            override suspend fun fetchMetadata(url: String): LinkPreviewFetchResult? = gate.await()
        }
        val cache = LinkPreviewCache(
            fetcher,
            InMemoryLinkPreviewStore(),
            settings(),
            scope,
        )
        val rebuilds = mutableListOf<Uuid>()
        val controller = LinkPreviewController(
            cache = cache,
            malwareFilter = MalwareDomainFilter { emptySet() },
            scope = scope,
            isChannelMessage = { false },
            onRebuild = { rebuilds.add(it) },
        )
        val message = ChatFixtures.message(text = "see https://ok.com/p")

        controller.requestPreviewFetch(message)
        assertEquals(PreviewLoadState.LOADING, controller.previewState(message.id))
        controller.cancel(message.id)
        gate.complete(fetchResult("https://ok.com/p"))
        testScheduler.runCurrent()

        controller.requestPreviewFetch(message)
        testScheduler.runCurrent()

        assertEquals(PreviewLoadState.LOADED, controller.previewState(message.id))
        assertEquals("Title", controller.loadedPreview(message.id)?.title)
    }

    @Test
    fun aFailedInlineFetchBecomesTapToRetry() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val url = "https://cdn.example.com/missing.jpg"
        val harness = harness(scope, result = null)
        val message = ChatFixtures.message(text = "see $url")

        harness.controller.requestPreviewFetch(message)

        assertEquals(PreviewLoadState.NO_PREVIEW, harness.controller.previewState(message.id))
        assertEquals(1, harness.fetcher.inlineCalls)
        assertEquals(0, harness.fetcher.calls)
    }

    private companion object {
        /** GIF89a 1×1 logical screen; enough for [one.meshcore.shared.chat.ImageDimensionReader.isGifData]. */
        val MIN_GIF = byteArrayOf(
            'G'.code.toByte(),
            'I'.code.toByte(),
            'F'.code.toByte(),
            '8'.code.toByte(),
            '9'.code.toByte(),
            'a'.code.toByte(),
            1,
            0,
            1,
            0,
        )
    }
}
