// AndroidOnly: WP-502 Drive the production composer through the real Room/service graph and a rejecting fake firmware.
// ReferenceInput: MeshCore/Sources/MeshCore/Protocol/ErrorCode.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/ViewModel/ChatViewModel+SendQueues.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.platform

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as C
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.uitests.ConversationScreenCases
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "w360dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectedConversationComposeTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun rejectedNativeDirectSendAndMountedRetryExposeEachPersistedTerminalFailure() = runTest {
        rejectedSendAndRetry(this, channel = false)
    }

    @Test fun rejectedNativeChannelSendAndMountedRetryExposeEachPersistedTerminalFailure() = runTest {
        rejectedSendAndRetry(this, channel = true)
    }

    private suspend fun rejectedSendAndRetry(test: TestScope, channel: Boolean) {
        val h = ContainerHarness(test, store)
        val contactKey = Bytes(ByteArray(32) { 4 })
        val opcode = if (channel) 3 else 2
        val failures = Collections.synchronizedList(mutableListOf<UUID>())
        val accepted = Collections.synchronizedList(mutableListOf<PendingSendDTO>())
        test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            h.appState.messageEventStream.events().collect { event ->
                if (event is MessageEvent.MessageFailed) failures += event.messageID
            }
        }
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(contactKey, "Synthetic mesh peer")
            radio.respond = { request ->
                if (request[0].toInt() == opcode) {
                    accepted += runBlocking { store.fetchPendingSends(requireNotNull(h.appState.currentRadioId)).single() }
                    // Channel notFound is retryable; pinned illegalArgument (6) is terminal.
                    listOf(Bytes.of(1, if (channel) 6 else 2))
                } else null
            }
        }
        try {
            h.manager.connect(h.target(UUID.fromString("50200000-0000-0000-0000-000000000099")))
            h.settle()
            h.assertReady()
            val radio = requireNotNull(h.appState.currentRadioId)
            val contact = requireNotNull(store.fetchContact(radio, contactKey))
            val channelDTO = ChannelDTO(radioId = radio, index = 0u, name = "Synthetic mesh channel")
            if (channel) store.saveChannel(channelDTO)
            suspend fun messages() = if (channel) store.fetchMessages(radio, channelDTO.index, 10, 0)
                else store.fetchMessages(EntityKey(radio, contact.id), 10, 0)
            compose.runOnUiThread {
                if (channel) h.container.navigation.navigateToChannel(channelDTO)
                else h.container.navigation.navigateToChat(contact)
                compose.activity.setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        NativeNavigationShell(h.container.navigation, chats = h.container.chatsFeature)
                    }
                }
            }
            h.settle()
            compose.waitForIdle()
            val original = ConversationScreenCases.originalText
            compose.onNode(
                hasSetTextAction() and hasContentDescription(
                    compose.activity.getString(C.chatsInputAccessibilityLabel), substring = true,
                ),
            ).assertIsDisplayed().performTextInput(original)
            val send = compose.onNodeWithContentDescription(compose.activity.getString(C.chatsInputSendMessage))
            send.assertIsEnabled().performClick()
            val failed = compose.activity.getString(C.chatsMessageStatusFailed)
            val retry = compose.activity.getString(C.chatsMessageStatusRetry)
            var messageID: UUID? = null
            repeat(2) { attempt ->
                h.eventually("mounted queue attempt ${attempt + 1} persists and broadcasts its terminal failure") {
                    failures.size == attempt + 1 && runBlocking {
                        messages().singleOrNull()?.let {
                            it.text == original && it.status == MessageStatus.FAILED &&
                                store.fetchPendingSendsForMessage(EntityKey(radio, it.id)).isEmpty()
                        } == true
                    }
                }
                val persisted = messages().single()
                if (attempt == 0) messageID = persisted.id else assertEquals(messageID, persisted.id)
                assertEquals(original, persisted.text)
                assertEquals(MessageStatus.FAILED, persisted.status)
                assertEquals(radio, persisted.radioId)
                val pending = accepted[attempt]
                assertEquals(persisted.id, pending.messageID)
                assertEquals(1L, pending.attemptCount)
                assertEquals(channel && attempt == 1, pending.isResend)
                if (channel) {
                    assertEquals(original, pending.messageText)
                    assertEquals(persisted.timestamp, pending.messageTimestamp)
                    assertEquals("TestNode", pending.localNodeName)
                }
                h.settle()
                compose.waitForIdle()
                compose.waitUntil(30_000) {
                    h.settle()
                    compose.onAllNodesWithContentDescription("$original. $failed").fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithContentDescription("$original. $failed").assertIsDisplayed()
                compose.onNodeWithText(retry).assertIsDisplayed()
                assertNull(h.draftStore.draft(if (channel) ChatConversationID.channel(radio, channelDTO.index)
                    else ChatConversationID.dm(radio, contact.id)))
                if (attempt == 0) compose.onNodeWithText(retry).performClick()
            }
            assertEquals(listOf(messageID, messageID), failures.toList())
            assertEquals(2, accepted.size)
            assertEquals(1, h.lifecycle.created.size)
            assertEquals(1, h.links.radios.size)
            assertEquals(2, h.links.radios.single().sent.count { it[0].toInt() == opcode })
            assertEquals(0, requireNotNull(h.sessions.current).chatSendQueueService.queuedEnvelopeCount)
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            h.container.close()
        }
    }
}
