// PortedFrom: MC1Tests/Views/Chats/Components/UnifiedMessageBubbleA11yLabelTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/Components/MessageBubbleTestData.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.content.DataStoreLinkPreviewPreferencesSource
import com.meshcoreone.android.app.navigation.*
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as Chats
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import com.meshcoreone.android.core.services.rendering.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageBubbleAccessibilityAcceptanceTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val resources get() = host.get().resources
    private val sourceRadio = RadioId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val sourceContact = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val sourceDate = Instant.ofEpochSecond(1_700_000_000)

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @OriginalCase("UnifiedMessageBubbleA11yLabelTests::incoming message label includes sender, text, and footer fragments()")
    @Test fun incomingOriginalLabelIncludesResolvedSenderTextAndEveryFooterFragment() {
        val message = incoming("hello world").copy(pathNodes = null, regionScope = "NORTHWEST")
        val item = sourceItem(message, NodeNameResolution("Alice", NodeNameMatchKind.EXACT),
            formattedPath = "A to B to C", hop = true, region = true)
        val expected = "Alice: hello world" +
            ", ${Chats.chatsMessageHopCountAccessibilityLabel(resources, message.hopCount.toInt())}" +
            ", ${Chats.chatsMessagePathAccessibilityLabel(resources, "A to B to C")}" +
            ", ${Chats.chatsMessageRegionAccessibilityLabel(resources, "NORTHWEST")}"
        assertEquals(expected, messageBubbleAccessibilityLabel(message, item, true, resources))
    }

    @OriginalCase("UnifiedMessageBubbleA11yLabelTests::outgoing message label includes status text()")
    @Test fun outgoingOriginalPendingLabelIncludesSendingStatus() {
        val message = outgoing("ok", MessageStatus.PENDING)
        val item = sourceItem(message)
        assertTrue(messageBubbleAccessibilityLabel(message, item, false, resources)
            .contains(resources.getString(Chats.chatsMessageStatusSending)))
    }

    @OriginalCase("UnifiedMessageBubbleA11yLabelTests::fallback sender label includes possible-match disclosure()")
    @Test fun fallbackOriginalLabelIncludesPossibleMatchDisclosure() {
        val message = incoming("hi").copy(senderNodeName = null)
        val item = sourceItem(message, NodeNameResolution("Alice", NodeNameMatchKind.FALLBACK))
        val label = messageBubbleAccessibilityLabel(message, item, true, resources)
        assertTrue(label.startsWith("Alice: "))
        assertTrue(label.contains(resources.getString(Chats.chatsMessageSenderPossibleMatch)))
    }

    @OriginalCase("UnifiedMessageBubbleA11yLabelTests::label composition is stable across repeated reads()")
    @Test fun originalLabelCompositionIsStableAcrossRepeatedReads() {
        val message = outgoing("stable", MessageStatus.SENT)
        val item = sourceItem(message)
        val first = messageBubbleAccessibilityLabel(message, item, false, resources)
        val second = messageBubbleAccessibilityLabel(message, item, false, resources)
        assertEquals(first, second)
        assertTrue(first.contains("stable"))
    }

    @Test fun supplementalStatusAndRegionLabelsKeepWideCountersAndEveryAmbiguousCandidate() {
        val retry = outgoing("original", MessageStatus.RETRYING).copy(
            retryAttempt = 2_147_483_648, maxRetryAttempts = 2_147_483_650,
        )
        assertEquals("original, Retrying 2147483649/2147483650",
            messageBubbleAccessibilityLabel(retry, sourceItem(retry), false, resources))
        val heard = outgoing("original", MessageStatus.DELIVERED).copy(heardRepeats = 2, sendCount = 2_147_483_648)
        val heardLabel = messageBubbleAccessibilityLabel(heard, sourceItem(heard), false, resources)
        assertTrue(heardLabel.contains("2 ${resources.getString(Chats.chatsMessageRepeatPlural)}"))
        assertTrue(heardLabel.contains("2147483648"))
        val ambiguous = incoming("original").copy(regionScopeMatches = listOf("NORTH", "SOUTH").snapshot())
        val label = messageBubbleAccessibilityLabel(ambiguous, sourceItem(ambiguous, region = true), true, resources)
        assertTrue(label.contains(Chats.chatsMessageRegionAmbiguousAccessibilityLabel(resources, "NORTH and SOUTH")))
        assertFalse(label.contains("NORTH/SOUTH"))
    }

    @Test fun mountedAppUsesRealNicknamePreferencesAndRetainedMessageIdentityThenCopiesOriginalText() = runTest {
        withApp(this) { f ->
            val contact = f.contact("Alice", 3).copy(nickname = "Field Alice")
            store.saveContact(contact)
            val message = incoming("hello world").copy(radioId = f.radio, regionScope = "NORTHWEST")
            store.saveMessage(message)
            f.mount(channel = true)
            val prefix = "Field Alice ${Chats.chatsMessageSenderUnverifiedNicknameFormat(resources, "Alice")}: " +
                "${resources.getString(Chats.chatsMessageSenderUnverifiedNicknameAccessibilityLabel)}, "
            val first = prefix + message.text
            f.awaitLabel(first)
            val nodeID = bubble(first).fetchSemanticsNode().id
            f.assertSingleLabel(first)

            f.storage.preferences.set(AppStorageKey.showIncomingHopCount, true)
            f.storage.preferences.set(AppStorageKey.showIncomingPath, true)
            f.storage.preferences.set(AppStorageKey.showIncomingRegion, true)
            val suffix = ", ${Chats.chatsMessageHopCountAccessibilityLabel(resources, 3)}" +
                ", ${Chats.chatsMessagePathAccessibilityLabel(resources, "A3,7F,42")}" +
                ", ${Chats.chatsMessageRegionAccessibilityLabel(resources, "NORTHWEST")}"
            f.awaitLabel(first + suffix)
            assertEquals(nodeID, bubble(first + suffix).fetchSemanticsNode().id)
            f.assertSingleLabel(first + suffix)
            compose.onNodeWithContentDescription(first).assertDoesNotExist()

            val updated = message.copy(text = "original \u4f60\u597d \u0645\u0631\u062d\u0628\u0627")
            store.saveMessage(updated)
            f.h.appState.messageEventStream.send(MessageEvent.MessagesRegionUpdated(listOf(message.id)))
            val updatedLabel = prefix + updated.text + suffix
            f.awaitLabel(updatedLabel)
            assertEquals(nodeID, bubble(updatedLabel).fetchSemanticsNode().id)
            f.assertSingleLabel(updatedLabel)
            assertEquals(updated, store.fetchMessage(EntityKey(f.radio, updated.id)))

            val clipboard = requireNotNull(host.get().getSystemService(ClipboardManager::class.java))
            var copies = 0
            val listener = ClipboardManager.OnPrimaryClipChangedListener { copies++ }
            clipboard.addPrimaryClipChangedListener(listener)
            try {
                compose.onNodeWithText(resources.getString(Chats.chatsMessageActionDetails)).performClick()
                compose.onNodeWithText(resources.getString(Chats.chatsMessageActionTranslate)).assertDoesNotExist()
                compose.onNodeWithText(resources.getString(Chats.chatsMessageActionCopy)).performScrollTo().performClick()
                compose.waitForIdle()
                assertEquals(updated.text, requireNotNull(clipboard.primaryClip).getItemAt(0).text.toString())
                assertEquals(1, copies)
                f.assertSingleLabel(updatedLabel)
                assertEquals(updated, store.fetchMessage(EntityKey(f.radio, updated.id)))
            } finally { clipboard.removePrimaryClipChangedListener(listener) }
            assertTrue(f.http.requests.isEmpty())
        }
    }

    @Test fun mountedAppUsesActualAmbiguousPrefixResolutionAndNeverClaimsAnAmbiguousNickname() = runTest {
        withApp(this) { f ->
            store.saveContact(f.contact("Alice", 3).copy(nickname = "First nickname"))
            store.saveContact(f.contact("Alice", 4).copy(nickname = "Second nickname"))
            val message = incoming("hi").copy(radioId = f.radio)
            store.saveMessage(message)
            f.mount(channel = true)
            f.awaitLabel("Alice: hi")
            f.assertSingleLabel("Alice: hi")
            assertFalse(bubble("Alice: hi").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
                .single().contains(resources.getString(Chats.chatsMessageSenderUnverifiedNicknameAccessibilityLabel)))

            val contacts = store.fetchContacts(f.radio)
            for (contact in contacts) store.saveContact(contact.copy(
                name = if (contact.publicKey == f.contact("Alice", 3).publicKey) "Alice" else "Zulu",
                nickname = null,
            ))
            f.h.appState.refreshConversations()
            val fallback = message.copy(senderNodeName = null, senderKeyPrefix = Bytes.of(0xAB))
            store.saveMessage(fallback)
            f.h.appState.messageEventStream.send(MessageEvent.MessagesRegionUpdated(listOf(message.id)))
            val label = "Alice: ${resources.getString(Chats.chatsMessageSenderPossibleMatch)}, hi"
            f.awaitLabel(label)
            f.assertSingleLabel(label)
            assertEquals(fallback, store.fetchMessage(EntityKey(f.radio, fallback.id)))
        }
    }

    @Test fun mountedAppPreservesRichLinkImageAndExactlyOneRealFailedSendRetry() = runTest {
        withApp(this, images = true) { f ->
            val contact = f.contact("Peer", 3)
            store.saveContact(contact)
            val message = outgoing("look $IMAGE", MessageStatus.FAILED).copy(radioId = f.radio, contactID = contact.id)
            store.saveMessage(message)
            f.mount(channel = false, direct = contact)
            val label = "${message.text}, ${resources.getString(Chats.chatsMessageStatusFailed)}"
            f.awaitLabel(label)
            f.assertSingleLabel(label)

            val body = compose.onNode(hasText(message.text) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val point = layouts.single().getBoundingBox(message.text.indexOf(IMAGE) + 2).center
            body.performTouchInput { click(point) }
            compose.waitForIdle()
            assertEquals(listOf(IMAGE), f.opened.toList())

            compose.onNodeWithText(resources.getString(Chats.chatsPreviewTapToLoad)).performClick()
            compose.waitUntil(30_000) {
                f.h.settle()
                compose.onAllNodesWithContentDescription(IMAGE).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithContentDescription(IMAGE).assertHasClickAction().performClick()
            compose.onNode(isDialog()).assertExists()
            compose.onNodeWithText(resources.getString(AppLocalizableStrings.commonDone)).performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
            f.assertSingleLabel(label)
            assertEquals(listOf(IMAGE), f.opened.toList())

            compose.onNodeWithText(resources.getString(Chats.chatsMessageStatusRetry)).assertIsEnabled().performClick()
            f.h.eventually("one actual retry reaches firmware, fails, and deletes its own pending send") {
                f.h.links.radios.single().sent.count { it[0].toInt() == 2 } == 1 && runBlocking {
                    store.fetchMessage(EntityKey(f.radio, message.id))?.status == MessageStatus.FAILED &&
                        store.fetchPendingSendsForMessage(EntityKey(f.radio, message.id)).isEmpty()
                }
            }
            f.awaitLabel(label)
            f.assertSingleLabel(label)
            compose.waitForIdle()
            assertEquals(1, f.h.links.radios.single().sent.count { it[0].toInt() == 2 })
            assertEquals(message.id, requireNotNull(store.fetchMessage(EntityKey(f.radio, message.id))).id)
            assertEquals(message.text, requireNotNull(store.fetchMessage(EntityKey(f.radio, message.id))).text)
            assertEquals(listOf(IMAGE), f.opened.toList())
            assertEquals(1, f.http.requests.count { it == IMAGE })
        }
    }

    private fun bubble(label: String) = compose.onNodeWithContentDescription(label)

    private suspend fun withApp(test: TestScope, images: Boolean = false, body: suspend (Fixture) -> Unit) {
        val f = Fixture(test)
        try { f.start(images); body(f) } finally {
            withContext(NonCancellable) {
                compose.runOnUiThread { host.get().setContent {} }
                compose.waitForIdle()
                f.close()
            }
        }
    }

    private inner class Fixture(private val test: TestScope) {
        val storage = MeshCoreStorage.get(host.get())
        val job = SupervisorJob(test.backgroundScope.coroutineContext[Job])
        private val scope = CoroutineScope(test.backgroundScope.coroutineContext + job)
        val http = ImageHttp()
        val opened = Collections.synchronizedList(mutableListOf<String>())
        lateinit var content: AppChatContent
        lateinit var h: ContainerHarness
        val radio get() = requireNotNull(h.appState.currentRadioId)

        suspend fun start(images: Boolean) {
            storage.preferences.update {
                this[AppStorageKey.linkPreviewsEnabled] = images
                this[AppStorageKey.linkPreviewsAutoResolveDM] = false
                this[AppStorageKey.showMapPreviewThumbnails] = false
                this[AppStorageKey.showIncomingPath] = false
                this[AppStorageKey.showIncomingHopCount] = false
                this[AppStorageKey.showIncomingRegion] = false
            }
            content = AppChatContent(host.get(), store, http,
                DataStoreLinkPreviewPreferencesSource.create(storage.preferences, scope),
                storage.preferences, storage.preferences.snapshot(), scope, imageSafety = { it == IMAGE })
            h = ContainerHarness(test, store, chatContent = content)
            h.links.configureRadio = { firmware ->
                firmware.respond = { request -> if (request[0].toInt() == 2) listOf(Bytes.of(1, 2)) else null }
            }
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
        }

        fun contact(name: String, seed: Int) = ContactDTO(
            id = UUID.nameUUIDFromBytes("bubble-$name-$seed".toByteArray(Charsets.UTF_8)), radioId = radio,
            publicKey = Bytes(ByteArray(32) { if (it == 0) 0xAB.toByte() else seed.toByte() }),
            name = name, lastHeardTimestamp = null,
        )

        suspend fun mount(channel: Boolean, direct: ContactDTO? = null) {
            if (channel) {
                val selected = ChannelDTO(radioId = radio, index = 1u, name = "Original bubble channel")
                store.saveChannel(selected)
                h.container.navigation.navigateToChannel(selected)
            } else h.container.navigation.navigateToChat(requireNotNull(direct))
            val destination = h.container.navigation.state.value.activeStack.last().destination
            compose.runOnUiThread {
                host.get().setContent {
                    CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) { opened += uri }
                    }) {
                        MeshCoreTheme(motionScale = 0f) {
                            AppChatsContent(h.container.chatsFeature, destination, h.container.navigation)
                        }
                    }
                }
            }
            h.settle()
            compose.waitForIdle()
        }

        fun awaitLabel(label: String) {
            compose.waitUntil(30_000) {
                h.settle()
                compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().size == 1
            }
        }

        fun assertSingleLabel(label: String) {
            for (unmerged in listOf(false, true)) {
                compose.onAllNodesWithContentDescription(label, useUnmergedTree = unmerged).assertCountEquals(1)
                val config = compose.onNodeWithContentDescription(label, useUnmergedTree = unmerged).fetchSemanticsNode().config
                assertEquals(listOf(label), config[SemanticsProperties.ContentDescription])
                assertFalse("Do not erase rich-body, media, Details or retry descendants", config.isClearingSemantics)
                assertFalse(config.contains(SemanticsProperties.LiveRegion))
            }
        }

        suspend fun close() {
            try { if (this::h.isInitialized) h.container.close() } finally {
                if (this::content.isInitialized) content.close()
                job.cancelAndJoin()
                storage.close()
            }
        }
    }

    private fun incoming(text: String) = MessageDTO(
        id = UUID.fromString("44444444-4444-4444-4444-444444444444"), radioId = sourceRadio,
        channelIndex = 1u, text = text, timestamp = 1_700_000_000u, createdAt = sourceDate,
        direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
        pathLength = 3u, pathNodes = Bytes.of(0xA3, 0x7F, 0x42),
        senderKeyPrefix = Bytes.fromHex("ABCDEF012345"), senderNodeName = "Alice", isRead = true,
    )

    private fun outgoing(text: String, status: MessageStatus) = MessageDTO(
        id = UUID.fromString("33333333-3333-3333-3333-333333333333"), radioId = sourceRadio,
        contactID = sourceContact, text = text, timestamp = 1_700_000_000u, createdAt = sourceDate,
        direction = MessageDirection.OUTGOING, status = status, pathLength = 0u, isRead = true, sendCount = 1,
    )

    private fun sourceItem(
        message: MessageDTO,
        resolution: NodeNameResolution = NodeNameResolution("Unknown", NodeNameMatchKind.UNRESOLVED),
        formattedPath: String? = null, hop: Boolean = false, region: Boolean = false,
    ): MessageItem = MessageFragmentBuilder.makeItem(message, MessageBuildInputs(
        messageID = message.id, previewState = PreviewLoadState.IDLE, loadedPreview = null, cachedURL = null,
        isInlineImageURL = false, hasInlineImageRef = false, hasPreviewImageRef = false,
        hasPreviewIconRef = false, imageIsGIF = false, formattedText = null,
        baseColor = if (message.isOutgoing) BaseColorSlot.OUTGOING else BaseColorSlot.INCOMING,
        formattedPath = formattedPath, senderResolution = resolution, showTimestamp = false,
        showDirectionGap = false, showSenderName = true, showNewMessagesDivider = false,
    ), EnvInputs.DEFAULT.copy(
        showIncomingPath = formattedPath != null, showIncomingHopCount = hop, showIncomingRegion = region,
        previewsEnabled = true, currentUserName = "Me", translationOffersEnabled = false,
    ))

    private class ImageHttp : BoundedHttpFetching {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
            requests += url
            if (url != IMAGE) return HttpFetchAttempt.Failed("External requests are disabled in the local-image fixture")
            val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
            val png = ByteArrayOutputStream().use { output ->
                try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
                finally { bitmap.recycle() }
            }
            return HttpFetchAttempt.Started(200, "image/png", png.size.toLong(), {}) { chunk -> chunk(png); Unit }
        }
    }

    private companion object { const val IMAGE = "https://1.1.1.1/image.png" }
}
