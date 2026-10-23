// PortedFrom: MC1/Views/Chats/Components/IncomingAvatarGutter.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+Messages.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: mounted App table/photo/grouping regressions, not additional original-case credit.
package com.meshcoreone.android.app.navigation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import com.meshcoreone.android.app.container.AppChatContent
import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.app.content.DataStoreLinkPreviewPreferencesSource
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import java.io.ByteArrayOutputStream
import java.time.Instant
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
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChannelAvatarConsumerTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun channelClusterBookendsReserveTheRealScaledColumnAndStayDecorative() = runTest {
        withApp(this) { f ->
            val first = f.message("First", 0)
            val second = f.message("Second", 1)
            store.saveContact(f.contact("Alice", 1))
            store.saveMessage(first)
            store.saveMessage(second)
            f.mount(fontScale = 2f)
            f.awaitAvatar(second)
            compose.onNodeWithTag(avatarTag(first), useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag(spacerTag(first), useUnmergedTree = true).assertExists()
            val avatar = compose.onNodeWithTag(avatarTag(second), useUnmergedTree = true)
            assertEquals(56f, avatar.getUnclippedBoundsInRoot().width.value, 0.1f)
            val firstLeft = bubble(first).getUnclippedBoundsInRoot().left.value
            val secondLeft = bubble(second).getUnclippedBoundsInRoot().left.value
            assertEquals(firstLeft, secondLeft, 0.1f)
            assertEquals(62f, secondLeft - avatar.getUnclippedBoundsInRoot().left.value, 0.1f)
            avatar.assertHasNoClickAction()
            compose.onAllNodesWithContentDescription("Alice", useUnmergedTree = true).assertCountEquals(0)
            assertEquals(first, store.fetchMessage(EntityKey(f.radio, first.id)))
            assertEquals(second, store.fetchMessage(EntityKey(f.radio, second.id)))
            f.assertNoSendsOrHttp()
        }
    }

    @Test fun replacingPhotoWithoutChangingLastModifiedUpdatesTheSameMountedBubble() = runTest {
        withApp(this) { f ->
            val contact = f.contact("Alice", 1).copy(avatarImageData = png(Color.RED))
            val message = f.message("Original text", 0)
            store.saveContact(contact)
            store.saveMessage(message)
            f.mount()
            f.awaitColor(message, Color.RED)
            val nodeId = bubble(message).fetchSemanticsNode().id
            val replacement = contact.copy(avatarImageData = png(Color.GREEN))
            assertEquals(contact.lastModified, replacement.lastModified)
            store.saveContact(replacement)
            f.h.appState.bumpContactsVersion()
            f.awaitColor(message, Color.GREEN)
            assertEquals(nodeId, bubble(message).fetchSemanticsNode().id)
            assertEquals(message, store.fetchMessage(EntityKey(f.radio, message.id)))
            f.assertNoSendsOrHttp()
        }
    }

    @Test fun collidingContactNamesRevokeThePhotoAndUniqueNameRecoveryRestoresIt() = runTest {
        withApp(this) { f ->
            val alice = f.contact("Alice", 1).copy(avatarImageData = png(Color.RED))
            val collision = f.contact("alice", 2).copy(avatarImageData = png(Color.GREEN))
            val message = f.message("Do not assert an ambiguous contact", 0)
            store.saveContact(alice)
            store.saveMessage(message)
            f.mount()
            f.awaitColor(message, Color.RED)
            val nodeId = bubble(message).fetchSemanticsNode().id
            store.saveContact(collision)
            f.h.appState.bumpContactsVersion()
            f.awaitInitials(message)
            assertEquals(nodeId, bubble(message).fetchSemanticsNode().id)
            store.saveContact(collision.copy(name = "Other"))
            f.h.appState.bumpContactsVersion()
            f.awaitColor(message, Color.RED)
            assertEquals(nodeId, bubble(message).fetchSemanticsNode().id)
            f.assertNoSendsOrHttp()
        }
    }

    @Test fun prefixOnlySenderUsesResolvedInitialsButNeverTheContactPhoto() = runTest {
        withApp(this) { f ->
            store.saveContact(f.contact("Alice", 1).copy(avatarImageData = png(Color.RED)))
            val message = f.message("Prefix only", 0).copy(
                senderNodeName = null, senderKeyPrefix = Bytes.of(0xAA, 1),
            )
            val namedControl = f.message("Named positive control", 1)
            store.saveMessage(message)
            store.saveMessage(namedControl)
            f.mount()
            f.awaitColor(namedControl, Color.RED)
            f.awaitInitials(message)
            compose.onNodeWithContentDescription("Alice: Prefix only").assertExists()
            assertEquals(message, store.fetchMessage(EntityKey(f.radio, message.id)))
            f.assertNoSendsOrHttp()
        }
    }

    @Test fun directAndOutgoingRowsNeverReserveTheIncomingChannelColumn() = runTest {
        withApp(this) { f ->
            val contact = f.contact("Alice", 1).copy(avatarImageData = png(Color.RED))
            store.saveContact(contact)
            val direct = f.message("Direct", 0).copy(channelIndex = null, contactID = contact.id)
            store.saveMessage(direct)
            f.mount(direct = contact)
            f.awaitBubble(direct)
            compose.onNodeWithTag(avatarTag(direct), useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag(spacerTag(direct), useUnmergedTree = true).assertDoesNotExist()

            val outgoing = f.message("Outgoing", 1).copy(
                direction = MessageDirection.OUTGOING, status = MessageStatus.SENT,
            )
            store.saveMessage(outgoing)
            f.mount()
            f.awaitBubble(outgoing)
            compose.onNodeWithTag(avatarTag(outgoing), useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag(spacerTag(outgoing), useUnmergedTree = true).assertDoesNotExist()
            f.assertNoSendsOrHttp()
        }
    }

    @Test fun realArrivalMovesTheBookendWithoutReplacingTheExistingMessageBubble() = runTest {
        withApp(this) { f ->
            store.saveContact(f.contact("Alice", 1).copy(avatarImageData = png(Color.RED)))
            val first = f.message("First", 0)
            store.saveMessage(first)
            f.mount()
            f.awaitColor(first, Color.RED)
            val nodeId = bubble(first).fetchSemanticsNode().id
            val second = f.message("Second", 1)
            store.saveMessage(second)
            f.h.appState.messageEventStream.send(MessageEvent.ChannelMessageReceived(second, 1u))
            f.awaitColor(second, Color.RED)
            compose.onNodeWithTag(avatarTag(first), useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag(spacerTag(first), useUnmergedTree = true).assertExists()
            assertEquals(nodeId, bubble(first).fetchSemanticsNode().id)
            assertEquals(
                bubble(first).getUnclippedBoundsInRoot().left.value,
                bubble(second).getUnclippedBoundsInRoot().left.value, 0.1f,
            )
            assertEquals(first, store.fetchMessage(EntityKey(f.radio, first.id)))
            assertEquals(second, store.fetchMessage(EntityKey(f.radio, second.id)))
            f.assertNoSendsOrHttp()
        }
    }

    private suspend fun withApp(test: TestScope, body: suspend (Fixture) -> Unit) {
        val fixture = Fixture(test)
        try { fixture.start(); body(fixture) } finally {
            withContext(NonCancellable) {
                compose.runOnUiThread { host.get().setContent {} }
                compose.waitForIdle()
                fixture.close()
            }
        }
    }

    private inner class Fixture(private val test: TestScope) {
        private val storage = MeshCoreStorage.get(host.get())
        private val job = SupervisorJob(test.backgroundScope.coroutineContext[Job])
        private val scope = CoroutineScope(test.backgroundScope.coroutineContext + job)
        private val httpRequests = java.util.Collections.synchronizedList(mutableListOf<String>())
        private lateinit var content: AppChatContent
        lateinit var h: ContainerHarness
        val radio get() = requireNotNull(h.appState.currentRadioId)

        suspend fun start() {
            storage.preferences.update {
                this[AppStorageKey.linkPreviewsEnabled] = false
                this[AppStorageKey.showMapPreviewThumbnails] = false
                this[AppStorageKey.showIncomingPath] = false
                this[AppStorageKey.showIncomingHopCount] = false
                this[AppStorageKey.showIncomingRegion] = false
            }
            val http = object : BoundedHttpFetching {
                override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
                    httpRequests += url
                    return HttpFetchAttempt.Failed("Avatar tests do not fetch network content")
                }
            }
            content = AppChatContent(
                host.get(), store, http, DataStoreLinkPreviewPreferencesSource.create(storage.preferences, scope),
                storage.preferences, storage.preferences.snapshot(), scope,
            )
            h = ContainerHarness(test, store, chatContent = content)
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
        }

        fun contact(name: String, seed: Int) = ContactDTO(
            radioId = radio, publicKey = Bytes(ByteArray(32) { if (it == 0) 0xAA.toByte() else seed.toByte() }),
            name = name, typeRawValue = 1u, outPathLength = 0u, lastHeardTimestamp = null,
        )

        fun message(text: String, offset: Int) = MessageDTO(
            id = UUID.randomUUID(), radioId = radio, channelIndex = 1u, text = text,
            timestamp = (1_700_000_000 + offset).toUInt(),
            createdAt = Instant.ofEpochSecond(1_700_000_000L + offset),
            direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
            senderNodeName = "Alice", senderKeyPrefix = Bytes.of(0xAA, 1), pathLength = 0u, isRead = true,
        )

        suspend fun mount(direct: ContactDTO? = null, fontScale: Float = 1f) {
            if (direct != null) {
                h.container.navigation.navigateToChat(direct)
            } else {
                val channel = ChannelDTO(radioId = radio, index = 1u, name = "Avatar channel")
                store.saveChannel(channel)
                h.container.navigation.navigateToChannel(channel)
            }
            val destination = h.container.navigation.state.value.activeStack.last().destination
            compose.runOnUiThread {
                host.get().setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        MeshCoreTheme(motionScale = 0f) {
                            AppChatsContent(h.container.chatsFeature, destination, h.container.navigation)
                        }
                    }
                }
            }
            h.settle()
            compose.waitForIdle()
        }

        fun awaitBubble(message: MessageDTO) = compose.waitUntil(30_000) {
            h.settle()
            compose.onAllNodesWithTag(bubbleTag(message), useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }

        fun awaitAvatar(message: MessageDTO) = compose.waitUntil(30_000) {
            h.settle()
            compose.onAllNodesWithTag(avatarTag(message), useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }

        fun awaitColor(message: MessageDTO, expected: Int) = compose.waitUntil(30_000) {
            h.settle()
            avatarColor(message) == expected
        }

        fun awaitInitials(message: MessageDTO) = compose.waitUntil(30_000) {
            h.settle()
            val image = avatarImage(message)
            if (image == null) {
                false
            } else {
                val pixels = image.toPixelMap()
                val innerColors = buildSet {
                    for (x in image.width / 4 until image.width * 3 / 4) {
                        for (y in image.height / 4 until image.height * 3 / 4) add(pixels[x, y].toArgb())
                    }
                }
                innerColors.size > 1 &&
                    pixels[image.width / 2, image.height / 2].toArgb() !in setOf(Color.RED, Color.GREEN)
            }
        }

        private fun avatarImage(message: MessageDTO): androidx.compose.ui.graphics.ImageBitmap? {
            if (compose.onAllNodesWithTag(avatarTag(message), useUnmergedTree = true).fetchSemanticsNodes().size != 1) {
                return null
            }
            return compose.onNodeWithTag(avatarTag(message), useUnmergedTree = true).captureToImage()
        }

        private fun avatarColor(message: MessageDTO): Int? = avatarImage(message)?.let { image ->
            image.toPixelMap()[image.width / 2, image.height / 2].toArgb()
        }

        fun assertNoSendsOrHttp() {
            assertTrue(httpRequests.isEmpty())
            assertEquals(0, h.links.radios.single().sent.count { it[0].toInt() == 2 })
        }

        suspend fun close() {
            try { if (this::h.isInitialized) h.container.close() } finally {
                if (this::content.isInitialized) content.close()
                job.cancelAndJoin()
                storage.close()
            }
        }
    }

    private fun bubble(message: MessageDTO) = compose.onNodeWithTag(bubbleTag(message), useUnmergedTree = true)
    private fun bubbleTag(message: MessageDTO) = "timeline-bubble:${message.id}"
    private fun avatarTag(message: MessageDTO) = "timeline-avatar:${message.id}"
    private fun spacerTag(message: MessageDTO) = "timeline-avatar-spacer:${message.id}"

    private fun png(color: Int): Bytes {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            Bytes(ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            })
        } finally { bitmap.recycle() }
    }
}
