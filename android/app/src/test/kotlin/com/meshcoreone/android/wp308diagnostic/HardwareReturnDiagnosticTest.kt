// AndroidOnly: WP-308 Mounted production composer hardware-key regressions on the existing App native test classpath.
// ReferenceInput: MC1/Views/Chats/Components/ChatInputBar.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.wp308diagnostic

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.feature.chats.composer.ComposerSendPhase
import com.meshcoreone.android.feature.chats.composer.ComposerUiState
import com.meshcoreone.android.feature.chats.composer.ui.ComposerBar
import com.meshcoreone.android.feature.chats.composer.ui.ComposerBarActions
import com.meshcoreone.android.feature.chats.composer.ui.ShareAvailability
import org.junit.After
import org.junit.Assert.assertEquals
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
@Config(sdk = [31, 37], qualifiers = "w1080dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class HardwareReturnDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val state = mutableStateOf(ComposerUiState(maxBytes = 160, isConnected = true))
    private val field = mutableStateOf(TextFieldValue())
    private var sendRequests = 0
    private val enterKeys = listOf(Key.Enter, Key.NumPadEnter)

    @Before
    fun mount() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.get().setContent {
            MeshCoreTheme {
                ComposerBar(
                    state = state.value.copy(draft = field.value.text),
                    fieldValue = field.value,
                    placeholder = "",
                    share = ShareAvailability(location = false, myInfo = false),
                    actions = ComposerBarActions(
                        onFieldValueChange = { field.value = it },
                        onSend = { sendRequests++ },
                        onShareLocation = { error("Unexpected location action") },
                        onShareContact = { error("Unexpected contact action") },
                        onShareMyInfo = { error("Unexpected node-info action") },
                        onOpenEmoji = null,
                        onDismissFailure = {},
                    ),
                    focusRequester = remember { FocusRequester() },
                )
            }
        }
    }

    @After
    fun close() {
        host.pause().stop().destroy()
    }

    private fun select(value: ComposerUiState) {
        compose.runOnIdle {
            state.value = value
            field.value = TextFieldValue(value.draft, TextRange(value.draft.length))
            sendRequests = 0
        }
        compose.onNode(hasSetTextAction()).performClick().assertIsFocused()
        compose.runOnIdle {
            field.value = field.value.copy(selection = TextRange(field.value.text.length))
        }
        val label = if (value.isOverLimit) AppChatsStrings.chatsInputTooLong else AppChatsStrings.chatsInputSendMessage
        val send = compose.onNodeWithContentDescription(host.get().getString(label))
        if (value.canSend) send.assertIsEnabled() else send.assertIsNotEnabled()
    }

    @Test
    fun disabledReturnEditsWithoutInvokingSend() {
        val connected = ComposerUiState(draft = "Retained draft", maxBytes = 160, isConnected = true)
        val disabled = listOf(
            connected.copy(isConnected = false),
            connected.copy(draft = ""),
            connected.copy(draft = "\u4e2d".repeat(54)),
            connected.copy(isCoolingDown = true),
            connected.copy(sendPhase = ComposerSendPhase.Sending),
        )
        for (value in disabled) {
            for (key in enterKeys) {
                select(value)
                compose.onNode(hasSetTextAction()).performKeyInput { pressKey(key) }
                compose.runOnIdle {
                    assertEquals("Disabled $value / $key must not invoke send", 0, sendRequests)
                    assertEquals("Disabled $value / $key must preserve newline editing", value.draft + "\n", field.value.text)
                    assertEquals(TextRange(value.draft.length + 1), field.value.selection)
                }
            }
        }
    }

    @Test
    fun validReturnSendsOnlyOnKeyDownWithoutEditingTheDraft() {
        val value = ComposerUiState(draft = "Valid draft", maxBytes = 160, isConnected = true)
        for (key in enterKeys) {
            select(value)
            compose.onNode(hasSetTextAction()).performKeyInput { keyDown(key) }
            compose.runOnIdle {
                assertEquals(1, sendRequests)
                assertEquals(value.draft, field.value.text)
            }
            compose.onNode(hasSetTextAction()).performKeyInput { keyUp(key) }
            compose.runOnIdle {
                assertEquals("KeyUp must not send twice", 1, sendRequests)
                assertEquals(value.draft, field.value.text)
            }
        }
    }

    @Test
    fun shiftReturnEditsWithoutInvokingSendEvenWhenSendIsEnabled() {
        val connected = ComposerUiState(draft = "Shift draft", maxBytes = 160, isConnected = true)
        for (value in listOf(connected, connected.copy(isConnected = false))) {
            for (key in enterKeys) {
                select(value)
                compose.onNode(hasSetTextAction()).performKeyInput {
                    keyDown(Key.ShiftLeft)
                    pressKey(key)
                    keyUp(Key.ShiftLeft)
                }
                compose.runOnIdle {
                    assertEquals(0, sendRequests)
                    assertEquals(value.draft + "\n", field.value.text)
                }
            }
        }
    }
}
