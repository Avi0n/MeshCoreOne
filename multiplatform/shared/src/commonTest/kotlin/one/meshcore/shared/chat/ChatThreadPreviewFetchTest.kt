package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.linkpreview.InMemoryLinkPreviewSettings
import one.meshcore.shared.chat.linkpreview.InMemoryLinkPreviewStore
import one.meshcore.shared.chat.linkpreview.LinkMetadataFetcher
import one.meshcore.shared.chat.linkpreview.LinkPreviewCache
import one.meshcore.shared.chat.linkpreview.LinkPreviewFetchResult
import one.meshcore.shared.chat.linkpreview.LinkPreviewPreferences
import one.meshcore.shared.model.MessageDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tap-to-load and tap-to-retry seams the bubble drives. Both are explicit user actions, so both fetch
 * past the scope gate and past the negative cache; without the view-model entry points the placeholder and
 * the failed-image affordance are dead controls. Mirrors the Swift `manualFetchPreview` / `retryImageFetch`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadPreviewFetchTest {
    private val url = "https://example.com/photo"

    /** Fails the first [failures] fetches, then resolves, so a retry is observably different from a re-park. */
    private class FlakyFetcher(private var failures: Int) : LinkMetadataFetcher {
        var calls = 0
            private set

        override suspend fun fetchMetadata(url: String): LinkPreviewFetchResult? {
            calls++
            if (failures > 0) {
                failures--
                return null
            }
            return LinkPreviewFetchResult(url, title = "Title", imageBytes = byteArrayOf(1), iconBytes = null)
        }
    }

    private fun cache(scope: CoroutineScope, fetcher: LinkMetadataFetcher, autoResolve: Boolean) = LinkPreviewCache(
        fetcher,
        InMemoryLinkPreviewStore(),
        LinkPreviewPreferences(
            InMemoryLinkPreviewSettings(
                previewsEnabled = true,
                autoResolveDM = autoResolve,
                autoResolveChannels = autoResolve,
            ),
        ),
        scope,
    )

    private fun row() = ChatFixtures.message(
        text = "look $url",
        direction = MessageDirection.INCOMING,
    )

    private fun previewsOn() = EnvInputs(currentUserName = "Me", showIncomingSendTime = false, previewsEnabled = true)

    @Test
    fun manualFetchLoadsAScopeDisabledPreview() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fetcher = FlakyFetcher(failures = 0)
        val previewCache = cache(scope, fetcher, autoResolve = false)
        val message = row()
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows = listOf(message)),
            envInputs = { previewsOn() },
            linkPreviewCache = { previewCache },
        )
        viewModel.loadInitial()
        runCurrent()
        viewModel.requestPreviewFetch(message.id)
        runCurrent()
        assertEquals(0, fetcher.calls)

        viewModel.manualPreviewFetch(message.id)
        runCurrent()

        assertEquals(1, fetcher.calls)
        assertEquals("Title", loadedPreviewTitle(viewModel.renderState.value.items.single()))
    }

    @Test
    fun retryRefetchesAfterAFailedResolve() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fetcher = FlakyFetcher(failures = 1)
        val previewCache = cache(scope, fetcher, autoResolve = true)
        val message = row()
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows = listOf(message)),
            envInputs = { previewsOn() },
            linkPreviewCache = { previewCache },
        )
        viewModel.loadInitial()
        runCurrent()
        viewModel.requestPreviewFetch(message.id)
        runCurrent()
        assertEquals(1, fetcher.calls)

        viewModel.retryPreviewFetch(message.id)
        runCurrent()

        assertEquals(2, fetcher.calls)
        assertEquals("Title", loadedPreviewTitle(viewModel.renderState.value.items.single()))
    }

    private fun loadedPreviewTitle(item: MessageItem): String? = item.content
        .filterIsInstance<MessageFragment.LinkPreview>()
        .firstNotNullOfOrNull { (it.state.mode as? LinkPreviewFragmentState.Mode.Loaded)?.preview?.title }
}
