package one.meshcore.shared.chat

import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertNull

class ChatPreviewPrewarmerTest {
    private val imageUrl = "https://example.com/pic.png"

    @Test
    fun prewarmSavesAspectForDirectImageTail() = runTest {
        val dimensions = InlineImageDimensionsStore()
        val store = FakeChatThreadStore(
            rows = listOf(
                ChatFixtures.message(
                    text = "see $imageUrl",
                    direction = MessageDirection.INCOMING,
                    contactID = ChatFixtures.CONTACT,
                ),
            ),
        )
        val prewarmer = prewarmer(store, dimensions)
        prewarmer.prewarm(Conversation.Direct(ChatFixtures.contact(name = "Pat")))
        assertEquals(2.0, dimensions.aspect(imageUrl))
    }

    @Test
    fun prewarmSkipsWhenPreviewsAreOff() = runTest {
        val dimensions = InlineImageDimensionsStore()
        val store = FakeChatThreadStore(
            rows = listOf(
                ChatFixtures.message(
                    text = "see $imageUrl",
                    direction = MessageDirection.INCOMING,
                    contactID = ChatFixtures.CONTACT,
                ),
            ),
        )
        val prewarmer = prewarmer(store, dimensions, previewsOn = false)
        prewarmer.prewarm(Conversation.Direct(ChatFixtures.contact(name = "Pat")))
        assertNull(dimensions.aspect(imageUrl))
    }

    @Test
    fun prewarmSkipsRooms() = runTest {
        val dimensions = InlineImageDimensionsStore()
        val store = FakeChatThreadStore(
            rows = listOf(
                ChatFixtures.message(text = "see $imageUrl", direction = MessageDirection.INCOMING),
            ),
        )
        val prewarmer = prewarmer(store, dimensions)
        prewarmer.prewarm(Conversation.Room(ChatFixtures.room(name = "Ops")))
        assertNull(dimensions.aspect(imageUrl))
    }

    private fun TestScope.prewarmer(
        store: FakeChatThreadStore,
        dimensions: InlineImageDimensionsStore,
        previewsOn: Boolean = true,
    ): ChatPreviewPrewarmer {
        val cache = LinkPreviewCache(
            fetcher = object : LinkMetadataFetcher {
                override suspend fun fetchMetadata(url: String): LinkPreviewFetchResult? = null
                override suspend fun probeImageDimensions(url: String) = ImagePixelSize(800, 400)
            },
            store = InMemoryLinkPreviewStore(),
            preferences = LinkPreviewPreferences(InMemoryLinkPreviewSettings(previewsEnabled = previewsOn)),
            scope = backgroundScope,
        )
        return ChatPreviewPrewarmer(
            store = { store },
            radioId = { ChatFixtures.RADIO },
            prefetcher = { InlineImagePrefetcher(cache, dimensions) },
            previewsEnabled = { previewsOn },
            shouldAutoResolve = { true },
        )
    }
}
