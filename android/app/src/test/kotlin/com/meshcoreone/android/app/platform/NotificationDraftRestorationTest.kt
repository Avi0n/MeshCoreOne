// AndroidOnly: WP-307 Real App consumer witnesses for pending notification reply restoration and ownership.
// ReferenceInput: MC1/Views/Chats/ViewModel/ChatViewModel+Messages.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/ChatConversationView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.platform

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "w1080dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationDraftRestorationTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun pendingNotificationReplyWinsSavedDraftOnceAndSurvivesAdaptiveRecomposition() = runTest {
        mountedDrafts(this, pending = "failed reply", expected = "failed reply", exerciseContinuity = true)
    }

    @Test fun absentNotificationReplyRestoresOrdinarySavedDraft() = runTest {
        mountedDrafts(this, pending = null, expected = "disk", exerciseContinuity = false)
    }

    @Test fun emptyNotificationReplyFallsBackToSavedDraft() = runTest {
        mountedDrafts(this, pending = "", expected = "disk", exerciseContinuity = false)
    }

    private suspend fun mountedDrafts(
        test: TestScope,
        pending: String?,
        expected: String,
        exerciseContinuity: Boolean,
    ) {
        val h = ContainerHarness(test, store)
        val width = mutableStateOf(360)
        val peerKey = Bytes(ByteArray(32) { 4 })
        val otherKey = Bytes(ByteArray(32) { 5 })
        h.links.configureRadio = { firmware ->
            firmware.knownContacts += contactFrame(peerKey, "Reply peer")
            firmware.knownContacts += contactFrame(otherKey, "Other reply peer")
        }
        try {
            h.manager.connect(h.target(UUID.fromString("30700000-0000-0000-0000-000000000001")))
            h.settle()
            h.assertReady()
            val session = requireNotNull(h.sessions.current)
            val notifications = session.notificationService
            val radio = session.token.radioId
            val peer = requireNotNull(store.fetchContact(radio, peerKey))
            val other = requireNotNull(store.fetchContact(radio, otherKey))
            val conversation = ChatConversationID.dm(radio, peer.id)
            val otherConversation = ChatConversationID.dm(radio, other.id)
            val peerEntity = EntityKey(radio, peer.id)
            val otherEntity = EntityKey(radio, other.id)
            val otherRadio = RadioId(UUID.fromString("30700000-0000-0000-0000-000000000002"))
            val foreignEntity = EntityKey(otherRadio, peer.id)
            h.draftStore.setDraft("disk", conversation)
            h.draftStore.setDraft("other disk", otherConversation)
            h.draftStore.setDraft("foreign disk", ChatConversationID.dm(otherRadio, peer.id))
            if (pending != null) notifications.saveDraft(peerEntity, pending)
            notifications.saveDraft(otherEntity, "other pending")
            notifications.saveDraft(foreignEntity, "foreign pending")
            compose.runOnUiThread {
                h.container.navigation.navigateToChat(peer)
                compose.activity.setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        Box(Modifier.width(width.value.dp).fillMaxHeight()) {
                            NativeNavigationShell(h.container.navigation, chats = h.container.chatsFeature)
                        }
                    }
                }
            }
            h.settle()
            compose.waitForIdle()
            val matcher = hasSetTextAction() and hasContentDescription(
                compose.activity.getString(AppChatsStrings.chatsInputAccessibilityLabel), substring = true,
            )
            val field = compose.onNode(matcher).assertIsDisplayed().assertTextEquals(expected)
            assertEquals(expected, h.draftStore.draft(conversation))
            assertNull("The selected pending reply must be consumed exactly once", notifications.consumeDraft(peerEntity))
            assertEquals("other disk", h.draftStore.draft(otherConversation))
            assertEquals("foreign disk", h.draftStore.draft(ChatConversationID.dm(otherRadio, peer.id)))

            if (exerciseContinuity) {
                field.performClick().performTextReplacement("in-progress")
                field.assertIsFocused().assertTextEquals("in-progress")
                val identity = field.fetchSemanticsNode().id
                val entry = h.container.navigation.state.value.activeStack.last()
                // A later failed reply belongs to the next initialization, not a recomposition.
                notifications.saveDraft(peerEntity, "next entry")
                for (value in listOf(360, 599, 600, 779, 780, 834, 360)) {
                    compose.runOnIdle { width.value = value; h.appState.refreshConversations() }
                    h.settle()
                    compose.waitForIdle()
                    compose.onNodeWithTag(if (value < 780) "single-pane" else "list-detail").assertIsDisplayed()
                    field.assertIsDisplayed().assertIsFocused().assertTextEquals("in-progress")
                    assertEquals(identity, field.fetchSemanticsNode().id)
                    assertEquals("in-progress", h.draftStore.draft(conversation))
                    assertEquals(entry, h.container.navigation.state.value.activeStack.last())
                    assertEquals(session.token, requireNotNull(h.sessions.current).token)
                }
            }

            compose.runOnUiThread { h.container.navigation.navigateToChat(other) }
            h.settle()
            compose.waitForIdle()
            compose.onNode(matcher).assertIsDisplayed().assertTextEquals("other pending")
            assertNull(notifications.consumeDraft(otherEntity))
            assertEquals("other pending", h.draftStore.draft(otherConversation))
            assertEquals("foreign pending", notifications.consumeDraft(foreignEntity))
            assertNull(notifications.consumeDraft(foreignEntity))
            compose.runOnUiThread { h.container.navigation.navigateToChat(peer) }
            h.settle()
            compose.waitForIdle()
            val restored = if (exerciseContinuity) "next entry" else expected
            compose.onNode(matcher).assertIsDisplayed().assertTextEquals(restored)
            assertEquals(restored, h.draftStore.draft(conversation))
            assertNull(notifications.consumeDraft(peerEntity))
            assertEquals(1, h.lifecycle.created.size)
            assertEquals(1, h.links.radios.size)
            assertEquals(0, h.links.radios.single().sent.count { it[0].toInt() in setOf(2, 3) })
            assertEquals(0, session.chatSendQueueService.queuedEnvelopeCount)
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            h.container.close()
        }
    }
}
