// AndroidOnly: WP-502 Drive the production composer through the real Room/service graph and a rejecting fake firmware.
// ReferenceInput: MeshCore/Sources/MeshCore/Protocol/ErrorCode.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.platform

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as C
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.uitests.ConversationScreenCases
import java.util.UUID
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

    @Test fun rejectedNativeSendPersistsOriginalTextAndVisibleFailureWithoutAnotherConnectionOwner() = runTest {
        val h = ContainerHarness(this, store)
        val contactKey = Bytes(ByteArray(32) { 4 })
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(contactKey, "Synthetic mesh peer")
            // Independent reference bytes: RESP_CODE_ERR (1), ErrorCode.notFound (2).
            radio.respond = { request -> if (request[0].toInt() == 2) listOf(Bytes.of(1, 2)) else null }
        }
        try {
            h.manager.connect(h.target(UUID.fromString("50200000-0000-0000-0000-000000000099")))
            h.settle()
            h.assertReady()
            val radio = requireNotNull(h.appState.currentRadioId)
            val contact = requireNotNull(store.fetchContact(radio, contactKey))
            compose.runOnUiThread {
                h.container.navigation.navigateToChat(contact)
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
            h.eventually("native send reaches the real service and persists its failed row") {
                kotlinx.coroutines.runBlocking {
                    store.fetchMessages(EntityKey(radio, contact.id), 10, 0).any {
                        it.text == original && it.status == MessageStatus.FAILED
                    }
                }
            }
            val persisted = store.fetchMessages(EntityKey(radio, contact.id), 10, 0).single()
            assertEquals(original, persisted.text)
            assertEquals(MessageStatus.FAILED, persisted.status)
            h.settle()
            compose.waitForIdle()
            val failed = compose.activity.getString(C.chatsMessageStatusFailed)
            compose.onNodeWithContentDescription("$original. $failed").assertIsDisplayed()
            assertNull(h.draftStore.draft(ChatConversationID.dm(radio, contact.id)))
            compose.onNodeWithText(compose.activity.getString(C.chatsMessageStatusRetry)).assertIsDisplayed()
            assertEquals(1, h.lifecycle.created.size)
            assertEquals(1, h.links.radios.size)
            assertTrue(h.links.radios.single().sent.any { it[0].toInt() == 2 })
            assertEquals(radio, persisted.radioId)
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            h.container.close()
        }
    }
}
