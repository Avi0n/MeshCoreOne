// AndroidOnly: WP-309 Mounted public action route regressions using the existing App Robolectric harness.
// ReferenceInput: MC1/Views/Chats/Reactions/ReactionDetailsSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Reactions/MessageActionAvailability.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.actions

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.actions.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
@Config(sdk = [31], qualifiers = "w360dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageActionsRepairComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val source = Source()
    private val actions = mutableListOf<MessageAction>()
    private lateinit var localized: android.content.Context
    private val now = Instant.parse("2026-10-22T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun closeHost() {
        try { host.pause().stop().destroy() } finally { scope.cancel() }
    }

    private fun mount(locale: String = "en-US", outgoing: Boolean = false, fontScale: Float = 1f) {
        val holder = MessageActionsStateHolder(source, scope)
        host.get().setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocales(LocaleList(Locale.forLanguageTag(locale)))
                this.fontScale = fontScale
            }
            localized = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalResources provides localized.resources,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                MeshCoreTheme {
                    MessageActionsRoute(
                        source.message.copy(direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING),
                        holder, null, null, listOf("X"), actions::add, clock = clock,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = localized.getString(id)
    private fun dialogText(id: Int) = compose.onNode(hasText(text(id)) and hasAnyAncestor(isDialog()))
    private fun clickAction(id: Int) {
        compose.onNodeWithText(text(id)).performScrollTo()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
    }
    private fun openReactions() {
        compose.onNodeWithContentDescription("X, 1").performScrollTo().performClick()
        compose.onNode(isDialog()).assertExists()
    }

    @Test fun retainedIncomingActionsDispatchWithoutAnyTranslateEntryInEnglishAndChinese() {
        for (locale in listOf("en-US", "zh-Hans")) {
            mount(locale)
            compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionTranslate)).assertDoesNotExist()
            clickAction(AppChatsStrings.chatsMessageActionReply)
            clickAction(AppChatsStrings.chatsMessageActionSendDM)
            clickAction(AppChatsStrings.chatsMessageActionCopy)
            compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionSendAgain)).assertDoesNotExist()
            compose.onNode(hasText("X") and !hasContentDescription("X, 1")).performScrollTo().performClick()
            clickAction(AppChatsStrings.chatsMessageActionDelete)
            assertEquals(
                listOf(MessageAction.Reply, MessageAction.SendDirectMessage, MessageAction.Copy,
                    MessageAction.React("X"), MessageAction.Delete),
                actions.toList(),
            )
            assertFalse(actions.contains(MessageAction.Translate))
            assertEquals("Original \u4f60\u597d", source.message.text)
            actions.clear()
        }
    }

    @Test fun outgoingActionsKeepSendAgainAndCopyWithoutReplyOrTranslate() {
        mount(outgoing = true)
        compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionTranslate)).assertDoesNotExist()
        compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionReply)).assertDoesNotExist()
        compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionSendDM)).assertDoesNotExist()
        clickAction(AppChatsStrings.chatsMessageActionCopy)
        clickAction(AppChatsStrings.chatsMessageActionSendAgain)
        assertEquals(listOf(MessageAction.Copy, MessageAction.SendAgain), actions)
    }

    @Test fun pendingThenEmptyShowsTitleOnlyAfterSuccessfulFetch() {
        for (locale in listOf("en-US", "zh-Hans")) {
            mount(locale)
            openReactions()
            dialogText(AppChatsStrings.reactionsEmptyStateTitle).assertDoesNotExist()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
            compose.runOnIdle { source.pending.last().complete(emptyList()) }
            dialogText(AppChatsStrings.reactionsEmptyStateTitle).assertIsDisplayed()
            dialogText(AppChatsStrings.reactionsEmptyStateDescription).assertIsDisplayed()
            dialogText(AppLocalizableStrings.commonDone).performClick()
        }
    }

    @Test fun failedReadOffersRealRetryAndThenLocalizedNamedSenderTime() {
        mount()
        openReactions()
        compose.runOnIdle { source.pending.single().completeExceptionally(IllegalStateException("private diagnostic")) }
        dialogText(AppLocalizableStrings.commonErrorFailedToLoad).assertIsDisplayed()
        dialogText(AppChatsStrings.reactionsEmptyStateTitle).assertDoesNotExist()
        compose.onNodeWithText("private diagnostic").assertDoesNotExist()
        dialogText(AppLocalizableStrings.commonTryAgain).performClick()
        compose.runOnIdle {
            assertEquals(2, source.pending.size)
            source.pending.last().complete(listOf(source.reaction(now.minusSeconds(86_400), "Yesterday sender")))
        }
        compose.onNodeWithText("Yesterday sender").assertIsDisplayed()
        compose.onNodeWithText("yesterday").assertIsDisplayed()
        dialogText(AppLocalizableStrings.commonErrorFailedToLoad).assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }

    @Test fun chineseNamedTimesAndLongSenderListRemainReachableAtDoubleFont() {
        mount("zh-Hans", fontScale = 2f)
        openReactions()
        compose.runOnIdle {
            source.pending.single().complete((0..30).map { index ->
                source.reaction(now.minusSeconds(86_400), "\u8282\u70b9 $index")
            })
        }
        compose.onNodeWithText("\u8282\u70b9 0").assertIsDisplayed()
        compose.onAllNodesWithText("\u6628\u5929").onFirst().assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        compose.onNodeWithText("\u8282\u70b9 30").assertIsDisplayed()
        dialogText(AppLocalizableStrings.commonDone).assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }

    private class Source : MessageActionsDataSource {
        private val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000309"))
        val message = MessageDTO(
            id = UUID.fromString("00000000-0000-0000-0000-000000000310"),
            radioId = radio, contactID = null, channelIndex = 0u, text = "Original \u4f60\u597d",
            timestamp = 700u, createdAt = Instant.ofEpochSecond(700), direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED, textType = TextType.PLAIN,
            pathLength = 0u, pathNodes = Bytes.EMPTY, senderNodeName = "Remote",
            reactionSummary = "X:1",
        )
        val pending = mutableListOf<CompletableDeferred<List<ReactionDTO>>>()
        fun reaction(receivedAt: Instant, sender: String) = ReactionDTO(
            messageID = message.id, emoji = "X", senderName = sender, messageHash = "0123abcd",
            rawText = "original", radioId = radio, receivedAt = receivedAt,
        )
        override suspend fun reactions(messageId: UUID): List<ReactionDTO> {
            assertEquals(message.id, messageId)
            return CompletableDeferred<List<ReactionDTO>>().also { pending += it }.await()
        }
        override suspend fun contacts(radioId: RadioId): List<ContactDTO> = error("Unexpected details read")
        override suspend fun discoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO> = error("Unexpected details read")
        override suspend fun repeats(messageId: UUID): List<MessageRepeatDTO> = error("Unexpected details read")
        override suspend fun blockChannelSender(radioId: RadioId, senderName: String): Unit = error("Unexpected block")
        override suspend fun blockContacts(contactIds: Set<UUID>): Unit = error("Unexpected block")
        override suspend fun setMuted(contactId: UUID, muted: Boolean): Unit = error("Unexpected mute")
    }
}
