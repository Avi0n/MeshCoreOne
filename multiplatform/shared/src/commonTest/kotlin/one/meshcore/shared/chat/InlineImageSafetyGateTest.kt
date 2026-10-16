package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.linkpreview.InMemoryLinkPreviewSettings
import one.meshcore.shared.chat.linkpreview.InMemoryLinkPreviewStore
import one.meshcore.shared.chat.linkpreview.LinkContentFetcher
import one.meshcore.shared.chat.linkpreview.LinkPreviewCache
import one.meshcore.shared.chat.linkpreview.LinkPreviewPreferences
import one.meshcore.shared.chat.linkpreview.LinkPreviewService
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.urlsafety.HostResolver
import one.meshcore.shared.urlsafety.MalwareDomainFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The inline-image safety gate. An image URL arrives inside a message any mesh peer can send, so it reaches
 * a state the image loader fetches only after the malware blocklist AND the SSRF check have cleared it —
 * and while those suspending checks are still resolving the row renders [InlineImage.LoadState.Pending],
 * which carries no URL at all, so no renderer can issue a request from it. Mirrors the iOS gate
 * (`ChatViewModel.fetchInlineImage`'s blocklist check plus `InlineImageCache.performFetch`'s
 * `URLSafetyChecker`), where the bytes likewise never leave the gated cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InlineImageSafetyGateTest {
    private companion object {
        val PUBLIC_ADDRESS = listOf("93.184.216.34")
        val PRIVATE_ADDRESS = listOf("192.168.1.10")
        const val IMAGE_URL = "https://cdn.example/pic.png"
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

    /** Counts every page/image byte fetch the preview service would make; the image path must make none. */
    private class RecordingContentFetcher : LinkContentFetcher {
        var htmlCalls = 0
            private set
        var imageCalls = 0
            private set

        override suspend fun fetchHtml(url: String): String? {
            htmlCalls += 1
            return null
        }

        override suspend fun fetchImageBytes(url: String): ByteArray? {
            imageCalls += 1
            return null
        }

        override suspend fun fetchInlineImage(url: String): one.meshcore.shared.chat.InlineImageHttpResult {
            inlineCalls += 1
            return inlineResult
        }

        var inlineCalls = 0
            private set
        var inlineResult: one.meshcore.shared.chat.InlineImageHttpResult =
            one.meshcore.shared.chat.InlineImageHttpResult.Failed
    }

    /** A DNS seam that parks until [release], so a test can observe the frame rendered mid-check. */
    private class GatedResolver(private val addresses: List<String>) : HostResolver {
        private val gate = CompletableDeferred<Unit>()
        var calls = 0
            private set

        fun release() = gate.complete(Unit)

        override suspend fun resolve(host: String): List<String> {
            calls += 1
            gate.await()
            return addresses
        }
    }

    private class Harness(
        val viewModel: ChatThreadViewModel,
        val content: RecordingContentFetcher,
        val resolver: GatedResolver,
        val releaseBlocklist: () -> Unit,
    ) {
        val inlineState: InlineImage.LoadState?
            get() = viewModel.renderState.value.items.single().content
                .filterIsInstance<MessageFragment.InlineImage>()
                .firstOrNull()
                ?.image
                ?.state

        val fragments: List<MessageFragment>
            get() = viewModel.renderState.value.items.single().content

        /** Mirrors the bubble's `.task(id: previewFetchTaskID)` once the row is composed. */
        fun admitVisibleRow() {
            viewModel.requestPreviewFetch(viewModel.renderState.value.items.single().id)
        }
    }

    private fun TestScope.harness(blocked: Set<String>, addresses: List<String>): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val content = RecordingContentFetcher()
        val resolver = GatedResolver(addresses)
        val cache = LinkPreviewCache(
            LinkPreviewService(resolver, content),
            InMemoryLinkPreviewStore(),
            LinkPreviewPreferences(
                InMemoryLinkPreviewSettings(previewsEnabled = true, autoResolveDM = true, autoResolveChannels = true),
            ),
            scope,
        )
        val blocklistGate = CompletableDeferred<Unit>()
        val filter = MalwareDomainFilter {
            blocklistGate.await()
            blocked
        }
        val message = ChatFixtures.message(text = "look $IMAGE_URL", direction = MessageDirection.INCOMING)
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows = listOf(message)),
            envInputs = { EnvInputs(currentUserName = "Me", showIncomingSendTime = false, previewsEnabled = true) },
            linkPreviewCache = { cache },
            malwareFilter = { filter },
        )
        return Harness(viewModel, content, resolver) { blocklistGate.complete(Unit) }
    }

    @Test
    fun blocklistCheckPendingRendersNothingFetchable() = runTest {
        val harness = harness(blocked = setOf("cdn.example"), addresses = PUBLIC_ADDRESS)

        harness.viewModel.loadInitial()
        runCurrent()
        harness.admitVisibleRow()
        runCurrent()

        assertEquals(
            InlineImage.LoadState.Pending,
            harness.inlineState,
            "the row must carry no fetchable URL while the blocklist check is still resolving",
        )

        harness.releaseBlocklist()
        runCurrent()

        assertTrue(
            harness.fragments.none { it is MessageFragment.InlineImage },
            "a blocked host resolves to the warning card, never to an image",
        )
        assertTrue(harness.fragments.any { it is MessageFragment.MalwareWarning })
        assertEquals(0, harness.resolver.calls, "the blocklist short-circuits before any network work")
        assertEquals(0, harness.content.htmlCalls)
    }

    @Test
    fun privateAddressNeverReachesAFetchingState() = runTest {
        val harness = harness(blocked = emptySet(), addresses = PRIVATE_ADDRESS)

        harness.viewModel.loadInitial()
        runCurrent()
        harness.admitVisibleRow()
        runCurrent()
        harness.releaseBlocklist()
        runCurrent()

        assertEquals(
            InlineImage.LoadState.Pending,
            harness.inlineState,
            "the row must carry no fetchable URL while the SSRF check is still resolving",
        )

        harness.resolver.release()
        runCurrent()

        assertEquals(InlineImage.LoadState.Failed(IMAGE_URL), harness.inlineState)
        assertEquals(0, harness.content.htmlCalls, "an image URL never falls back to the page fetcher")
        assertEquals(0, harness.content.imageCalls)
        assertEquals(0, harness.content.inlineCalls)
    }

    @Test
    fun publicHostReachesTheFetchingStateOnlyAfterBothChecks() = runTest {
        val harness = harness(blocked = emptySet(), addresses = PUBLIC_ADDRESS)
        harness.content.inlineResult = InlineImageHttpResult.Image(MIN_GIF)

        harness.viewModel.loadInitial()
        runCurrent()
        harness.admitVisibleRow()
        runCurrent()
        harness.releaseBlocklist()
        runCurrent()
        assertEquals(InlineImage.LoadState.Pending, harness.inlineState)

        harness.resolver.release()
        runCurrent()

        assertTrue(harness.inlineState is InlineImage.LoadState.Loaded)
        assertEquals(0, harness.content.htmlCalls)
        assertEquals(1, harness.content.inlineCalls)
        assertEquals(0, harness.content.imageCalls)
    }
}
