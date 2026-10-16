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
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadEagerAdmissionTest {
    @Test
    fun eagerFetchingObservesThePublishedRowAndUpdatedUnreadState() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val message = ChatFixtures.message(text = "$PAGE_URL $IMAGE_URL", direction = MessageDirection.INCOMING)
        lateinit var viewModel: ChatThreadViewModel
        val observed = mutableListOf<Pair<String, ChatThreadRenderState>>()
        val fetcher = object : LinkMetadataFetcher {
            override suspend fun fetchMetadata(url: String): LinkPreviewFetchResult {
                observed += url to viewModel.renderState.value
                return LinkPreviewFetchResult(url, "Resolved title", null, null)
            }

            override suspend fun probeImageDimensions(url: String): ImagePixelSize {
                observed += url to viewModel.renderState.value
                return ImagePixelSize(800, 400)
            }
        }
        val cache = LinkPreviewCache(
            fetcher = fetcher,
            store = InMemoryLinkPreviewStore(),
            preferences = LinkPreviewPreferences(InMemoryLinkPreviewSettings(previewsEnabled = true)),
            scope = scope,
        )
        val dimensions = InlineImageDimensionsStore()
        viewModel = makeThreadViewModel(
            envInputs = { EnvInputs(currentUserName = "Me", showIncomingSendTime = false, previewsEnabled = true) },
            linkPreviewCache = { cache },
            inlineImageDimensions = { dimensions },
            viewModelScope = scope,
        ).first
        viewModel.loadInitial()
        runCurrent()
        viewModel.setNearBottom(false)

        viewModel.handleIncoming(IncomingMessageEvent.DirectMessageReceived(message, ChatFixtures.CONTACT))
        runCurrent()

        assertEquals(2, observed.size)
        assertEquals(setOf(PAGE_URL, IMAGE_URL), observed.map { it.first }.toSet())
        observed.forEach { (_, state) ->
            assertEquals(listOf(message.id), state.items.map { it.id })
            assertFalse(state.isAtBottom)
            assertEquals(1, state.unreadWhileScrolledUp)
        }
        viewModel.cleanup()
    }

    private companion object {
        const val PAGE_URL = "https://example.com/page"
        const val IMAGE_URL = "https://example.com/photo.png"
    }
}
