// AndroidOnly: WP-502 Cancel/reopen the actual App reaction child sheet over Room/current services and a deterministic radio.
// ReferenceInput: MC1/Views/Chats/Reactions/Sections/ActionsEmojiSection.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Reactions/EmojiPickerSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.platform

import android.content.Context
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.content.DataStoreLinkPreviewPreferencesSource
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as C
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@Config(sdk = [31], qualifiers = "w360dp-h800dp")
class ReactionPickerCancellationComposeTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cancellingTheFullReactionPickerRetainsItsParentAndReopeningSendsOnlyTheFreshSelection() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        val contentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val heart = "\u2764\uFE0F"
        val thumb = "\uD83D\uDC4D"
        val rocket = "\uD83D\uDE80"
        val recentBefore = listOf(heart, thumb)
        val frequentBefore = Bytes.utf8("[\"$thumb\",\"$heart\"]")
        storage.preferences.set(AppStorageKey.linkPreviewsEnabled, false)
        storage.preferences.set(AppStorageKey.recentReactionEmojis, recentBefore.snapshot())
        storage.preferences.set(AppStorageKey.frequentEmojis, frequentBefore)
        val recentStates = Collections.synchronizedList(mutableListOf<List<String>>())
        val frequentStates = Collections.synchronizedList(mutableListOf<Bytes>())
        contentScope.launch(start = CoroutineStart.UNDISPATCHED) {
            storage.preferences.observe(AppStorageKey.recentReactionEmojis).collect { recentStates += it.toList() }
        }
        contentScope.launch(start = CoroutineStart.UNDISPATCHED) {
            storage.preferences.observe(AppStorageKey.frequentEmojis).collect { frequentStates += it }
        }
        val http = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt =
                error("The offline reaction cancellation flow must not fetch network content")
        }
        val content = AppChatContent(
            context, store, http, DataStoreLinkPreviewPreferencesSource.create(storage.preferences, contentScope),
            storage.preferences, storage.preferences.snapshot(), contentScope,
        )
        val h = ContainerHarness(this, store, chatContent = content)
        h.links.configureRadio = { fake ->
            fake.respond = { frame ->
                if ((frame[0].toInt() and 255) == 0x02) sentAndDelivered(frame, key(7)) else null
            }
        }
        try {
            h.manager.connect(h.target(UUID.fromString("50200000-0000-0000-0000-000000000098")))
            h.settle()
            h.assertReady()
            val radio = requireNotNull(h.appState.currentRadioId)
            val token = requireNotNull(h.sessions.current).token
            val contact = ContactDTO(
                radioId = radio, publicKey = Bytes(ByteArray(32) { 4 }),
                name = "Synthetic reaction peer", lastHeardTimestamp = null,
            )
            val message = MessageDTO(
                radioId = radio, contactID = contact.id, text = "Synthetic reactable message",
                timestamp = 1u, direction = MessageDirection.INCOMING,
            )
            store.saveContact(contact)
            store.saveMessage(message)
            val conversation = EntityKey(radio, contact.id)
            val target = EntityKey(radio, message.id)
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
            val navigationEntry = h.container.navigation.state.value.activeStack.last()
            compose.onNodeWithText(context.getString(C.chatsMessageActionDetails)).performClick()
            val parentDialog = compose.runOnIdle {
                requireNotNull(ShadowDialog.getLatestDialog()).also { assertTrue(it.isShowing) }
            }
            val more = context.getString(C.reactionsMoreEmojis)
            val search = hasSetTextAction() and hasAnyAncestor(isDialog())
            compose.onNodeWithText(more).performClick()
            compose.onNode(search).performTextInput("grinning")
            compose.waitUntil(30_000) {
                compose.onAllNodesWithContentDescription("grinning face").fetchSemanticsNodes().size == 1 &&
                    recentStates.isNotEmpty() && frequentStates.isNotEmpty()
            }
            val original = store.fetchMessages(conversation, 10, 0).single()
            val originalReactions = store.fetchReactions(target)
            fun sends() = h.links.radios.single().sent.filter { (it[0].toInt() and 255) in setOf(0x02, 0x03) }
            suspend fun assertNoSelection() {
                h.settle()
                compose.waitForIdle()
                assertEquals(listOf(original), store.fetchMessages(conversation, 10, 0))
                assertEquals(originalReactions, store.fetchReactions(target))
                assertTrue(store.fetchPendingSends(radio).isEmpty())
                assertTrue(sends().isEmpty())
                assertEquals(recentBefore, storage.preferences.get(AppStorageKey.recentReactionEmojis))
                assertEquals(frequentBefore, storage.preferences.get(AppStorageKey.frequentEmojis))
                assertEquals(listOf(recentBefore), recentStates.toList())
                assertEquals(listOf(frequentBefore), frequentStates.toList())
                assertEquals(token, requireNotNull(h.sessions.current).token)
                assertEquals(navigationEntry, h.container.navigation.state.value.activeStack.last())
                assertTrue(h.messagingDiagnostics.toString(), h.messagingDiagnostics.isEmpty())
            }
            assertNoSelection()
            val childDialog = compose.runOnIdle {
                requireNotNull(ShadowDialog.getLatestDialog()).also {
                    assertNotSame(parentDialog, it)
                    assertTrue(it.isShowing)
                }
            }
            // Material Back can first collapse an expanded sheet to its partial detent.
            repeat(2) {
                compose.runOnIdle {
                    if (childDialog.isShowing) {
                        childDialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                        childDialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                    }
                }
                compose.waitForIdle()
            }
            compose.runOnIdle {
                assertNotSame(parentDialog, childDialog)
                assertFalse(childDialog.isShowing)
                assertTrue(parentDialog.isShowing)
            }
            compose.onNode(search).assertDoesNotExist()
            compose.onNodeWithText(more).assertIsDisplayed().assertHasClickAction()
            assertNoSelection()

            compose.onNodeWithText(more).performClick()
            compose.onNode(search).assertTextContains("grinning").performTextReplacement("rocket")
            compose.waitUntil(30_000) {
                compose.onAllNodesWithContentDescription("rocket").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithContentDescription("grinning face").assertDoesNotExist()
            assertNoSelection()
            compose.onNodeWithContentDescription("rocket").assertIsDisplayed().performClick()
            val recentAfter = listOf(rocket, heart, thumb)
            val frequentAfter = Bytes.utf8("[\"$rocket\",\"$thumb\",\"$heart\"]")
            // Independent SHA-256(first five bytes)/Crockford hash of the literal target text and UInt32LE(1).
            val wireText = "$rocket\nd11q0688"
            compose.waitUntil(30_000) {
                h.settle()
                runBlocking {
                    store.fetchMessages(conversation, 10, 0).any {
                        it.text == wireText && it.status == MessageStatus.DELIVERED
                    } && storage.preferences.get(AppStorageKey.recentReactionEmojis) == recentAfter &&
                        storage.preferences.get(AppStorageKey.frequentEmojis) == frequentAfter
                } && recentStates.size == 2 && frequentStates.size == 2
            }
            compose.onNodeWithText(more).assertDoesNotExist()
            compose.onNode(search).assertDoesNotExist()
            h.settle()
            compose.waitForIdle()
            val messages = store.fetchMessages(conversation, 10, 0)
            assertEquals(2, messages.size)
            assertEquals(original, messages.single { it.id == message.id })
            val reaction = messages.single { it.id != message.id }
            assertEquals(wireText, reaction.text)
            assertEquals(MessageDirection.OUTGOING, reaction.direction)
            assertEquals(MessageStatus.DELIVERED, reaction.status)
            assertEquals(radio, reaction.radioId)
            assertEquals(contact.id, reaction.contactID)
            val frame = sends().single()
            assertEquals(0x02, frame[0].toInt() and 255)
            assertEquals(wireText, frame.toByteArray().copyOfRange(13, frame.size).toString(Charsets.UTF_8))
            assertEquals(listOf(recentBefore, recentAfter), recentStates.toList())
            assertEquals(listOf(frequentBefore, frequentAfter), frequentStates.toList())
            assertEquals(recentAfter, storage.preferences.get(AppStorageKey.recentReactionEmojis))
            assertEquals(frequentAfter, storage.preferences.get(AppStorageKey.frequentEmojis))
            assertTrue(store.fetchPendingSends(radio).isEmpty())
            assertEquals(token, requireNotNull(h.sessions.current).token)
            assertEquals(navigationEntry, h.container.navigation.state.value.activeStack.last())
            assertEquals(1, h.lifecycle.created.size)
            assertTrue(h.messagingDiagnostics.toString(), h.messagingDiagnostics.isEmpty())
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            h.container.close()
            content.close()
            contentScope.cancel()
            storage.close()
        }
    }
}
