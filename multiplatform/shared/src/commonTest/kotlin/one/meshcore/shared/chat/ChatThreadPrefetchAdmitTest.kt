package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.linkpreview.InMemoryLinkPreviewSettings
import one.meshcore.shared.chat.linkpreview.InMemoryLinkPreviewStore
import one.meshcore.shared.chat.linkpreview.LinkMetadataFetcher
import one.meshcore.shared.chat.linkpreview.LinkPreviewCache
import one.meshcore.shared.chat.linkpreview.LinkPreviewFetchResult
import one.meshcore.shared.chat.linkpreview.LinkPreviewPreferences
import one.meshcore.shared.model.MessageDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Incoming and outgoing rows publish immediately while dimensions resolve in background work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadPrefetchAdmitTest {
    private val imageUrl = "https://example.com/pic.png"

    @Test
    fun incomingImageAppearsBeforeProbeAndGainsAspectAfterward() = runTest {
        val dimensions = InlineImageDimensionsStore()
        val cache = cache(probeDelayMs = 150, probe = ImagePixelSize(800, 400))
        val (viewModel, _) = makeThreadViewModel(
            envInputs = { previewsOn() },
            linkPreviewCache = { cache },
            inlineImageDimensions = { dimensions },
        )
        viewModel.loadInitial()
        runCurrent()

        val job =
            launch {
                viewModel.handleIncoming(IncomingMessageEvent.DirectMessageReceived(incoming(), ChatFixtures.CONTACT))
            }
        runCurrent()
        assertEquals(1, viewModel.coordinator.messages.size)
        assertEquals(1, viewModel.renderState.value.items.size)
        assertTrue(job.isCompleted)
        assertNull(dimensions.aspect(imageUrl))

        advanceTimeBy(150)
        runCurrent()
        job.join()

        assertEquals(1, viewModel.renderState.value.items.size)
        assertEquals(2.0, dimensions.aspect(imageUrl))
        val image = inlineImage(viewModel)
        assertEquals(2.0, image?.cachedAspect)
    }

    @Test
    fun incomingImageAdmitsWithoutDeadlineAndProbeCompletesInBackground() = runTest {
        val dimensions = InlineImageDimensionsStore()
        val cache = cache(probeDelayMs = 10_000, probe = ImagePixelSize(800, 400))
        val (viewModel, _) = makeThreadViewModel(
            envInputs = { previewsOn() },
            linkPreviewCache = { cache },
            inlineImageDimensions = { dimensions },
        )
        viewModel.loadInitial()
        runCurrent()

        val job =
            launch {
                viewModel.handleIncoming(IncomingMessageEvent.DirectMessageReceived(incoming(), ChatFixtures.CONTACT))
            }
        runCurrent()
        assertEquals(1, viewModel.coordinator.messages.size)
        assertEquals(1, viewModel.renderState.value.items.size)
        assertTrue(job.isCompleted)

        advanceTimeBy(10_000)
        runCurrent()
        job.join()

        assertEquals(1, viewModel.renderState.value.items.size)
        assertEquals(2.0, dimensions.aspect(imageUrl))
    }

    @Test
    fun incomingWithoutUrlsAdmitsImmediately() = runTest {
        val (viewModel, _) = makeThreadViewModel(envInputs = { previewsOn() })
        viewModel.loadInitial()
        runCurrent()

        viewModel.handleIncoming(
            IncomingMessageEvent.DirectMessageReceived(
                incoming(text = "plain text"),
                ChatFixtures.CONTACT,
            ),
        )
        assertEquals(1, viewModel.renderState.value.items.size)
    }

    @Test
    fun incomingWithPreviewsOffAdmitsImmediately() = runTest {
        val cache = cache(probeDelayMs = 10_000, probe = ImagePixelSize(800, 400))
        val (viewModel, _) = makeThreadViewModel(
            envInputs = { EnvInputs(currentUserName = "Me", showIncomingSendTime = false, previewsEnabled = false) },
            linkPreviewCache = { cache },
            inlineImageDimensions = { InlineImageDimensionsStore() },
        )
        viewModel.loadInitial()
        runCurrent()

        viewModel.handleIncoming(IncomingMessageEvent.DirectMessageReceived(incoming(), ChatFixtures.CONTACT))
        assertEquals(1, viewModel.renderState.value.items.size)
    }

    @Test
    fun cleanupCancelsOutgoingImagePrefetch() = runTest {
        val dimensions = InlineImageDimensionsStore()
        val cache = cache(probeDelayMs = 10_000, probe = ImagePixelSize(800, 400))
        val (viewModel, _) = makeThreadViewModel(
            envInputs = { previewsOn() },
            linkPreviewCache = { cache },
            inlineImageDimensions = { dimensions },
        )
        viewModel.loadInitial()
        runCurrent()

        viewModel.admission.admit(
            ChatFixtures.message(text = "see $imageUrl", direction = MessageDirection.OUTGOING),
        )
        runCurrent()
        assertNull(dimensions.aspect(imageUrl))

        viewModel.cleanup()
        advanceTimeBy(10_000)
        runCurrent()

        assertNull(dimensions.aspect(imageUrl))
    }

    private fun incoming(text: String = "see $imageUrl"): MessageDto = ChatFixtures.message(
        text = text,
        direction = MessageDirection.INCOMING,
        contactID = ChatFixtures.CONTACT,
    )

    private fun previewsOn() = EnvInputs(currentUserName = "Me", showIncomingSendTime = false, previewsEnabled = true)

    private fun TestScope.cache(probeDelayMs: Long, probe: ImagePixelSize): LinkPreviewCache = LinkPreviewCache(
        fetcher = object : LinkMetadataFetcher {
            override suspend fun fetchMetadata(url: String): LinkPreviewFetchResult? = null
            override suspend fun probeImageDimensions(url: String): ImagePixelSize? {
                if (probeDelayMs > 0) delay(probeDelayMs)
                return probe
            }
        },
        store = InMemoryLinkPreviewStore(),
        preferences = LinkPreviewPreferences(InMemoryLinkPreviewSettings(previewsEnabled = true)),
        scope = backgroundScope,
    )

    private fun inlineImage(viewModel: ChatThreadViewModel): InlineImage? =
        viewModel.renderState.value.items.single().content
            .filterIsInstance<MessageFragment.InlineImage>()
            .firstOrNull()
            ?.image
}
