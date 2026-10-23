// AndroidOnly: WP-407 A full-catalog reaction traverses actual app adapters/Room/message services over a deterministic test radio.
package com.meshcoreone.android.app.platform

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.content.DataStoreLinkPreviewPreferencesSource
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.datastore.*
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as C
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.content.*
import com.meshcoreone.android.core.services.reactions.ReactionParser
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [31], qualifiers = "w360dp-h800dp")
class EmojiReactionRegistrationTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun choosingFromTheFullReactionPickerSendsThroughTheCurrentRadioAndDismissesBothSheets() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        val contentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val http = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt =
                error("The offline emoji flow must not fetch network content")
        }
        storage.preferences.set(AppStorageKey.linkPreviewsEnabled, false)
        val content = AppChatContent(context, store, http,
            DataStoreLinkPreviewPreferencesSource.create(storage.preferences, contentScope),
            storage.preferences, storage.preferences.snapshot(), contentScope)
        val h = ContainerHarness(this, store, chatContent = content)
        h.links.configureRadio = { fake ->
            fake.respond = { frame ->
                if ((frame[0].toInt() and 255) == 0x02) sentAndDelivered(frame, key(7)) else null
            }
        }
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val radio = requireNotNull(h.appState.currentRadioId)
            val contact = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 4 }),
                name = "Synthetic reaction peer", lastHeardTimestamp = null)
            val message = MessageDTO(radioId = radio, contactID = contact.id, text = "Synthetic reactable message",
                timestamp = 1u, direction = MessageDirection.INCOMING)
            store.saveContact(contact)
            store.saveMessage(message)
            compose.runOnUiThread {
                h.container.navigation.navigateToChat(contact)
                compose.activity.setContent { MeshCoreTheme(motionScale = 0f) {
                    NativeNavigationShell(h.container.navigation, chats = h.container.chatsFeature)
                } }
            }
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(C.chatsMessageActionDetails)).performClick()
            compose.onNodeWithText(context.getString(C.reactionsMoreEmojis)).performClick()
            compose.onNodeWithText(context.getString(C.reactionsEmojiSearchPlaceholder)).performTextInput("grinning")
            compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("grinning face").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("grinning face").performClick()
            compose.waitUntil(30_000) {
                h.settle()
                runBlocking { store.fetchMessages(EntityKey(radio, contact.id), 10, 0).any {
                    ReactionParser.parseDM(it.text)?.emoji == "😀" && it.status == MessageStatus.DELIVERED
                } }
            }
            val reaction = store.fetchMessages(EntityKey(radio, contact.id), 10, 0).single { ReactionParser.parseDM(it.text)?.emoji == "😀" }
            assertEquals(radio, reaction.radioId)
            assertEquals(contact.id, reaction.contactID)
            val sent = h.links.radios.single().sent.filter { (it[0].toInt() and 255) == 0x02 }
            assertTrue(sent.isNotEmpty())
            assertTrue(sent.all { it.toByteArray().copyOfRange(13, it.size).toString(Charsets.UTF_8) == reaction.text })
            compose.waitUntil(30_000) {
                h.settle()
                compose.waitForIdle()
                assertTrue(h.messagingDiagnostics.toString(), h.messagingDiagnostics.isEmpty())
                runBlocking { storage.preferences.get(AppStorageKey.recentReactionEmojis).firstOrNull() == "😀" } &&
                    compose.onAllNodesWithText(context.getString(C.reactionsMoreEmojis)).fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText(context.getString(C.reactionsEmojiSearchPlaceholder)).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText(context.getString(C.reactionsMoreEmojis)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(C.reactionsEmojiSearchPlaceholder)).assertDoesNotExist()
            assertTrue(h.messagingDiagnostics.isEmpty())
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            h.container.close()
            content.close()
            contentScope.cancel()
            storage.close()
        }
    }
}
