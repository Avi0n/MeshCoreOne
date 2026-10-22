// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomConversationView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomMessageActionsSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/SendDMSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.rooms

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as Chats
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.composer.MentionUtilities
import com.meshcoreone.android.feature.chats.composer.ui.ConversationLinkActions
import com.meshcoreone.android.feature.chats.rooms.*
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoomMessageActionsComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val radio = RadioId(UUID(0, 310))
    private val session = RemoteNodeSessionDTO(
        radioId = radio, publicKey = Bytes(ByteArray(32) { 1 }), name = "Room",
        role = RemoteNodeRole.ROOM_SERVER, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE,
    )
    private val original = RoomMessageDTO(
        sessionID = session.id, authorKeyPrefix = Bytes.of(2), authorName = "Alice",
        text = "@[Me] original 日本語 👩‍🚀 e\u0301", timestamp = 100u,
    )
    private val alice = ContactDTO(
        radioId = radio, publicKey = Bytes(ByteArray(32) { 2 }), name = "aLiCe",
        nickname = "Verified Alice", lastHeardTimestamp = null,
    )
    private val sent = mutableListOf<String>()
    private val navigated = mutableListOf<ContactDTO>()
    private var postFailure: Exception? = null
    private lateinit var holder: RoomConversationStateHolder

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }

    @After fun destroyHost() {
        scope.cancel()
        host.pause().stop().destroy()
    }

    private fun text(id: Int) = host.get().getString(id)

    private fun show(
        message: RoomMessageDTO = original,
        room: RemoteNodeSessionDTO = session,
        quote: Boolean = false,
        contacts: List<ContactDTO> = listOf(alice),
    ) {
        val stored = mutableListOf(message)
        val dependencies = object : RoomConversationDependencies {
            override val events = emptyFlow<RoomConversationEvent>()
            override val data = object : RoomConversationDataSource {
                override suspend fun fetchMessages(session: RemoteNodeSessionDTO) = stored.toList()
                override suspend fun refreshSession(session: RemoteNodeSessionDTO) = room
                override suspend fun contactFor(session: RemoteNodeSessionDTO): ContactDTO? = null
                override suspend fun markAsRead(session: RemoteNodeSessionDTO) = Unit
                override suspend fun markFailedSendsSeen(session: RemoteNodeSessionDTO) = Unit
            }
            override val service = object : RoomConversationService {
                override suspend fun join(contact: ContactDTO, password: String?, rememberPassword: Boolean) = room
                override suspend fun reconnect(session: RemoteNodeSessionDTO) = room
                override suspend fun post(session: RemoteNodeSessionDTO, text: String): RoomMessageDTO {
                    postFailure?.let { throw it }
                    sent += text
                    return message.copy(id = UUID.randomUUID(), text = text, isFromSelf = true).also(stored::add)
                }
                override suspend fun retry(session: RemoteNodeSessionDTO, messageId: UUID) =
                    error("Send Again must not invoke Retry")
            }
        }
        holder = RoomConversationStateHolder(room, dependencies, scope)
        val links = ConversationLinkActions(
            contacts = { contacts }, connectedDeviceName = { "Me" }, radioId = { radio },
            route = { false }, openExternal = { error("Unexpected external link") },
            navigateToContact = { error("Send DM must navigate to chat, not contact detail") },
        )
        host.get().setContent {
            MeshCoreTheme(theme = ThemeRegistry.default, systemDark = false, motionScale = 0f) {
                Box(Modifier.width(390.dp).height(1000.dp)) {
                    RoomConversationRoute(holder, links, replyWithQuote = quote, onSendDirectMessage = navigated::add)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun openActions() {
        val label = if (holder.state.value.messages.first().isFromSelf) {
            "${original.text}, ${text(com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings.remoteNodesRoomMessageStatusDeliveredLabel)}"
        } else "Alice: ${original.text}"
        val details = text(Chats.chatsMessageActionDetails)
        val action = compose.onNodeWithContentDescription(label).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == details }
        compose.runOnIdle { assertTrue(action.action()) }
        compose.waitForIdle()
    }

    private fun perform(id: Int) {
        compose.onNodeWithText(text(id)).performClick()
        compose.waitForIdle()
    }

    @Test fun copyUsesOriginalTextAndDismissesWithoutSending() {
        show()
        compose.onNodeWithTag("room-bubble:${original.id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText(text(Chats.chatsMessageActionTranslate)).assertDoesNotExist()
        perform(Chats.chatsMessageActionCopy)

        val clipboard = host.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(original.text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(sent.isEmpty())
        compose.onNodeWithText(text(Chats.chatsMessageActionCopy)).assertDoesNotExist()
    }

    @Test fun mentionAppendsToDraftAndFocusesComposerWithoutSending() {
        show()
        compose.runOnIdle { holder.updateDraft("draft") }
        openActions()
        compose.onNodeWithText(text(Chats.chatsMessageActionReply)).assertDoesNotExist()
        perform(Chats.chatsMessageActionMention)

        assertEquals(MentionUtilities.appendMention("Alice", "draft"), holder.state.value.draft)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertTrue(sent.isEmpty())
    }

    @Test fun quoteReplacesDraftUsingSharedWireTextAndSendPostsExactlyOnce() {
        show(quote = true)
        compose.runOnIdle { holder.updateDraft("replace me") }
        openActions()
        compose.onNodeWithText(text(Chats.chatsMessageActionMention)).assertDoesNotExist()
        perform(Chats.chatsMessageActionReply)
        val expected = MentionUtilities.buildReplyText("Alice", original.text)
        assertEquals(expected, holder.state.value.draft)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        perform(Chats.chatsInputSendMessage)

        assertEquals(listOf(expected), sent)
        assertEquals("", holder.state.value.draft)
    }

    @Test fun guestCanCopyAndSelectDMButCannotReplyOrSendAgain() {
        show(room = session.copy(permissionLevel = RoomPermissionLevel.GUEST))
        openActions()
        compose.onNodeWithText(text(Chats.chatsMessageActionReply)).assertDoesNotExist()
        compose.onNodeWithText(text(Chats.chatsMessageActionMention)).assertDoesNotExist()
        compose.onNodeWithText(text(Chats.chatsMessageActionSendAgain)).assertDoesNotExist()
        compose.onNodeWithText(text(Chats.chatsMessageActionCopy)).assertExists()
        perform(Chats.chatsMessageActionSendDM)
        compose.onNodeWithText(alice.publicKey.hexString).performClick()
        assertEquals(listOf(alice), navigated)
        assertTrue(sent.isEmpty())
    }

    @Test fun sendDMExcludesBlockedOtherRadioAndNonmatchingContactsAndUsesSelectedStableIdentity() {
        val second = alice.copy(id = UUID.randomUUID(), publicKey = Bytes(ByteArray(32) { 3 }), nickname = "Second Alice")
        val blocked = alice.copy(id = UUID.randomUUID(), publicKey = Bytes(ByteArray(32) { 4 }), isBlocked = true)
        val otherRadio = alice.copy(id = UUID.randomUUID(), radioId = RadioId(UUID(0, 999)), publicKey = Bytes.of(5))
        val otherName = alice.copy(id = UUID.randomUUID(), name = "Bob", publicKey = Bytes.of(6))
        show(contacts = listOf(alice, second, blocked, otherRadio, otherName))
        openActions()
        perform(Chats.chatsMessageActionSendDM)
        for (excluded in listOf(blocked, otherRadio, otherName)) {
            compose.onNodeWithText(excluded.publicKey.hexString).assertDoesNotExist()
        }
        compose.onNodeWithText(alice.publicKey.hexString).assertExists()
        compose.onNodeWithText(second.publicKey.hexString).performClick()
        assertEquals(listOf(second), navigated)
        assertTrue(sent.isEmpty())
    }

    @Test fun unmatchedDMSenderHasLocalizedEmptyStateAndCancelDoesNotNavigate() {
        show(contacts = emptyList())
        openActions()
        perform(Chats.chatsMessageActionSendDM)
        compose.onNodeWithText(Chats.chatsSendDMNoMatches(host.get().resources, "Alice")).assertExists()
        perform(Chats.chatsSendDMCancel)
        assertTrue(navigated.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test fun outgoingSendAgainPostsOriginalNotDraftAndDoesNotUseRetry() {
        show(message = original.copy(isFromSelf = true))
        compose.runOnIdle { holder.updateDraft("unrelated draft") }
        openActions()
        compose.onNodeWithText(text(Chats.chatsMessageActionMention)).assertDoesNotExist()
        compose.onNodeWithText(text(Chats.chatsMessageActionSendDM)).assertDoesNotExist()
        perform(Chats.chatsMessageActionSendAgain)

        assertEquals(listOf(original.text), sent)
        assertEquals("unrelated draft", holder.state.value.draft)
    }

    @Test fun disconnectedSendAgainSurfacesRealServiceFailureWithoutLosingDraft() {
        val failure = IllegalStateException("Disconnected fixture")
        postFailure = failure
        show(message = original.copy(isFromSelf = true), room = session.copy(isConnected = false))
        compose.runOnIdle { holder.updateDraft("keep draft") }
        openActions()
        perform(Chats.chatsMessageActionSendAgain)

        assertSame(failure, holder.state.value.failure)
        compose.onNodeWithText("Disconnected fixture").assertExists()
        assertEquals("keep draft", holder.state.value.draft)
        assertTrue(sent.isEmpty())
    }

    @Test
    @Config(qualifiers = "zh-rCN-w1080dp-h1000dp-mdpi")
    fun chineseRouteUsesLocalizedRetainedActionsWithNoTranslationEntry() {
        show()
        openActions()
        assertNotEquals("Copy", text(Chats.chatsMessageActionCopy))
        compose.onNodeWithText(text(Chats.chatsMessageActionTranslate)).assertDoesNotExist()
        compose.onNodeWithText(text(Chats.chatsMessageActionMention)).assertExists()
        compose.onNodeWithText(text(Chats.chatsMessageActionSendDM)).assertExists()
        perform(Chats.chatsMessageActionCopy)
        val clipboard = host.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(original.text, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }
}
