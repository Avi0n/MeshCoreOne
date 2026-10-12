// AndroidOnly: WP-502 Real production timeline/composer rendering with fixed synthetic data and explicit service-state inputs.
// ReferenceInput: MC1/Views/Chats/Components/ChatInputBar.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/ChatConversationMessagesContent.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowInsets as AndroidWindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.*
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.feature.chats.composer.ComposerUiState
import com.meshcoreone.android.feature.chats.composer.ComposerSendPhase
import com.meshcoreone.android.feature.chats.composer.ui.ComposerBar
import com.meshcoreone.android.feature.chats.composer.ui.ComposerBarActions
import com.meshcoreone.android.feature.chats.composer.ui.ShareAvailability
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimeline
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineCallbacks
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.*

class ConversationScreenCases(
    private val compose: ComposeTestRule,
    private val activity: Activity,
    private val setContent: (@Composable () -> Unit) -> Unit,
    private val useCurrentDeviceWindow: Boolean = false,
) {
    companion object {
        val radio = RadioId(UUID.fromString("50200000-0000-0000-0000-000000000001"))
        val contactId = UUID.fromString("50200000-0000-0000-0000-000000000002")
        const val originalText = "Original mesh text: \u4e2d\u6587 \u05e9\u05dc\u05d5\u05dd \uD83D\uDC69\u200D\uD83D\uDCBB"
        val instant: Instant = Instant.ofEpochSecond(1_704_067_200)

        fun message(index: Int, direction: MessageDirection, status: MessageStatus) = MessageDTO(
            id = UUID.fromString("50200000-0000-0000-0000-${index.toString().padStart(12, '0')}"),
            radioId = radio, contactID = contactId,
            text = if (direction == MessageDirection.INCOMING) originalText.repeat(3) else "Synthetic outgoing mesh message",
            timestamp = instant.epochSecond.toUInt(), createdAt = instant, sortDate = instant,
            direction = direction, status = status,
        )
    }

    private val selected = mutableStateOf(ScreenCase("initial"))
    private val draft = mutableStateOf(TextFieldValue("Retained draft"))
    private val timelineOverride = mutableStateOf<ChatTimelineState?>(null)
    private val composerOverride = mutableStateOf<ComposerUiState?>(null)
    private var effectiveScheme: ColorScheme? = null
    private var retries = 0
    private var sendRequests = 0
    private var loadRetries = 0

    fun mount() {
        setContent {
            val case = selected.value
            val context = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocales(LocaleList(Locale.forLanguageTag(case.locale)))
            }
            val localized = context.createConfigurationContext(configuration)
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalResources provides localized.resources,
                LocalDensity provides Density(density, case.fontScale),
                LocalLayoutDirection provides if (case.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MeshCoreTheme(
                    theme = requireNotNull(ThemeRegistry.theme(case.theme)),
                    colorSchemePreference = case.preference,
                    systemDark = case.systemDark,
                    highContrast = false,
                    motionScale = 0f,
                ) {
                    val tokens = LocalMeshTheme.current
                    SideEffect { effectiveScheme = tokens.frame.colorScheme }
                    Surface(if (useCurrentDeviceWindow) Modifier.windowInsetsPadding(WindowInsets.systemBars) else Modifier) {
                        Column(Modifier.width(case.widthDp.dp).height(case.heightDp.dp).testTag("wp502-screen")) {
                            val status = when (case.state) {
                                ConversationState.PENDING -> MessageStatus.PENDING
                                ConversationState.FAILED -> MessageStatus.FAILED
                                ConversationState.DELIVERED -> MessageStatus.DELIVERED
                                ConversationState.OFFLINE -> MessageStatus.DELIVERED
                            }
                            val messages = listOf(
                                message(3, MessageDirection.INCOMING, MessageStatus.DELIVERED),
                                message(4, MessageDirection.OUTGOING, status),
                            )
                            ChatTimeline(
                                timelineOverride.value ?: ChatTimelineState(
                                    messages = messages,
                                    rows = messages.map { TimelineRow.Message(it, startsGroup = true, endsGroup = true) },
                                    hasLoadedOnce = true, hasMoreMessages = false, initialAnchorConsumed = true,
                                ),
                                ChatTimelineCallbacks(
                                    onLoadOlder = { error("Fixture has no older page") },
                                    onRetry = { retries++ },
                                    onInitialAnchorConsumed = { error("Fixture anchor is already consumed") },
                                    onScrollPositionChanged = { _, _ -> },
                                    onJumpToLatest = { error("Fixture is already at latest") },
                                    onRetryLoad = { loadRetries++ },
                                ),
                                Modifier.weight(1f),
                            )
                            ComposerBar(
                                state = (composerOverride.value ?: ComposerUiState(
                                    maxBytes = 160,
                                    isConnected = case.state != ConversationState.OFFLINE,
                                )).copy(draft = draft.value.text),
                                fieldValue = draft.value,
                                placeholder = localized.getString(C.chatsInputAccessibilityLabel),
                                share = ShareAvailability(location = false, myInfo = false),
                                actions = ComposerBarActions(
                                    onFieldValueChange = { draft.value = it },
                                    onSend = { sendRequests++ },
                                    onShareLocation = { error("Unauthorized fixture location") },
                                    onShareContact = { error("No share target in this fixture") },
                                    onShareMyInfo = { error("No connected node info in this fixture") },
                                    onOpenEmoji = null,
                                    onDismissFailure = {},
                                ),
                                focusRequester = remember { FocusRequester() },
                            )
                        }
                    }
                }
            }
        }
    }

    fun show(case: ScreenCase) {
        val current = if (useCurrentDeviceWindow) {
            val decor = activity.window.decorView
            val density = activity.resources.displayMetrics.density
            val insets = requireNotNull(decor.rootWindowInsets).getInsets(AndroidWindowInsets.Type.systemBars())
            case.copy(
                widthDp = ((decor.width - insets.left - insets.right) / density).toInt(),
                heightDp = ((decor.height - insets.top - insets.bottom) / density).toInt(),
            )
        } else case
        compose.runOnIdle { selected.value = current }
        compose.waitForIdle()
        assertEquals(current.expectedScheme, effectiveScheme)
        compose.onNodeWithTag("wp502-screen").assertWidthIsEqualTo(current.widthDp.dp)
        compose.onNodeWithText(originalText.repeat(3), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Synthetic outgoing mesh message", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun resources() = activity.createConfigurationContext(
        Configuration(activity.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(selected.value.locale)))
        },
    ).resources

    fun assertStatusAndSendState(case: ScreenCase) {
        show(case)
        val status = when (case.state) {
            ConversationState.PENDING -> C.chatsMessageStatusSending
            ConversationState.FAILED -> C.chatsMessageStatusFailed
            else -> C.chatsMessageStatusDelivered
        }
        compose.onNodeWithText(resources().getString(status), useUnmergedTree = true).assertIsDisplayed()
        val send = compose.onNodeWithContentDescription(resources().getString(C.chatsInputSendMessage))
        send.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        if (case.state == ConversationState.OFFLINE) send.assertIsNotEnabled() else send.assertIsEnabled()
        compose.onNodeWithTag("wp502-screen").assertHeightIsEqualTo(selected.value.heightDp.dp)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(originalText.repeat(3), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(case.fontScale, layouts.single().layoutInput.density.fontScale, 0f)
        val layout = layouts.single()
        assertFalse("${case.id}: vertical text clipping", layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) assertFalse("${case.id}: ellipsized line $line", layout.isLineEllipsized(line))
        val text = layout.layoutInput.text.text
        // RTL shrink-to-fit may allocate unused paragraph width; visible glyph bounds detect actual clipping.
        val glyphs = text.indices.filter { index ->
            !Character.isWhitespace(text[index]) && !Character.isSpaceChar(text[index]) &&
                Character.getType(text[index]) != Character.FORMAT.toInt()
        }.map { index -> index to layout.getBoundingBox(index) }
        val outside = glyphs.filter { (_, bounds) ->
            bounds.left < 0f || bounds.top < 0f || bounds.right > layout.size.width || bounds.bottom > layout.size.height
        }
        assertTrue(
            "${case.id}: visible glyphs must fit measured text bounds; outside=$outside, " +
                "rtl=${case.rtl}, font=${case.fontScale}, size=${layout.size}, " +
                "constraints=${layout.layoutInput.constraints}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}",
            outside.isEmpty(),
        )
        if (layout.didOverflowWidth) println(
            "WP502_PARAGRAPH_BOUNDS|${case.id}|measured=${layout.size.width}|allocated=${layout.multiParagraph.width}" +
                "|glyphRight=${glyphs.maxOf { it.second.right }}",
        )
    }

    fun draftFocusAndRetrySurviveCurrentWindowChanges(widths: List<Int> = listOf(744, 834, 360)) {
        show(ScreenCase("failed-compact", state = ConversationState.FAILED))
        compose.onNode(hasSetTextAction()).performClick().performTextReplacement(originalText)
        compose.onNode(hasSetTextAction()).assertIsFocused().assertTextContains(originalText)
        compose.onNodeWithText(resources().getString(C.chatsMessageStatusRetry)).performClick()
        assertEquals(1, retries)
        for (width in widths) {
            show(ScreenCase("resize-$width", widthDp = width, state = ConversationState.FAILED))
            compose.onNode(hasSetTextAction()).assertIsFocused().assertTextContains(originalText)
        }
        compose.onNodeWithContentDescription(resources().getString(C.chatsInputSendMessage)).performClick()
        assertEquals(1, sendRequests)
        assertEquals(originalText, compose.runOnIdle { draft.value.text })
    }

    fun offlineAndUtf8OverflowDoNotInvokeSend() {
        assertStatusAndSendState(ScreenCase("offline"))
        compose.onNodeWithContentDescription(resources().getString(C.chatsInputSendMessage)).performClick()
        assertEquals(0, sendRequests)
        show(ScreenCase("connected", state = ConversationState.DELIVERED))
        compose.onNode(hasSetTextAction()).performTextReplacement("\u4e2d".repeat(54))
        compose.onNodeWithContentDescription(resources().getString(C.chatsInputTooLong)).assertIsNotEnabled().performClick()
        assertEquals(0, sendRequests)
        assertEquals(162, compose.runOnIdle { draft.value.text.toByteArray(Charsets.UTF_8).size })
        val boundary = "\u4e2d".repeat(53) + "a"
        compose.onNode(hasSetTextAction()).performTextReplacement(boundary)
        compose.onNodeWithContentDescription(resources().getString(C.chatsInputSendMessage)).assertIsEnabled().performClick()
        assertEquals(1, sendRequests)
        assertEquals(boundary, compose.runOnIdle { draft.value.text })
        assertEquals(160, boundary.toByteArray(Charsets.UTF_8).size)
    }

    fun loadingEmptyAndFailureRemainDistinctAndRetryIsAccessible() {
        show(ScreenCase("timeline-states"))
        compose.runOnIdle { timelineOverride.value = ChatTimelineState(isLoading = true, hasMoreMessages = false) }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        compose.onNodeWithText(resources().getString(C.chatsRowNoMessages)).assertDoesNotExist()
        compose.runOnIdle { timelineOverride.value = ChatTimelineState(hasLoadedOnce = true, hasMoreMessages = false) }
        compose.onNodeWithText(resources().getString(C.chatsRowNoMessages)).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
        compose.runOnIdle {
            timelineOverride.value = ChatTimelineState(
                loadError = IOException("Synthetic unavailable store"), hasMoreMessages = false,
            )
        }

        compose.onNodeWithText(resources().getString(C.chatsErrorLoadConversationsFailed)).assertIsDisplayed()
        compose.onNodeWithText(resources().getString(C.chatsRowNoMessages)).assertDoesNotExist()
        compose.onNodeWithText(resources().getString(L.commonTryAgain)).assertIsEnabled().performClick()
        assertEquals(1, loadRetries)
        compose.onNodeWithContentDescription(resources().getString(C.chatsInputSendMessage)).assertIsNotEnabled()
    }

    @OptIn(ExperimentalTestApi::class)
    fun hardwareReturnRespectsSendGuardsAndMultilineFallback() {
            show(ScreenCase("hardware-return", fontScale = 2f))
            val enabled = ComposerUiState(draft = originalText, maxBytes = 160, isConnected = true)
            val disabled = listOf(
                "disconnected" to enabled.copy(isConnected = false),
                "overflow" to enabled.copy(draft = "\u4e2d".repeat(54)),
                "blank" to enabled.copy(draft = " \t"),
                "cooldown" to enabled.copy(isCoolingDown = true),
                "sending" to enabled.copy(sendPhase = ComposerSendPhase.Sending),
            )
            val differences = mutableListOf<String>()
            var exercised = 0

            fun check(label: String, state: ComposerUiState, key: Key, shift: Boolean, canSend: Boolean) {
                compose.runOnIdle {
                    composerOverride.value = state
                    draft.value = TextFieldValue(state.draft, TextRange(state.draft.length))
                }
                val field = compose.onNode(hasSetTextAction())
                field.performClick().performTextReplacement(state.draft)
                field.assertIsFocused()
                val button = compose.onNodeWithContentDescription(resources().getString(
                    if (label == "overflow") C.chatsInputTooLong else C.chatsInputSendMessage,
                ))
                if (canSend) button.assertIsEnabled() else button.assertIsNotEnabled()
                val before = compose.runOnIdle { sendRequests }
                field.performKeyInput {
                    if (shift) keyDown(Key.ShiftLeft)
                    pressKey(key)
                    if (shift) keyUp(Key.ShiftLeft)
                }
                val (calls, text) = compose.runOnIdle { (sendRequests - before) to draft.value.text }
                val expectedCalls = if (canSend && !shift) 1 else 0
                val expectedText = state.draft + if (expectedCalls == 0) "\n" else ""
                val case = "$label/${if (key == Key.Enter) "Enter" else "NumPadEnter"}/shift=$shift"
                if (calls != expectedCalls) differences += "$case: send callbacks expected=$expectedCalls actual=$calls"
                if (text != expectedText) differences += "$case: normal multiline fallback/draft was not preserved"
                exercised++
                println("WP502_HARDWARE|$case|sendCallbacks=$calls|normalText=${text == expectedText}")
            }

            try {
                for (key in listOf(Key.Enter, Key.NumPadEnter)) {
                    for ((label, state) in disabled) {
                        check(label, state, key, shift = false, canSend = false)
                        check(label, state, key, shift = true, canSend = false)
                    }
                    check("enabled", enabled, key, shift = false, canSend = true)
                    check("enabled", enabled, key, shift = true, canSend = true)
                }
                assertEquals("Every hardware guard/key/modifier family must execute", 24, exercised)
                assertTrue(differences.joinToString("\n"), differences.isEmpty())
            } finally {
                compose.runOnIdle { composerOverride.value = null }
            }
    }

    fun capture(case: ScreenCase, directory: File) {
        assertStatusAndSendState(case)
        captureMountedScreen(case.id, directory)
    }

    fun captureMountedScreen(id: String, directory: File, tag: String = "wp502-screen") {
        compose.mainClock.advanceTimeBy(1_000)
        val first = pixels(tag)
        val second = pixels(tag)
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        assertTrue("Identical settled inputs must reproduce identical pixels: $id", first.sameAs(second))
        val colors = IntArray(first.width * first.height)
        first.getPixels(colors, 0, first.width, 0, 0, first.width, first.height)
        assertTrue("Blank rendering is not evidence", colors.toSet().size > 16)
        val png = ByteArrayOutputStream().use { stream ->
            assertTrue(first.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.toByteArray()
        }
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$id.png").writeBytes(png)
        val digest = MessageDigest.getInstance("SHA-256").digest(png)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        println("WP502_RENDER|$id|${first.width}|${first.height}|$digest|actualWidthDp=${first.width / activity.resources.displayMetrics.density}")
        first.recycle()
        second.recycle()
    }

    private fun pixels(tag: String): Bitmap {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        if (useCurrentDeviceWindow) {
            val rect = Rect(floor(bounds.left).toInt(), floor(bounds.top).toInt(), ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt())
            val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
            val completed = CountDownLatch(1)
            var result = PixelCopy.ERROR_UNKNOWN
            compose.runOnUiThread {
                val decor = activity.window.decorView
                assertTrue(rect.left >= 0 && rect.top >= 0 && rect.right <= decor.width && rect.bottom <= decor.height)
                assertTrue("Device screenshots require the native hardware window", decor.isHardwareAccelerated)
                decor.viewTreeObserver.registerFrameCommitCallback {
                    PixelCopy.request(activity.window, rect, bitmap, {
                        result = it
                        completed.countDown()
                    }, Handler(Looper.getMainLooper()))
                }
                decor.invalidate()
            }
            assertTrue("Native window capture timed out", completed.await(10, TimeUnit.SECONDS))
            assertEquals("Native window capture failed", PixelCopy.SUCCESS, result)
            return bitmap
        }
        return compose.runOnUiThread {
            val decor = activity.window.decorView
            assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= decor.width && bounds.bottom <= decor.height)
            val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(full))
            Bitmap.createBitmap(full, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
                .also { if (it !== full) full.recycle() }
        }
    }
}
