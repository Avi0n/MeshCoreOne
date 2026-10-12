// AndroidOnly: WP-502 Real production timeline/composer rendering with fixed synthetic data and explicit service-state inputs.
// PortedFrom: MC1/Views/Chats/Components/ChatInputBar.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ChatConversationMessagesContent.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.*
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as C
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.feature.chats.composer.ComposerUiState
import com.meshcoreone.android.feature.chats.composer.ui.ComposerBar
import com.meshcoreone.android.feature.chats.composer.ui.ComposerBarActions
import com.meshcoreone.android.feature.chats.composer.ui.ShareAvailability
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimeline
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineCallbacks
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
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
    private var effectiveScheme: ColorScheme? = null
    private var retries = 0
    private var sendRequests = 0

    fun mount() {
        setContent {
            val case = selected.value
            val context = LocalContext.current
            val configuration = Configuration(context.resources.configuration).apply {
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
                    Surface {
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
                                ChatTimelineState(
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
                                    onRetryLoad = { error("Fixture loaded successfully") },
                                ),
                                Modifier.weight(1f),
                            )
                            ComposerBar(
                                state = ComposerUiState(
                                    draft = draft.value.text, maxBytes = 160,
                                    isConnected = case.state != ConversationState.OFFLINE,
                                ),
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
            case.copy(widthDp = (decor.width / density).toInt(), heightDp = (decor.height / density).toInt())
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
        if (case.state == ConversationState.OFFLINE) send.assertIsNotEnabled() else send.assertIsEnabled()
        compose.onNodeWithTag("wp502-screen").assertHeightIsEqualTo(selected.value.heightDp.dp)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(originalText.repeat(3), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(case.fontScale, layouts.single().layoutInput.density.fontScale, 0f)
        assertFalse("Original text must wrap, not ellipsize or overflow", layouts.single().hasVisualOverflow)
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
    }

    fun capture(case: ScreenCase, directory: File) {
        assertStatusAndSendState(case)
        val first = pixels()
        val second = pixels()
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        assertTrue("Identical settled inputs must reproduce identical pixels: ${case.id}", first.sameAs(second))
        val colors = IntArray(first.width * first.height)
        first.getPixels(colors, 0, first.width, 0, 0, first.width, first.height)
        assertTrue("Blank rendering is not evidence", colors.toSet().size > 16)
        val png = ByteArrayOutputStream().use { stream ->
            assertTrue(first.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.toByteArray()
        }
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "${case.id}.png").writeBytes(png)
        val digest = MessageDigest.getInstance("SHA-256").digest(png)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        println("WP502_RENDER|${case.id}|${first.width}|${first.height}|$digest")
        first.recycle()
        second.recycle()
    }

    private fun pixels(): Bitmap {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("wp502-screen").fetchSemanticsNode().boundsInWindow
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
