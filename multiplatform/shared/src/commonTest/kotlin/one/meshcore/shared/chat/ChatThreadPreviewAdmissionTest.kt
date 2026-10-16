package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.linkpreview.InMemoryLinkPreviewSettings
import one.meshcore.shared.chat.linkpreview.InMemoryLinkPreviewStore
import one.meshcore.shared.chat.linkpreview.LinkMetadataFetcher
import one.meshcore.shared.chat.linkpreview.LinkPreviewCache
import one.meshcore.shared.chat.linkpreview.LinkPreviewFetchResult
import one.meshcore.shared.chat.linkpreview.LinkPreviewPreferences
import one.meshcore.shared.chat.linkpreview.LinkPreviewResult
import one.meshcore.shared.model.MessageDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadPreviewAdmissionTest {
    @Test
    fun suspendedPageFetchDoesNotHoldFollowingDmOrChannelMessages() = runTest {
        for (isChannel in listOf(false, true)) {
            val fetcher = PausedFetcher()
            val (viewModel, _) = thread(isChannel, fetcher)
            viewModel.loadInitial()
            runCurrent()
            val page = incoming(isChannel, PAGE_URL, timestamp = 1_000)
            val plain = incoming(isChannel, "plain text", timestamp = 1_001)
            val admission = launch {
                viewModel.handleIncoming(event(isChannel, page))
                viewModel.handleIncoming(event(isChannel, plain))
            }
            runCurrent()

            assertTrue(admission.isCompleted)
            assertEquals(listOf(page.id, plain.id), viewModel.coordinator.messages.map { it.id })
            assertEquals(listOf(page.id, plain.id), viewModel.renderState.value.items.map { it.id })
            assertEquals(1, fetcher.pageCalls)
            assertEquals(0, fetcher.pageCompletions)

            fetcher.pageRelease.complete(Unit)
            runCurrent()
            val preview = viewModel.renderState.value.items.first().content
                .filterIsInstance<MessageFragment.LinkPreview>().single().state.mode
            assertEquals("Resolved title", assertIs<LinkPreviewFragmentState.Mode.Loaded>(preview).preview.title)
            assertEquals(listOf(page.id, plain.id), viewModel.renderState.value.items.map { it.id })
            assertEquals(1, fetcher.pageCalls)
            viewModel.cleanup()
        }
    }

    @Test
    fun suspendedImageProbeDoesNotHoldFollowingDmOrChannelMessages() = runTest {
        for (isChannel in listOf(false, true)) {
            val fetcher = PausedFetcher()
            val dimensions = InlineImageDimensionsStore()
            val (viewModel, _) = thread(isChannel, fetcher, dimensions)
            viewModel.loadInitial()
            runCurrent()
            val image = incoming(isChannel, IMAGE_URL, timestamp = 1_000)
            val plain = incoming(isChannel, "plain text", timestamp = 1_001)
            val admission = launch {
                viewModel.handleIncoming(event(isChannel, image))
                viewModel.handleIncoming(event(isChannel, plain))
            }
            runCurrent()

            assertTrue(admission.isCompleted)
            assertEquals(listOf(image.id, plain.id), viewModel.renderState.value.items.map { it.id })
            assertEquals(1, fetcher.probeCalls)
            assertNull(dimensions.aspect(IMAGE_URL))
            fetcher.probeRelease.complete(Unit)
            runCurrent()
            assertEquals(2.0, dimensions.aspect(IMAGE_URL))
            assertEquals(listOf(image.id, plain.id), viewModel.coordinator.messages.map { it.id })
            assertEquals(listOf(image.id, plain.id), viewModel.renderState.value.items.map { it.id })
            viewModel.cleanup()
        }
    }

    @Test
    fun cancelledCallerCannotAdmitOrMutateScrollAndUnreadState() = runTest {
        val fetcher = PausedFetcher()
        val (viewModel, _) = thread(isChannel = false, fetcher)
        viewModel.loadInitial()
        runCurrent()
        viewModel.setNearBottom(false)
        val before = viewModel.renderState.value
        val admission = launch {
            currentCoroutineContext().cancel()
            viewModel.handleIncoming(event(false, incoming(false, "$PAGE_URL $IMAGE_URL", 1_000)))
        }
        runCurrent()

        assertTrue(admission.isCancelled)
        assertTrue(viewModel.coordinator.messages.isEmpty())
        assertEquals(before, viewModel.renderState.value)
        assertEquals(0, fetcher.pageCalls)
        assertEquals(0, fetcher.probeCalls)
    }

    @Test
    fun cleanupCancelsIncomingProbeWithoutRemovingTheImmediateRow() = runTest {
        val fetcher = PausedFetcher()
        val dimensions = InlineImageDimensionsStore()
        val (viewModel, _) = thread(false, fetcher, dimensions)
        viewModel.loadInitial()
        runCurrent()
        val message = incoming(false, IMAGE_URL, 1_000)
        viewModel.handleIncoming(event(false, message))
        runCurrent()
        assertEquals(1, fetcher.probeCalls)
        assertEquals(listOf(message.id), viewModel.renderState.value.items.map { it.id })

        viewModel.cleanup()
        runCurrent()
        assertTrue(fetcher.probeCancelled)
        fetcher.probeRelease.complete(Unit)
        runCurrent()
        assertNull(dimensions.aspect(IMAGE_URL))
        assertEquals(listOf(message.id), viewModel.coordinator.messages.map { it.id })
        assertEquals(listOf(message.id), viewModel.renderState.value.items.map { it.id })
    }

    @Test
    fun cleanupKeepsAppCacheWarmingWithoutRebuildingReleasedRows() = runTest {
        val fetcher = PausedFetcher()
        val cache = cache(fetcher, InMemoryLinkPreviewSettings(previewsEnabled = true))
        val (viewModel, _) = makeThreadViewModel(envInputs = { previewsOn() }, linkPreviewCache = { cache })
        viewModel.loadInitial()
        runCurrent()
        viewModel.handleIncoming(event(false, incoming(false, PAGE_URL, 1_000)))
        runCurrent()
        assertEquals(1, fetcher.pageCalls)
        val before = viewModel.renderState.value

        viewModel.cleanup()
        fetcher.pageRelease.complete(Unit)
        runCurrent()
        assertEquals(1, fetcher.pageCompletions)
        assertIs<LinkPreviewResult.Loaded>(cache.preview(PAGE_URL, isChannelMessage = false))
        assertEquals(before, viewModel.renderState.value)
        assertEquals(1, fetcher.pageCalls)
    }

    @Test
    fun bothMasterGatesAndTheCorrectConversationScopeControlBackgroundFetches() = runTest {
        for (isChannel in listOf(false, true)) {
            for (envEnabled in listOf(false, true)) {
                for (cacheEnabled in listOf(false, true)) {
                    for (scopeEnabled in listOf(false, true)) {
                        val fetcher = PausedFetcher()
                        val settings = InMemoryLinkPreviewSettings(
                            previewsEnabled = cacheEnabled,
                            autoResolveDM = if (isChannel) !scopeEnabled else scopeEnabled,
                            autoResolveChannels = if (isChannel) scopeEnabled else !scopeEnabled,
                        )
                        val (viewModel, _) = thread(isChannel, fetcher, settings = settings, envEnabled = envEnabled)
                        viewModel.loadInitial()
                        runCurrent()
                        viewModel.handleIncoming(event(isChannel, incoming(isChannel, "$PAGE_URL $IMAGE_URL", 1_000)))
                        runCurrent()
                        val expected = if (envEnabled && cacheEnabled && scopeEnabled) 1 else 0
                        assertEquals(1, viewModel.coordinator.messages.size)
                        assertEquals(1, viewModel.renderState.value.items.size)
                        assertEquals(expected, fetcher.pageCalls)
                        assertEquals(expected, fetcher.probeCalls)
                        fetcher.pageRelease.complete(Unit)
                        fetcher.probeRelease.complete(Unit)
                        runCurrent()
                        viewModel.cleanup()
                    }
                }
            }
        }
    }

    private fun TestScope.thread(
        isChannel: Boolean,
        fetcher: PausedFetcher,
        dimensions: InlineImageDimensionsStore = InlineImageDimensionsStore(),
        settings: InMemoryLinkPreviewSettings = InMemoryLinkPreviewSettings(previewsEnabled = true),
        envEnabled: Boolean = true,
    ): Pair<ChatThreadViewModel, ChatCoordinator> {
        val cache = cache(fetcher, settings)
        return makeThreadViewModel(
            conversationID = if (isChannel) {
                ChatConversationID.channel(ChatFixtures.RADIO, 0)
            } else {
                ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
            },
            envInputs = { previewsOn().copy(previewsEnabled = envEnabled) },
            linkPreviewCache = { cache },
            inlineImageDimensions = { dimensions },
        )
    }

    private fun TestScope.cache(fetcher: PausedFetcher, settings: InMemoryLinkPreviewSettings) = LinkPreviewCache(
        fetcher = fetcher,
        store = InMemoryLinkPreviewStore(),
        preferences = LinkPreviewPreferences(settings),
        scope = backgroundScope,
    )

    private class PausedFetcher : LinkMetadataFetcher {
        val pageRelease = CompletableDeferred<Unit>()
        val probeRelease = CompletableDeferred<Unit>()
        var pageCalls = 0
        var pageCompletions = 0
        var probeCalls = 0
        var probeCancelled = false

        override suspend fun fetchMetadata(url: String): LinkPreviewFetchResult {
            pageCalls += 1
            pageRelease.await()
            pageCompletions += 1
            return LinkPreviewFetchResult(url, "Resolved title", null, null)
        }

        override suspend fun probeImageDimensions(url: String): ImagePixelSize {
            probeCalls += 1
            try {
                probeRelease.await()
            } catch (cancelled: CancellationException) {
                probeCancelled = true
                throw cancelled
            }
            return ImagePixelSize(800, 400)
        }
    }

    private companion object {
        const val PAGE_URL = "https://example.com/page"
        const val IMAGE_URL = "https://example.com/photo.png"

        fun incoming(isChannel: Boolean, text: String, timestamp: Long): MessageDto = ChatFixtures.message(
            text = text,
            timestamp = timestamp,
            direction = MessageDirection.INCOMING,
            contactID = if (isChannel) null else ChatFixtures.CONTACT,
            channelIndex = if (isChannel) 0 else null,
            senderNodeName = if (isChannel) "Alice" else null,
        )

        fun event(isChannel: Boolean, message: MessageDto): IncomingMessageEvent = if (isChannel) {
            IncomingMessageEvent.ChannelMessageReceived(message, 0)
        } else {
            IncomingMessageEvent.DirectMessageReceived(message, ChatFixtures.CONTACT)
        }

        fun previewsOn() = EnvInputs(currentUserName = "Me", showIncomingSendTime = false, previewsEnabled = true)
    }
}
