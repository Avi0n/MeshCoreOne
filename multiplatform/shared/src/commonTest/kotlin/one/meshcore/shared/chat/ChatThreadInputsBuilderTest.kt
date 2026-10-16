package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.linkpreview.InMemoryLinkPreviewSettings
import one.meshcore.shared.chat.linkpreview.InMemoryLinkPreviewStore
import one.meshcore.shared.chat.linkpreview.LinkMetadataFetcher
import one.meshcore.shared.chat.linkpreview.LinkPreviewCache
import one.meshcore.shared.chat.linkpreview.LinkPreviewController
import one.meshcore.shared.chat.linkpreview.LinkPreviewData
import one.meshcore.shared.chat.linkpreview.LinkPreviewFetchResult
import one.meshcore.shared.chat.linkpreview.LinkPreviewPreferences
import one.meshcore.shared.chat.linkpreview.detectUrl
import one.meshcore.shared.chat.linkpreview.rehydrate
import one.meshcore.shared.chat.linkpreview.requestPreviewFetch
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.urlsafety.MalwareDomainFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mirrors the Swift `MessageFragmentBuilderTests` / display-flag grouping cases (spec §8.6). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadInputsBuilderTest {
    private val pathLabels = MessagePathLabels(direct = "Direct", flood = "Flood")

    private fun dm(timestamp: Long, direction: MessageDirection = MessageDirection.INCOMING) = ChatFixtures.message(
        text = "m",
        timestamp = timestamp,
        createdAt = timestamp.toDouble(),
        direction = direction,
        contactID = ChatFixtures.CONTACT,
    )

    @Test
    fun `first message shows timestamp sender and day divider`() {
        val messages = listOf(dm(1_000L))
        val inputs = buildThreadInputs(messages, dividerMessageID = null, TEST_SENDER_RESOLVER).single().second
        assertTrue(inputs.showTimestamp)
        assertTrue(inputs.showSenderName)
        assertTrue(inputs.showDayDivider)
        assertFalse(inputs.showDirectionGap)
    }

    @Test
    fun `consecutive messages within the grouping window hide the timestamp`() {
        val messages = listOf(dm(1_000L), dm(1_100L))
        val second = buildThreadInputs(messages, dividerMessageID = null, TEST_SENDER_RESOLVER)[1].second
        assertFalse(second.showTimestamp)
    }

    @Test
    fun `a gap beyond the grouping window shows the timestamp`() {
        val messages = listOf(dm(1_000L), dm(1_000L + ChatConstants.MESSAGE_GROUPING_GAP_SECONDS + 1))
        val second = buildThreadInputs(messages, dividerMessageID = null, TEST_SENDER_RESOLVER)[1].second
        assertTrue(second.showTimestamp)
    }

    @Test
    fun `a direction change sets the direction gap`() {
        val messages = listOf(dm(1_000L, MessageDirection.INCOMING), dm(1_050L, MessageDirection.OUTGOING))
        val second = buildThreadInputs(messages, dividerMessageID = null, TEST_SENDER_RESOLVER)[1].second
        assertTrue(second.showDirectionGap)
    }

    @Test
    fun `the divider flag tags exactly the divider message`() {
        val messages = listOf(dm(1_000L), dm(1_100L), dm(1_200L))
        val inputs = buildThreadInputs(messages, dividerMessageID = messages[1].id, TEST_SENDER_RESOLVER)
        assertFalse(inputs[0].second.showNewMessagesDivider)
        assertTrue(inputs[1].second.showNewMessagesDivider)
        assertFalse(inputs[2].second.showNewMessagesDivider)
    }

    @Test
    fun `base color follows direction`() {
        val messages = listOf(dm(1_000L, MessageDirection.INCOMING), dm(1_050L, MessageDirection.OUTGOING))
        val inputs = buildThreadInputs(messages, dividerMessageID = null, TEST_SENDER_RESOLVER)
        assertEquals(BaseColorSlot.INCOMING, inputs[0].second.baseColor)
        assertEquals(BaseColorSlot.OUTGOING, inputs[1].second.baseColor)
    }

    @Test
    fun `a coordinate in the body fills map preview inputs`() {
        val message = ChatFixtures.message(
            text = "Meet at 37.7749, -122.4194 tonight",
            timestamp = 1_000L,
            createdAt = 1_000.0,
            direction = MessageDirection.INCOMING,
            contactID = ChatFixtures.CONTACT,
        )
        val inputs = buildThreadInputs(
            listOf(message),
            dividerMessageID = null,
            TEST_SENDER_RESOLVER,
            context = MessageBuildContext(
                envInputs = EnvInputs(currentUserName = "", showIncomingSendTime = false, showMapPreviews = true),
            ),
        ).single().second
        assertEquals(37.7749, inputs.mapPreviewLatitude)
        assertEquals(-122.4194, inputs.mapPreviewLongitude)
        assertTrue(inputs.isMapPreviewReady)
    }

    @Test
    fun `plain body has no map preview inputs`() {
        val pair = buildThreadInputs(
            listOf(dm(1_000L)),
            dividerMessageID = null,
            TEST_SENDER_RESOLVER,
        ).single()
        val inputs = pair.second
        assertEquals(null, inputs.mapPreviewLatitude)
        assertEquals(null, inputs.mapPreviewLongitude)
        assertFalse(inputs.isMapPreviewReady)
    }

    @Test
    fun `incoming path row is hidden when the setting is off`() {
        val message = dm(1_000L, direction = MessageDirection.INCOMING)
        val inputs = buildThreadInputs(listOf(message), dividerMessageID = null, TEST_SENDER_RESOLVER)
            .single().second
        assertEquals(null, inputs.formattedPath)
    }

    @Test
    fun `incoming path row is hidden on an outgoing message even when the setting is on`() {
        val message = dm(1_000L, direction = MessageDirection.OUTGOING)
        val inputs = buildThreadInputs(
            listOf(message),
            dividerMessageID = null,
            TEST_SENDER_RESOLVER,
            context = MessageBuildContext(
                envInputs = EnvInputs(
                    currentUserName = "",
                    showIncomingSendTime = false,
                    incomingPathLabels = pathLabels,
                ),
            ),
        ).single().second
        assertEquals(null, inputs.formattedPath)
    }

    @Test
    fun `flood-routed incoming message with no path nodes shows the flood label`() {
        val message = dm(1_000L, direction = MessageDirection.INCOMING)
        val inputs = buildThreadInputs(
            listOf(message),
            dividerMessageID = null,
            TEST_SENDER_RESOLVER,
            context = MessageBuildContext(
                envInputs = EnvInputs(
                    currentUserName = "",
                    showIncomingSendTime = false,
                    incomingPathLabels = pathLabels,
                ),
            ),
        ).single().second
        assertEquals("Flood", inputs.formattedPath)
    }

    @Test
    fun `direct-routed incoming message shows the direct label`() {
        val message = dm(1_000L, direction = MessageDirection.INCOMING)
            .copy(pathLength = ChatConstants.FLOOD_PATH_SENTINEL)
        val inputs = buildThreadInputs(
            listOf(message),
            dividerMessageID = null,
            TEST_SENDER_RESOLVER,
            context = MessageBuildContext(
                envInputs = EnvInputs(
                    currentUserName = "",
                    showIncomingSendTime = false,
                    incomingPathLabels = pathLabels,
                ),
            ),
        ).single().second
        assertEquals("Direct", inputs.formattedPath)
    }

    @Test
    fun `incoming message with hop nodes shows the comma-separated hex chain`() {
        val message = dm(1_000L, direction = MessageDirection.INCOMING).copy(
            pathLength = 0x02,
            pathNodes = byteArrayOf(0xA3.toByte(), 0x7F),
        )
        val inputs = buildThreadInputs(
            listOf(message),
            dividerMessageID = null,
            TEST_SENDER_RESOLVER,
            context = MessageBuildContext(
                envInputs = EnvInputs(
                    currentUserName = "",
                    showIncomingSendTime = false,
                    incomingPathLabels = pathLabels,
                ),
            ),
        ).single().second
        assertEquals("A3,7F", inputs.formattedPath)
    }

    @Test
    fun `url body without controller stays idle`() {
        val message = ChatFixtures.message(
            text = "see https://example.com/page for details",
            timestamp = 1_000L,
            createdAt = 1_000.0,
            direction = MessageDirection.INCOMING,
            contactID = ChatFixtures.CONTACT,
        )
        val inputs = buildThreadInputs(
            listOf(message),
            dividerMessageID = null,
            TEST_SENDER_RESOLVER,
        ).single().second
        assertEquals("https://example.com/page", inputs.cachedURL)
        assertEquals(PreviewLoadState.IDLE, inputs.previewState)
    }

    @Test
    fun `controller rehydrate leaves non-idle preview state on inputs`() = runTest {
        val message = ChatFixtures.message(
            text = "see https://example.com/docs",
            timestamp = 1_000L,
            createdAt = 1_000.0,
            direction = MessageDirection.INCOMING,
            contactID = ChatFixtures.CONTACT,
        )
        val controller = LinkPreviewController(
            cache = LinkPreviewCache(
                fetcher = LinkMetadataFetcher { null },
                store = InMemoryLinkPreviewStore(),
                preferences = LinkPreviewPreferences(InMemoryLinkPreviewSettings(previewsEnabled = true)),
                scope = backgroundScope,
            ),
            malwareFilter = MalwareDomainFilter { null },
            scope = backgroundScope,
            isChannelMessage = { false },
            onRebuild = {},
        )
        controller.detectUrl(message)
        controller.rehydrate(
            message.id,
            LinkPreviewData(url = "https://example.com/docs", title = "Example Docs"),
        )
        val inputs = buildInputsForMessage(
            message = message,
            flags = computeDisplayFlags(message, previous = null),
            dividerMessageID = null,
            resolveSender = TEST_SENDER_RESOLVER,
            context = MessageBuildContext(linkPreviewController = controller),
        )
        assertEquals(PreviewLoadState.LOADED, inputs.previewState)
        assertEquals("Example Docs", inputs.loadedPreview?.title)
        assertEquals("https://example.com/docs", inputs.cachedURL)
    }

    @Test
    fun `image-extension URL classifies as an inline image`() {
        val message = ChatFixtures.message(text = "see https://example.com/photo.png")
        val inputs = buildInputsForMessage(
            message = message,
            flags = computeDisplayFlags(message, previous = null),
            dividerMessageID = null,
            resolveSender = TEST_SENDER_RESOLVER,
        )
        assertEquals("https://example.com/photo.png", inputs.cachedURL)
        assertTrue(inputs.isInlineImageURL)
    }

    @Test
    fun `image-extension URL discovered to serve a page is not an inline image`() = runTest {
        val url = "https://example.com/photo.png"
        val message = ChatFixtures.message(text = "see $url")
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = LinkPreviewController(
            cache = LinkPreviewCache(
                fetcher = object : LinkMetadataFetcher {
                    override suspend fun fetchMetadata(url: String) = LinkPreviewFetchResult(
                        url = url,
                        title = "Photo page",
                        imageBytes = byteArrayOf(1),
                        iconBytes = null,
                    )
                    override suspend fun isDirectFetchAllowed(url: String) = true
                    override suspend fun fetchInlineImage(url: String) = InlineImageHttpResult.HtmlPage
                },
                store = InMemoryLinkPreviewStore(),
                preferences = LinkPreviewPreferences(InMemoryLinkPreviewSettings(previewsEnabled = true)),
                scope = scope,
            ),
            malwareFilter = MalwareDomainFilter { null },
            scope = scope,
            isChannelMessage = { false },
            onRebuild = {},
        )
        controller.requestPreviewFetch(message)
        val inputs = buildInputsForMessage(
            message = message,
            flags = computeDisplayFlags(message, previous = null),
            dividerMessageID = null,
            resolveSender = TEST_SENDER_RESOLVER,
            context = MessageBuildContext(linkPreviewController = controller),
        )
        assertFalse(inputs.isInlineImageURL)
        assertEquals(PreviewLoadState.LOADED, inputs.previewState)
        assertEquals("Photo page", inputs.loadedPreview?.title)
    }
}
