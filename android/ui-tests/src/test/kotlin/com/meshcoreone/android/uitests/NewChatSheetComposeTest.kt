// AndroidOnly: WP-502 Mounted production New Chat sheet/holder with controlled ports; not original-case, database, device or golden evidence.
// ReferenceInput: MC1/Views/Chats/NewChatView.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Sheets/ChatsConversationSheets.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1Services/Sources/MC1Services/Models/Contact.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.LocaleList
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeId
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.list.*
import com.meshcoreone.android.feature.chats.list.ui.NewChatSheet
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
@Config(sdk = [31], qualifiers = "w360dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewChatSheetComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private lateinit var frame: ScreenCase
    private var effectiveScheme: ColorScheme? = null
    private val revision = mutableStateOf(0)
    private val radio = RadioId(UUID.fromString("50200000-0000-0000-0000-000000000370"))
    private val flood = contact(371, "Zulu", pathLength = 255u)
    private val blocked = contact(372, "Blocked", blocked = true)
    private val repeater = contact(373, "Repeater", type = 2u)
    private val direct = contact(374, "\u00c9lodie Radio", nickname = "\u4e2d\u6587 Caf\u00e9", pathLength = 1u)
    private val room = contact(375, "Room", type = 3u)
    private val fallback = contact(376, "\u05e9\u05dc\u05d5\u05dd Beta", type = 0u)
    private val inputs = listOf(flood, blocked, repeater, direct, room, fallback)

    private fun contact(
        suffix: Int, name: String, type: UByte = 1u, nickname: String? = null,
        pathLength: UByte = 0u, blocked: Boolean = false,
    ) = ContactDTO(
        id = UUID.fromString("50200000-0000-0000-0000-${suffix.toString().padStart(12, '0')}"),
        radioId = radio, publicKey = Bytes(ByteArray(32) { suffix.toByte() }), name = name,
        typeRawValue = type, flags = 0u, outPathLength = pathLength,
        outPath = if (pathLength == 1.toUByte()) Bytes(byteArrayOf(0x7f)) else Bytes.EMPTY,
        lastAdvertTimestamp = 1000u, latitude = 0.0, longitude = 0.0,
        lastModified = 1000u, lastHeardTimestamp = null, nickname = nickname,
        isBlocked = blocked, isMuted = false, isFavorite = false, lastMessageDate = null,
        unreadCount = 0, unreadMentionCount = 0, ocvPreset = null,
        customOCVArrayString = null, avatarImageData = null,
    )

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        Settings.Global.putFloat(host.get().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After fun closeHost() { host.pause().stop().destroy() }

    @Suppress("DEPRECATION")
    private fun mount(fixture: Fixture, case: ScreenCase = ScreenCase("new-chat", locale = "en-US")) {
        frame = case
        val resources = host.get().resources
        resources.updateConfiguration(Configuration(resources.configuration).apply {
            fontScale = frame.fontScale
            setLayoutDirection(Locale.forLanguageTag(if (frame.rtl) "ar" else frame.locale))
        }, resources.displayMetrics)
        host.get().applicationInfo.flags = host.get().applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        host.get().window.decorView.layoutDirection = if (frame.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        host.get().setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocales(LocaleList(Locale.forLanguageTag(frame.locale)))
            }
            val localized = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources,
                LocalDensity provides Density(LocalDensity.current.density, frame.fontScale),
                LocalLayoutDirection provides if (frame.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MeshCoreTheme(
                    theme = requireNotNull(ThemeRegistry.theme(frame.theme)),
                    colorSchemePreference = frame.preference, systemDark = frame.systemDark, motionScale = 0f,
                ) {
                    val scheme = LocalMeshTheme.current.frame.colorScheme
                    SideEffect { effectiveScheme = scheme }
                    key(fixture) {
                        if (fixture.shown.value) NewChatSheet(
                            fixture.holder, fixture.radio, { fixture.selected += it },
                            { fixture.dismissals += 1; fixture.shown.value = false },
                            Modifier.testTag("new-chat-sheet-${revision.value}"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(frame.expectedScheme, effectiveScheme)
    }

    private fun search() = compose.onNode(hasSetTextAction())
    private fun progress() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
    private fun row(description: String) = compose.onNode(
        hasContentDescription(description) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
        useUnmergedTree = true,
    )

    private fun assertReadable(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val sheet = compose.onNodeWithTag("new-chat-sheet-${revision.value}").fetchSemanticsNode().boundsInRoot
        assertTrue("$text outside sheet: frame=$frame node=$bounds sheet=$sheet", bounds.left >= sheet.left && bounds.right <= sheet.right)
        assertTrue("$text outside sheet: frame=$frame node=$bounds sheet=$sheet", bounds.top >= sheet.top && bounds.bottom <= sheet.bottom)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        val actualText = layout.layoutInput.text.text
        val glyphs = actualText.indices.associateWith { layout.getBoundingBox(it) }
        val outside = glyphs.filterValues {
            it.left < 0f || it.top < 0f || it.right > layout.size.width || it.bottom > layout.size.height
        }
        val lines = (0 until layout.lineCount).map { line ->
            "$line:${layout.getLineStart(line)}..${layout.getLineEnd(line)}" +
                " left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)}" +
                " top=${layout.getLineTop(line)} bottom=${layout.getLineBottom(line)}" +
                " ellipsized=${layout.isLineEllipsized(line)}"
        }
        val diagnostic = "WP502_NEW_CHAT_LAYOUT|expected=$text|actual=$actualText|frame=$frame" +
            "|effectiveScheme=$effectiveScheme|node=$bounds|sheet=$sheet|size=${layout.size}" +
            "|constraints=${layout.layoutInput.constraints}" +
            "|paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}" +
            "|density=${layout.layoutInput.density.density}|fontScale=${layout.layoutInput.density.fontScale}" +
            "|direction=${layout.layoutInput.layoutDirection}|style=${layout.layoutInput.style}" +
            "|softWrap=${layout.layoutInput.softWrap}|maxLines=${layout.layoutInput.maxLines}" +
            "|overflow=${layout.layoutInput.overflow}|didOverflowWidth=${layout.didOverflowWidth}" +
            "|didOverflowHeight=${layout.didOverflowHeight}|lines=$lines|glyphs=$glyphs|outside=$outside"
        if (layout.didOverflowWidth || layout.didOverflowHeight || outside.isNotEmpty()) println(diagnostic)
        assertEquals(text, layout.layoutInput.text.text)
        assertEquals(frame.fontScale, layout.layoutInput.density.fontScale, 0f)
        assertEquals(if (frame.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr, layout.layoutInput.layoutDirection)
        assertFalse("$text clipped vertically; $diagnostic", layout.didOverflowHeight)
        assertFalse("$text clipped horizontally; $diagnostic", layout.didOverflowWidth)
        for (line in 0 until layout.lineCount) assertFalse("$text ellipsized; $diagnostic", layout.isLineEllipsized(line))
    }

    private fun assertEmptyCopyAbsent() {
        compose.onNodeWithText("No Contacts").assertDoesNotExist()
        compose.onNodeWithText("Contacts will appear when discovered").assertDoesNotExist()
        compose.onNodeWithText("\u65e0\u8054\u7cfb\u4eba").assertDoesNotExist()
        compose.onNodeWithText("\u6210\u529f\u53d1\u73b0\u5468\u56f4\u7684\u8054\u7cfb\u4eba\u540e\uff0c\u4ed6\u4eec\u4f1a\u663e\u793a\u5728\u8fd9\u91cc").assertDoesNotExist()
    }

    private fun assertNoUnrelatedEffects(fixture: Fixture, expectedFetches: List<RadioId>) {
        compose.runOnIdle {
            assertEquals(expectedFetches, fixture.fetches)
            assertTrue(fixture.unexpectedCalls.isEmpty())
            assertTrue(fixture.reports.isEmpty())
            assertEquals(fixture.radio, fixture.currentRadioId.value)
            assertEquals(DeviceConnectionState.DISCONNECTED, fixture.connectionState.value)
            assertFalse(fixture.holder.state.value.loadFailed)
        }
    }

    @Test fun heldLoadPreservesOrderThenRealSearchSelectsExactlyTheOriginalDisplayNameContactWithoutReload() {
        val fixture = Fixture(radio)
        mount(fixture)
        progress().assertIsDisplayed()
        assertTrue(fixture.holder.state.value.isLoading)
        assertEmptyCopyAbsent()
        for (contact in inputs) compose.onNodeWithText(contact.displayName, useUnmergedTree = true).assertDoesNotExist()
        assertNoUnrelatedEffects(fixture, listOf(radio))
        compose.runOnIdle { assertTrue(fixture.result.complete(inputs)) }
        compose.waitForIdle()
        progress().assertDoesNotExist()
        assertEquals(inputs, fixture.holder.state.value.contacts)
        assertEmptyCopyAbsent()
        val ordered = listOf("Zulu, Flood routing", "\u4e2d\u6587 Caf\u00e9, Direct", "\u05e9\u05dc\u05d5\u05dd Beta, Direct")
            .map { row(it).assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                .fetchSemanticsNode().boundsInRoot }
        assertTrue(ordered.zipWithNext().all { (first, second) -> first.bottom <= second.top })
        for (hidden in listOf("Blocked", "Repeater", "Room", "\u00c9lodie Radio")) {
            compose.onNodeWithText(hidden, useUnmergedTree = true).assertDoesNotExist()
        }
        for (text in listOf("New Chat", "Cancel", "Search contacts", "Zulu", "\u4e2d\u6587 Caf\u00e9", "\u05e9\u05dc\u05d5\u05dd Beta")) assertReadable(text)
        search().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .performTextReplacement("CAFE")
        row("\u4e2d\u6587 Caf\u00e9, Direct").assertIsDisplayed()
        row("Zulu, Flood routing").assertDoesNotExist()
        row("\u05e9\u05dc\u05d5\u05dd Beta, Direct").assertDoesNotExist()
        search().performTextReplacement("\u4e2d\u6587")
        val before = fixture.holder.state.value
        compose.runOnIdle { revision.value += 1 }
        compose.onNodeWithTag("new-chat-sheet-1").assertIsDisplayed()
        search().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("\u4e2d\u6587")))
        assertEquals(before, fixture.holder.state.value)
        row("\u4e2d\u6587 Caf\u00e9, Direct").assertIsDisplayed()
        for (query in listOf("\u00c9lodie", "missing")) {
            search().performTextReplacement(query)
            for (description in listOf("Zulu, Flood routing", "\u4e2d\u6587 Caf\u00e9, Direct", "\u05e9\u05dc\u05d5\u05dd Beta, Direct")) {
                row(description).assertDoesNotExist()
            }
            assertEmptyCopyAbsent()
        }
        search().performTextReplacement("\u05e9\u05dc\u05d5\u05dd")
        row("\u05e9\u05dc\u05d5\u05dd Beta, Direct").assertIsDisplayed()
        row("\u4e2d\u6587 Caf\u00e9, Direct").assertDoesNotExist()
        search().performTextReplacement("")
        for (description in listOf("Zulu, Flood routing", "\u4e2d\u6587 Caf\u00e9, Direct", "\u05e9\u05dc\u05d5\u05dd Beta, Direct")) {
            row(description).assertIsDisplayed()
        }
        assertTrue(fixture.selected.isEmpty())
        row("\u4e2d\u6587 Caf\u00e9, Direct").performClick()
        compose.runOnIdle {
            assertEquals(listOf(direct), fixture.selected)
            assertSame(direct, fixture.selected.single())
            assertEquals(0, fixture.dismissals)
            assertTrue(fixture.shown.value)
            assertEquals(inputs, fixture.holder.state.value.contacts)
        }
        row("\u4e2d\u6587 Caf\u00e9, Direct").assertIsDisplayed()
        assertNoUnrelatedEffects(fixture, listOf(radio))
    }

    @Test fun ineligibleVersusEligibleListsKeepLocalizedCancelIndependentAtLargeRtlFont() {
        for (contacts in listOf(listOf(blocked, repeater, room), inputs)) {
            val fixture = Fixture(radio)
            mount(fixture, ScreenCase(
                "new-chat-cjk-rtl", theme = ThemeId.EMBER, expectedScheme = ColorScheme.DARK,
                locale = "zh-Hans", fontScale = 2f, rtl = true,
            ))
            progress().assertIsDisplayed()
            compose.runOnIdle { assertTrue(fixture.result.complete(contacts)) }
            compose.waitForIdle()
            progress().assertDoesNotExist()
            assertEmptyCopyAbsent()
            assertEquals(contacts, fixture.holder.state.value.contacts)
            for (name in listOf("Blocked", "Repeater", "Room")) compose.onNodeWithText(name).assertDoesNotExist()
            for (text in listOf("\u65b0\u804a\u5929", "\u53d6\u6d88", "\u641c\u7d22\u8054\u7cfb\u4eba")) assertReadable(text)
            if (contacts == inputs) {
                for (description in listOf("Zulu, \u6cdb\u6d2a\u8def\u7531", "\u4e2d\u6587 Caf\u00e9, \u76f4\u8fde", "\u05e9\u05dc\u05d5\u05dd Beta, \u76f4\u8fde")) {
                    row(description).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                }
                for (name in listOf("Zulu", "\u4e2d\u6587 Caf\u00e9", "\u05e9\u05dc\u05d5\u05dd Beta", "\u6cdb\u6d2a\u8def\u7531")) assertReadable(name)
                compose.onAllNodesWithText("\u76f4\u8fde", useUnmergedTree = true).assertCountEquals(2)
            } else {
                for (name in listOf("Zulu", "\u4e2d\u6587 Caf\u00e9", "\u05e9\u05dc\u05d5\u05dd Beta")) compose.onNodeWithText(name).assertDoesNotExist()
            }
            search().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                .performTextReplacement("Repeater")
            compose.runOnIdle { revision.value += 1 }
            search().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Repeater")))
            for (name in listOf("Blocked", "Room", "Zulu", "\u4e2d\u6587 Caf\u00e9", "\u05e9\u05dc\u05d5\u05dd Beta")) {
                compose.onNodeWithText(name, useUnmergedTree = true).assertDoesNotExist()
            }
            row("Repeater, \u8f6c\u53d1\u8282\u70b9").assertDoesNotExist()
            assertEmptyCopyAbsent()
            compose.onNode(hasText("\u53d6\u6d88") and hasClickAction()).assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("\u65b0\u804a\u5929").assertDoesNotExist()
            assertEquals(1, fixture.dismissals)
            assertTrue(fixture.selected.isEmpty())
            assertFalse(fixture.shown.value)
            assertNoUnrelatedEffects(fixture, listOf(radio))
        }
    }

    @Test fun freshNoRadioAndSuccessfullyLoadedRawEmptyShowEmptyCopyWithoutSelectionOrServiceMutation() {
        for (selectedRadio in listOf(null, radio)) {
            val fixture = Fixture(selectedRadio)
            mount(fixture)
            if (selectedRadio != null) {
                progress().assertIsDisplayed()
                assertEmptyCopyAbsent()
                compose.runOnIdle { assertTrue(fixture.result.complete(emptyList())) }
                compose.waitForIdle()
            }
            progress().assertDoesNotExist()
            assertTrue(fixture.holder.state.value.contacts.isEmpty())
            assertFalse(fixture.holder.state.value.isLoading)
            assertReadable("No Contacts")
            assertReadable("Contacts will appear when discovered")
            search().performTextReplacement("\u4e2d\u6587")
            compose.onNodeWithText("No Contacts").assertIsDisplayed()
            compose.onNode(hasText("Cancel") and hasClickAction()).assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("New Chat").assertDoesNotExist()
            assertEquals(1, fixture.dismissals)
            assertTrue(fixture.selected.isEmpty())
            assertFalse(fixture.shown.value)
            assertNoUnrelatedEffects(fixture, if (selectedRadio == null) emptyList() else listOf(radio))
        }
    }

    private class Fixture(val radio: RadioId?) : ChatListFeatureDependencies {
        val result = CompletableDeferred<List<ContactDTO>>()
        val fetches = mutableListOf<RadioId>()
        val selected = mutableListOf<ContactDTO>()
        val unexpectedCalls = mutableListOf<String>()
        val reports = mutableListOf<Pair<String, Throwable?>>()
        var dismissals = 0
        val shown = mutableStateOf(true)
        val holder = NewChatStateHolder(this)
        override val currentRadioId = MutableStateFlow(radio)
        override val connectionState = MutableStateFlow(DeviceConnectionState.DISCONNECTED)
        override val clock: Clock = Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC)
        override val diagnostics = ChatListDiagnostics { message, failure -> reports += message to failure }
        override val reactions: ChatReactionClassifier get() = unexpected("reactions")
        override val navigation: ChatListNavigation get() = unexpected("navigation")
        override val links: ChatLinkEnvironment get() = unexpected("links")
        override val reloadRequests: Flow<Unit> get() = unexpected("reloadRequests")
        override val messageEvents: Flow<ChatListEvent> get() = unexpected("messageEvents")
        override fun conversationServices(): ChatConversationServices? = unexpected("conversationServices")

        private fun unexpected(port: String): Nothing {
            unexpectedCalls += port
            throw AssertionError("Unrelated New Chat port invoked: $port")
        }

        override val data = object : ChatListDataSource {
            override suspend fun fetchContacts(radioId: RadioId): List<ContactDTO> {
                fetches += radioId
                return result.await()
            }
            override suspend fun fetchConversations(radioId: RadioId): List<ContactDTO> = unexpected("fetchConversations")
            override suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO> = unexpected("fetchChannels")
            override suspend fun fetchRemoteNodeSessions(radioId: RadioId): List<RemoteNodeSessionDTO> = unexpected("fetchRemoteNodeSessions")
            override suspend fun fetchLastMessages(contactIds: List<UUID>, limit: Int): Map<UUID, List<MessageDTO>> = unexpected("fetchLastMessages")
            override suspend fun fetchLastChannelMessages(channels: List<ChatChannelKey>, limit: Int): Map<UUID, List<MessageDTO>> = unexpected("fetchLastChannelMessages")
            override suspend fun fetchFailedSendConversationKeys(radioId: RadioId): FailedSendConversationKeys = unexpected("fetchFailedSendConversationKeys")
            override suspend fun setContactMuted(contactId: UUID, isMuted: Boolean): Unit = unexpected("setContactMuted")
            override suspend fun setChannelNotificationLevel(channelId: UUID, level: NotificationLevel): Unit = unexpected("setChannelNotificationLevel")
            override suspend fun setSessionNotificationLevel(sessionId: UUID, level: NotificationLevel): Unit = unexpected("setSessionNotificationLevel")
            override suspend fun setChannelFavorite(channelId: UUID, isFavorite: Boolean): Unit = unexpected("setChannelFavorite")
            override suspend fun setSessionFavorite(sessionId: UUID, isFavorite: Boolean): Unit = unexpected("setSessionFavorite")
            override suspend fun deleteDirectConversation(contact: ContactDTO): Unit = unexpected("deleteDirectConversation")
        }
    }
}
