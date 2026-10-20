// AndroidOnly: WP-502 Mounted native single-initialization draft witnesses; original-case equivalence requires primary-owner review.
// ReferenceInput: MC1Tests/Views/Chats/ChatViewModelDraftRestoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/ViewModel/ChatViewModel+Messages.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.platform

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as Chats
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "w1080dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DraftInitializationComposeTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun inProgressFieldSurvivesRealFailureReloadAndAdaptiveRecompositionWithIndependentSavedDraft() = runTest {
        populatedDraftSurvives(this, "in-progress")
    }

    @Test fun prepopulatedQuickReplyFieldSurvivesRealFailureReloadAndAdaptiveRecompositionWithIndependentSavedDraft() = runTest {
        populatedDraftSurvives(this, "quick-reply")
    }

    private suspend fun populatedDraftSurvives(test: TestScope, populated: String) {
        val h = ContainerHarness(test, store)
        val width = mutableStateOf(360)
        val peerKey = Bytes(ByteArray(32) { 4 })
        val otherKey = Bytes(ByteArray(32) { 5 })
        h.links.configureRadio = { firmware ->
            firmware.knownContacts += contactFrame(peerKey, "Draft peer")
            firmware.knownContacts += contactFrame(otherKey, "Other draft peer")
            firmware.respond = { command -> if (command[0].toInt() == 2) emptyList() else null }
        }
        try {
            h.manager.connect(h.target(UUID.fromString("50200000-0000-0000-0000-000000000307")))
            h.settle()
            h.assertReady()
            val radio = requireNotNull(h.appState.currentRadioId)
            val peer = requireNotNull(store.fetchContact(radio, peerKey))
            val other = requireNotNull(store.fetchContact(radio, otherKey))
            val conversation = ChatConversationID.dm(radio, peer.id)
            val otherConversation = ChatConversationID.dm(radio, other.id)
            val otherRadioConversation = ChatConversationID.dm(
                RadioId(UUID.fromString("50200000-0000-0000-0000-000000000308")), peer.id,
            )
            h.draftStore.setDraft("saved", conversation)
            h.draftStore.setDraft("other-contact", otherConversation)
            h.draftStore.setDraft("other-radio", otherRadioConversation)
            compose.runOnUiThread {
                h.container.navigation.navigateToChat(peer)
                compose.activity.setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                            Box(Modifier.width(width.value.dp).fillMaxHeight()) {
                                NativeNavigationShell(h.container.navigation, chats = h.container.chatsFeature)
                            }
                        }
                    }
                }
            }
            h.settle()
            compose.waitForIdle()
            val matcher = hasSetTextAction() and hasContentDescription(
                compose.activity.getString(Chats.chatsInputAccessibilityLabel), substring = true,
            )
            val field = compose.onNode(matcher)
            field.assertIsDisplayed().assertTextContains("saved")
            val entry = h.container.navigation.state.value.activeStack.last()
            val generation = requireNotNull(h.sessions.current).token
            compose.onNodeWithContentDescription(compose.activity.getString(Chats.chatsInputSendMessage))
                .assertIsEnabled().performClick()
            h.eventually("one actual durable direct command is held by firmware", virtualBudgetMillis = 100) {
                h.links.radios.single().sent.count { it[0].toInt() == 2 } == 1 && runBlocking {
                    store.fetchMessages(EntityKey(radio, peer.id), 10, 0).singleOrNull()?.text == "saved"
                }
            }
            field.performClick().performTextReplacement(populated)
            field.assertIsFocused().assertTextContains(populated)
            assertEquals(populated, h.draftStore.draft(conversation))
            // The saved value is changed independently AFTER typing, before the actual service reload.
            h.draftStore.setDraft("disk", conversation)
            assertEquals("disk", h.container.chatsFeature.drafts.get(conversation))
            field.assertTextContains(populated)
            val sending = compose.activity.getString(Chats.chatsMessageStatusSending)
            h.eventually("the actual mounted timeline shows the held message before its terminal reload", virtualBudgetMillis = 100) {
                compose.onAllNodesWithContentDescription("saved. $sending").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithContentDescription("saved. $sending").assertIsDisplayed()
            val fieldIdentity = field.fetchSemanticsNode().id
            val message = store.fetchMessages(EntityKey(radio, peer.id), 10, 0).single()
            assertEquals("saved", message.text)
            assertTrue(message.status in setOf(MessageStatus.PENDING, MessageStatus.SENDING))
            assertEquals(1, store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).size)
            h.links.radios.single().push(Bytes.of(1, 2))
            h.eventually("the real queue persists the terminal failure and removes its envelope") {
                runBlocking {
                    store.fetchMessage(EntityKey(radio, message.id))?.status == MessageStatus.FAILED &&
                        store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).isEmpty()
                }
            }
            h.settle()
            val failed = compose.activity.getString(Chats.chatsMessageStatusFailed)
            compose.waitUntil(30_000) {
                h.settle()
                compose.onAllNodesWithContentDescription("saved. $failed").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithContentDescription("saved. $failed").assertIsDisplayed()
            for (value in listOf(360, 599, 600, 779, 780, 834, 360)) {
                compose.runOnIdle { width.value = value; h.appState.refreshConversations() }
                h.settle()
                compose.waitForIdle()
                compose.onNodeWithTag(if (value < 780) "single-pane" else "list-detail").assertIsDisplayed()
                field.assertIsDisplayed().assertIsFocused().assertTextContains(populated)
                assertEquals("The actual field lifetime must survive width$value", fieldIdentity, field.fetchSemanticsNode().id)
                assertEquals("disk", h.draftStore.draft(conversation))
                assertEquals("other-contact", h.draftStore.draft(otherConversation))
                assertEquals("other-radio", h.draftStore.draft(otherRadioConversation))
                assertEquals(entry, h.container.navigation.state.value.activeStack.last())
                assertEquals(generation, requireNotNull(h.sessions.current).token)
            }
            compose.runOnUiThread { h.container.navigation.navigateToChat(other) }
            h.settle()
            compose.waitForIdle()
            val replacement = compose.onNode(matcher).assertIsDisplayed().assertTextContains("other-contact")
            assertNotEquals(fieldIdentity, replacement.fetchSemanticsNode().id)
            assertEquals("disk", h.draftStore.draft(conversation))
            assertEquals("other-radio", h.draftStore.draft(otherRadioConversation))
            assertEquals(1, h.lifecycle.created.size)
            assertEquals(1, h.links.radios.size)
            assertEquals(1, h.links.radios.single().sent.count { it[0].toInt() == 2 })
            assertEquals(0, h.links.radios.single().sent.count { it[0].toInt() == 3 })
            assertEquals(0, requireNotNull(h.sessions.current).chatSendQueueService.queuedEnvelopeCount)
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            h.container.close()
        }
    }
}
