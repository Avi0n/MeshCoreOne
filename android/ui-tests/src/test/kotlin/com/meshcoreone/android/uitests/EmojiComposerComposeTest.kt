// AndroidOnly: WP-502 Mounted emoji sheet -> real ComposerRoute integration; not original-case, reaction-send, asset, device or golden evidence.
// ReferenceInput: MC1/Views/Chats/Reactions/EmojiPickerSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Reactions/EmojiPickerViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Reactions/EmojiProvider.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Reactions/RecentEmojisStore.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Reactions/EmojiCategoryHeader.swift@db14559b39d32322b06477c6ae676112f583db50
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
import androidx.compose.ui.text.TextRange
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
import com.meshcoreone.android.feature.chats.composer.*
import com.meshcoreone.android.feature.chats.composer.ui.ComposerRoute
import com.meshcoreone.android.feature.chats.timeline.TimelineDraftStore
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmojiComposerComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private lateinit var frame: ScreenCase
    private var effectiveScheme: ColorScheme? = null
    private val revision = mutableStateOf(0)
    private val fixtures = mutableListOf<Fixture>()
    @get:Rule val failureDiagnostics = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            System.err.println("WP502_EMOJI_FAILURE|${description.methodName}|frame=${failureFrame()}|effectiveScheme=$effectiveScheme")
            fixtures.forEachIndexed { index, fixture ->
                System.err.println(
                    "WP502_EMOJI_STATE|fixture=$index|load=${fixture.picker.state.value.load}" +
                        "|attempts=${fixture.attempts.size}|active=${fixture.activeLoads}|cancelled=${fixture.cancelledLoads}" +
                        "|draftReads=${fixture.draftReads}|draftWrites=${fixture.draftWrites}" +
                        "|frequentReads=${fixture.frequentReads}|frequentWrites=${fixture.frequentWrites}" +
                        "|effects=${fixture.effects}|forbidden=${fixture.forbiddenCalls}",
                )
            }
            failure.printStackTrace(System.err)
        }
    }
    private val catalog = linkedMapOf(
        "flags" to listOf(EmojiEntry("1F1EF-1F1F5", "\uD83C\uDDEF\uD83C\uDDF5", "Japan")),
        "symbols" to listOf(EmojiEntry("2733", "\u2733", "asterisk")),
        "objects" to listOf(EmojiEntry("1F4A1", "\uD83D\uDCA1", "bulb")),
        "places" to listOf(EmojiEntry("1F680", "\uD83D\uDE80", "rocket")),
        "activity" to listOf(EmojiEntry("26BD", "\u26BD", "football")),
        "foods" to listOf(EmojiEntry("1F350", "\uD83C\uDF50", "pear")),
        "nature" to listOf(EmojiEntry("1F332", "\uD83C\uDF32", "tree")),
        "unknown" to listOf(EmojiEntry("1F6F8", "\uD83D\uDEF8", "forbidden unknown")),
        "people" to listOf(
            EmojiEntry("1F469-200D-1F4BB", TECH, "Caf\u00e9 coder", listOf("woman_technologist"), listOf("\u7f16\u7a0b")),
            EmojiEntry("2764-FE0F", HEART, ""),
        ),
    )
    private val categoryIds = listOf("frequent", "people", "nature", "foods", "activity", "places", "objects", "symbols", "flags")
    private val dark = ScreenCase(
        "emoji-dark-cjk-rtl", theme = ThemeId.EMBER, expectedScheme = ColorScheme.DARK,
        locale = "zh-Hans", heightDp = 1400, fontScale = 2f, rtl = true,
    )

    private fun failureFrame() = if (::frame.isInitialized) frame.toString() else "not-mounted"

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        Settings.Global.putFloat(host.get().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After fun closeHost() {
        host.pause().stop().destroy()
        fixtures.forEach { it.scope.cancel() }
    }

    @Suppress("DEPRECATION")
    private fun mount(fixture: Fixture, case: ScreenCase = ScreenCase("emoji-light", heightDp = 1400, locale = "en-US")) {
        fixtures += fixture
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
                        ComposerRoute(
                            fixture.composer, "Controlled draft", { fixture.forbidden("shareContact") },
                            Modifier.testTag("emoji-composer-${revision.value}"), fixture.picker,
                        )
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals(frame.expectedScheme, effectiveScheme) }
        compose.runOnIdle { assertMountedProfile(host.get(), frame) }
    }

    private fun composer() = compose.onNode(
        hasSetTextAction() and hasContentDescription(
            if (frame.locale == "en-US") "Message input, Encrypted" else "\u6d88\u606f\u8f93\u5165, \u5df2\u52a0\u5bc6",
        ),
    )
    private fun entry() = compose.onNodeWithContentDescription(if (frame.locale == "en-US") "More emojis" else "\u66f4\u591a\u8868\u60c5")
    private fun search() = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
    private fun grid() = compose.onNode(hasScrollToIndexAction())
    private fun progress() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
    private fun emoji(label: String) = compose.onNode(
        hasContentDescription(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
        useUnmergedTree = true,
    )
    private fun failureText() = if (frame.locale == "en-US") "Services not available" else "\u670d\u52a1\u4e0d\u53ef\u7528"

    private fun open() {
        entry().assertIsDisplayed().assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        search().assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    private fun dismiss() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
        search().assertDoesNotExist()
        entry().assertIsDisplayed()
    }

    private fun assertTypography(node: SemanticsNodeInteraction, text: String): TextLayoutResult {
        val bounds = node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width > 0f && bounds.height > 0f)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(text, layout.layoutInput.text.text)
        assertEquals(frame.fontScale, layout.layoutInput.density.fontScale, 0f)
        assertEquals(if (frame.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr, layout.layoutInput.layoutDirection)
        return layout
    }

    private fun assertCopyLayout(node: SemanticsNodeInteraction, text: String) {
        val layout = assertTypography(node, text)
        assertMountedText(
            compose, node, text, frame.fontScale,
            if (frame.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            "emoji frame=$frame effectiveScheme=$effectiveScheme",
        )
        assertFalse(layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
    }

    private fun assertDraft(fixture: Fixture, text: String, caret: Int) {
        composer().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(caret)))
        compose.runOnIdle {
            assertEquals(text, fixture.composer.state.value.draft)
            assertEquals(text, fixture.draftValues[fixture.targetId])
            assertEquals("untouched", fixture.draftValues[fixture.otherId])
            assertEquals(listOf(fixture.targetId), fixture.draftReads)
            assertTrue(fixture.forbiddenCalls.isEmpty())
            assertEquals(ComposerSendPhase.Idle, fixture.composer.state.value.sendPhase)
            assertTrue(fixture.composer.state.value.canSend)
            assertEquals(DeviceConnectionState.READY, fixture.environment.connectionState.value)
        }
    }

    private fun assertNoMutation(fixture: Fixture, loads: Int) {
        assertDraft(fixture, INITIAL, 6)
        compose.runOnIdle {
            assertEquals(loads, fixture.attempts.size)
            assertEquals(0, fixture.activeLoads)
            assertTrue(fixture.draftWrites.isEmpty())
            assertTrue(fixture.frequentWrites.isEmpty())
            assertTrue(fixture.effects.isEmpty())
            assertEquals(fixture.initialFrequent, fixture.frequentValue)
            assertEquals(0, fixture.composer.state.value.focusRequest)
        }
    }

    @Test fun heldCatalogRendersSourceOrderedHeadingsAndRealSearchWithoutRefetchOrCancellationMutation() {
        val fixture = Fixture(listOf(THUMB))
        mount(fixture)
        open()
        progress().assertIsDisplayed()
        assertEquals(EmojiPickerLoad.Loading, fixture.picker.state.value.load)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertCountEquals(0)
        emoji("Caf\u00e9 coder").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fixture.attempts.single().complete(catalog)) }
        progress().assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(categoryIds, fixture.picker.state.value.categories.map { it.id })
            assertEquals(listOf("people-1F469-200D-1F4BB", "people-2764-FE0F"), fixture.picker.state.value.categories[1].emojis.map { it.id })
        }
        val headers = listOf(
            0 to "Frequently Used", 2 to "Smileys & People", 5 to "Animals & Nature",
            7 to "Food & Drink", 9 to "Activity", 11 to "Travel & Places",
            13 to "Objects", 15 to "Symbols", 17 to "Flags",
        )
        for ((index, label) in headers) {
            grid().performScrollToIndex(index)
            val header = compose.onNodeWithText(label).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            assertCopyLayout(header, label)
        }
        grid().performScrollToIndex(0)
        emoji(THUMB).assertIsDisplayed().assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        emoji(HEART).assertIsDisplayed().assertContentDescriptionEquals(HEART).assertHasClickAction()
        emoji("forbidden unknown").assertDoesNotExist()
        for (query in listOf("CAFE", "TECHNOLOGIST", "\u7f16\u7a0b")) {
            search().performTextReplacement(query)
            emoji("Caf\u00e9 coder").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            compose.onNodeWithText("Frequently Used").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(listOf("people"), fixture.picker.state.value.categories.map { it.id })
                assertEquals(listOf(EmojiItem("people-1F469-200D-1F4BB", TECH, "Caf\u00e9 coder")), fixture.picker.state.value.categories.single().emojis)
            }
        }
        search().performTextReplacement("unmatched-zzz")
        progress().assertDoesNotExist()
        compose.onNodeWithText(failureText()).assertDoesNotExist()
        grid().assertExists()
        compose.runOnIdle {
            assertEquals(EmojiPickerLoad.Loaded, fixture.picker.state.value.load)
            assertTrue(fixture.picker.state.value.categories.isEmpty())
        }
        search().performTextReplacement("")
        compose.runOnIdle { revision.value += 1 }
        compose.onNodeWithTag("emoji-composer-1").assertExists()
        emoji(THUMB).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(categoryIds, fixture.picker.state.value.categories.map { it.id })
            assertEquals(2, fixture.frequentReads)
            assertEquals(0, fixture.activeLoads)
        }
        dismiss()
        assertNoMutation(fixture, 1)
    }

    @Test fun realSelectionReplacesUtf16RangePersistsDraftAndNormalizesFrequentOnceBeforeReopening() {
        val fixture = Fixture(OVERFULL_FREQUENT)
        mount(fixture, dark)
        composer().performTextInputSelection(TextRange(1, 5))
        composer().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1, 5)))
        open()
        progress().assertIsDisplayed()
        compose.runOnIdle { assertTrue(fixture.attempts.single().complete(catalog)) }
        progress().assertDoesNotExist()
        assertCopyLayout(compose.onNodeWithText("\u5e38\u7528"), "\u5e38\u7528")
        search().performTextReplacement("TECHNOLOGIST")
        assertCopyLayout(compose.onNodeWithText("\u8868\u60c5"), "\u8868\u60c5")
        val selected = emoji("Caf\u00e9 coder").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertTypography(compose.onNodeWithText(TECH, useUnmergedTree = true), TECH)
        selected.performClick()
        search().assertDoesNotExist()
        assertDraft(fixture, "A\uD83D\uDC69\u200D\uD83D\uDCBBZ", 6)
        compose.runOnIdle {
            assertEquals(listOf(EXPECTED_FREQUENT), fixture.frequentWrites)
            assertEquals(EXPECTED_FREQUENT, fixture.frequentValue)
            assertEquals(listOf(fixture.targetId to "A\uD83D\uDC69\u200D\uD83D\uDCBBZ"), fixture.draftWrites)
            assertEquals(listOf("frequent", "draft:A\uD83D\uDC69\u200D\uD83D\uDCBBZ"), fixture.effects)
            assertEquals(1, fixture.composer.state.value.focusRequest)
        }
        composer().performTextInput("!")
        assertDraft(fixture, "A\uD83D\uDC69\u200D\uD83D\uDCBB!Z", 7)
        open()
        progress().assertDoesNotExist()
        search().performTextReplacement("")
        assertCopyLayout(compose.onNodeWithText("\u5e38\u7528"), "\u5e38\u7528")
        emoji(TECH).assertIsDisplayed().assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.runOnIdle {
            assertEquals(categoryIds, fixture.picker.state.value.categories.map { it.id })
            assertEquals(EXPECTED_FREQUENT, fixture.picker.state.value.categories.first().emojis.map { it.unicode })
            assertEquals(1, fixture.attempts.size)
            assertEquals(3, fixture.frequentReads)
        }
        dismiss()
        assertDraft(fixture, "A\uD83D\uDC69\u200D\uD83D\uDCBB!Z", 7)
        compose.runOnIdle {
            assertEquals(listOf(EXPECTED_FREQUENT), fixture.frequentWrites)
            assertEquals(listOf(fixture.targetId to "A\uD83D\uDC69\u200D\uD83D\uDCBBZ", fixture.targetId to "A\uD83D\uDC69\u200D\uD83D\uDCBB!Z"), fixture.draftWrites)
            assertEquals(listOf("frequent", "draft:A\uD83D\uDC69\u200D\uD83D\uDCBBZ", "draft:A\uD83D\uDC69\u200D\uD83D\uDCBB!Z"), fixture.effects)
            assertEquals(1, fixture.composer.state.value.focusRequest)
        }
    }

    @Test fun failedCatalogShowsLocalizedFailureAndOnlyRealDismissReopenStartsAnotherLoad() {
        for (case in listOf(ScreenCase("emoji-error", heightDp = 1400, locale = "en-US"), dark)) {
            val fixture = Fixture(emptyList())
            mount(fixture, case)
            open()
            progress().assertIsDisplayed()
            compose.runOnIdle { assertTrue(fixture.attempts.single().completeExceptionally(IllegalStateException("private catalog reason"))) }
            progress().assertDoesNotExist()
            assertCopyLayout(compose.onNodeWithText(failureText()), failureText())
            compose.onNodeWithText("private catalog reason", substring = true).assertDoesNotExist()
            grid().assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(fixture.picker.state.value.load is EmojiPickerLoad.Failed)
                revision.value += 1
            }
            compose.onNodeWithText(failureText()).assertIsDisplayed()
            assertNoMutation(fixture, 1)
            dismiss()
            open()
            progress().assertIsDisplayed()
            compose.onNodeWithText(failureText()).assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(2, fixture.attempts.size)
                assertEquals(1, fixture.activeLoads)
                assertTrue(fixture.attempts[1].complete(catalog))
            }
            progress().assertDoesNotExist()
            emoji("Caf\u00e9 coder").assertIsDisplayed()
            compose.onNodeWithText(failureText()).assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(EmojiPickerLoad.Loaded, fixture.picker.state.value.load)
                assertEquals(listOf("people", "nature", "foods", "activity", "places", "objects", "symbols", "flags"), fixture.picker.state.value.categories.map { it.id })
            }
            dismiss()
            assertNoMutation(fixture, 2)
        }
    }

    @Test fun ownerCancellationPropagatesFromHeldCatalogWithoutFailedCopyDraftOrFrequentMutation() {
        val fixture = Fixture(listOf(THUMB))
        mount(fixture)
        open()
        progress().assertIsDisplayed()
        compose.runOnIdle { fixture.scope.cancel() }
        progress().assertIsDisplayed()
        compose.onNodeWithText(failureText()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, fixture.cancelledLoads)
            assertEquals(0, fixture.activeLoads)
            assertEquals(EmojiPickerLoad.Loading, fixture.picker.state.value.load)
        }
        dismiss()
        assertNoMutation(fixture, 1)
    }

    private class Fixture(val initialFrequent: List<String>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val targetId = ChatConversationID(
            RadioId(UUID.fromString("50200000-0000-0000-0000-000000000380")),
            ConversationKey.DM(UUID.fromString("50200000-0000-0000-0000-000000000381")),
        )
        val otherId = ChatConversationID(
            RadioId(UUID.fromString("50200000-0000-0000-0000-000000000382")),
            ConversationKey.DM(UUID.fromString("50200000-0000-0000-0000-000000000383")),
        )
        val draftValues = mutableMapOf(targetId to INITIAL, otherId to "untouched")
        val draftReads = mutableListOf<ChatConversationID>()
        val draftWrites = mutableListOf<Pair<ChatConversationID, String?>>()
        var frequentValue = initialFrequent.toList()
        var frequentReads = 0
        val frequentWrites = mutableListOf<List<String>>()
        val effects = mutableListOf<String>()
        val forbiddenCalls = mutableListOf<String>()
        val attempts = mutableListOf<CompletableDeferred<Map<String, List<EmojiEntry>>>>()
        var activeLoads = 0
        var cancelledLoads = 0
        fun forbidden(port: String): Nothing {
            forbiddenCalls += port
            throw AssertionError("Forbidden emoji consumer interaction: $port")
        }
        val environment = object : ComposerEnvironment {
            override val connectionState = MutableStateFlow(DeviceConnectionState.READY)
            override val device = MutableStateFlow<ComposerDeviceInfo?>(null)
            override val mentionContacts = MutableStateFlow<List<ContactDTO>>(emptyList())
            override val recentSenderOrder = MutableStateFlow<Map<String, UInt>>(emptyMap())
            override val location = object : ComposerLocationSource {
                override val isAuthorized = false
                override val currentCoordinate: Coordinate? get() = forbidden("location.currentCoordinate")
                override suspend fun requestCurrentLocation(timeoutMillis: Long): Coordinate? = forbidden("location.request")
            }
        }
        private val drafts = object : TimelineDraftStore {
            override fun get(conversationId: ChatConversationID): String? {
                draftReads += conversationId
                if (conversationId != targetId) forbidden("draft.read.wrongIdentity")
                return draftValues[conversationId]
            }
            override fun set(conversationId: ChatConversationID, text: String?) {
                if (conversationId != targetId) forbidden("draft.write.wrongIdentity")
                draftWrites += conversationId to text
                effects += "draft:$text"
                if (text == null) draftValues.remove(conversationId) else draftValues[conversationId] = text
            }
        }
        val picker = EmojiPickerStateHolder(
            catalog = {
                val gate = CompletableDeferred<Map<String, List<EmojiEntry>>>()
                attempts += gate
                activeLoads += 1
                try { gate.await() }
                catch (cancelled: CancellationException) { cancelledLoads += 1; throw cancelled }
                finally { activeLoads -= 1 }
            },
            frequent = object : FrequentEmojiStore {
                override fun read(): List<String> { frequentReads += 1; return frequentValue.toList() }
                override fun write(emojis: List<String>) {
                    frequentValue = emojis.toList()
                    frequentWrites += emojis.toList()
                    effects += "frequent"
                }
            },
            scope = scope,
        )
        val composer = ComposerStateHolder(
            ComposerTarget.Direct(ContactDTO(
                id = UUID.fromString("50200000-0000-0000-0000-000000000381"),
                radioId = RadioId(UUID.fromString("50200000-0000-0000-0000-000000000380")),
                publicKey = Bytes(ByteArray(32) { 0x38 }), name = "Emoji target",
                typeRawValue = 1u, flags = 0u, outPathLength = 0u, outPath = Bytes.EMPTY,
                lastAdvertTimestamp = 1000u, latitude = 0.0, longitude = 0.0, lastModified = 1000u,
                lastHeardTimestamp = null, nickname = null, isBlocked = false, isMuted = false,
                isFavorite = false, lastMessageDate = null, unreadCount = 0, unreadMentionCount = 0,
                ocvPreset = null, customOCVArrayString = null, avatarImageData = null,
            )),
            object : ComposerSendPort {
                override suspend fun createPendingDirect(text: String, contact: ContactDTO, textType: TextType, replyToId: UUID?): MessageDTO = forbidden("send.direct")
                override suspend fun createPendingChannel(text: String, channelIndex: UByte, radioId: RadioId, textType: TextType): MessageDTO = forbidden("send.channel")
                override suspend fun enqueueDM(envelope: DirectMessageEnvelope): Unit = forbidden("enqueue.direct")
                override suspend fun enqueueChannel(envelope: ChannelMessageEnvelope): Unit = forbidden("enqueue.channel")
                override suspend fun signalDMEnqueued(envelope: DirectMessageEnvelope): Unit = forbidden("retry")
                override suspend fun recordLocalEnqueueFailure(messageId: UUID): Unit = forbidden("enqueue.failure")
            },
            drafts, environment, scope, ComposerTimer { forbidden("cooldown") },
            ComposerDiagnostics { operation, _ -> forbidden("diagnostics:$operation") }, Locale.US,
        )
    }

    companion object {
        private const val TECH = "\uD83D\uDC69\u200D\uD83D\uDCBB"
        private const val HEART = "\u2764\uFE0F"
        private const val THUMB = "\uD83D\uDC4D"
        private const val INITIAL = "A\uD83D\uDE00BCZ"
        private val OVERFULL_FREQUENT = listOf(
            HEART, TECH, "\uD83D\uDE00", "\uD83D\uDE01", "\uD83D\uDE02", "\uD83D\uDE03", "\uD83D\uDE04",
            "\uD83D\uDE05", "\uD83D\uDE06", "\uD83D\uDE07", "\uD83D\uDE08", "\uD83D\uDE09", "\uD83D\uDE0A",
            "\uD83D\uDE0B", "\uD83D\uDE0C", "\uD83D\uDE0D", "\uD83D\uDE0E", "\uD83D\uDE0F", "\uD83D\uDE10",
            "\uD83D\uDE11", "\uD83D\uDE12", TECH,
        )
        private val EXPECTED_FREQUENT = listOf(
            TECH, HEART, "\uD83D\uDE00", "\uD83D\uDE01", "\uD83D\uDE02", "\uD83D\uDE03", "\uD83D\uDE04",
            "\uD83D\uDE05", "\uD83D\uDE06", "\uD83D\uDE07", "\uD83D\uDE08", "\uD83D\uDE09", "\uD83D\uDE0A",
            "\uD83D\uDE0B", "\uD83D\uDE0C", "\uD83D\uDE0D", "\uD83D\uDE0E", "\uD83D\uDE0F", "\uD83D\uDE10", "\uD83D\uDE11",
        )
    }
}
