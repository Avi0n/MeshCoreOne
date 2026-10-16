package one.meshcore.shared.cli.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.remotenode.CLIOutputLine
import one.meshcore.shared.remotenode.CLIOutputType
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_cli_paste
import one.meshcore.shared.ui.HoldingClipboard
import one.meshcore.shared.ui.plainTextClipEntry
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CliClipboardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val input = mutableStateOf(TextFieldValue(""))
    private val applied = mutableListOf<String>()
    private val ghostAtEnd = mutableListOf<Boolean>()
    private lateinit var clipboard: HoldingClipboard
    private lateinit var pasteLabel: String
    private var copiedBlock = COPIED_BLOCK

    @After
    fun releaseWrite() {
        if (::clipboard.isInitialized) clipboard.release.complete(Unit)
    }

    @Test
    fun longPressCopiesTheBlockCapturedAtThePress() {
        show()
        longPressLine()
        compose.runOnIdle {
            val text = requireNotNull(clipboard.entries.single()).clipData.getItemAt(0).text.toString()
            assertEquals(COPIED_BLOCK, text)
            assertEquals(0, clipboard.completed)
            assertEquals(0, clipboard.reads)
            copiedBlock = CHANGED_BLOCK
        }
        release()
        compose.runOnIdle {
            assertEquals(1, clipboard.completed)
            assertEquals(0, clipboard.cancelled)
            val text = requireNotNull(clipboard.entries.single()).clipData.getItemAt(0).text.toString()
            assertEquals(COPIED_BLOCK, text)
            assertTrue(applied.isEmpty())
        }
    }

    @Test
    fun leavingThePaneCancelsAPendingCopy() {
        show()
        longPressLine()
        compose.runOnIdle { assertEquals(1, clipboard.entries.size) }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, clipboard.completed) }
    }

    @Test
    fun pasteInsertsTheLatestInputAfterTheReadReturns() {
        input.value = TextFieldValue(INITIAL_INPUT, TextRange(CURSOR))
        show(readsAllowed = true, readEntry = plainTextClipEntry(PASTED))
        clickPaste()
        compose.runOnIdle {
            assertEquals(1, clipboard.reads)
            assertEquals(0, clipboard.completed)
            assertEquals(INITIAL_INPUT, input.value.text)
            input.value = TextFieldValue(EDITED_INPUT, TextRange(EDITED_INPUT.length))
        }
        compose.waitForIdle()
        release()
        compose.runOnIdle {
            assertEquals(EDITED_INPUT + PASTED, input.value.text)
            assertEquals(TextRange((EDITED_INPUT + PASTED).length), input.value.selection)
            assertEquals(listOf(EDITED_INPUT + PASTED), applied)
            assertEquals(listOf(true), ghostAtEnd)
        }
    }

    @Test
    fun leavingThePaneCancelsAPendingPaste() {
        input.value = TextFieldValue(INITIAL_INPUT, TextRange(CURSOR))
        show(readsAllowed = true, readEntry = plainTextClipEntry(PASTED))
        clickPaste()
        compose.runOnIdle {
            assertEquals(1, clipboard.reads)
            assertEquals(INITIAL_INPUT, input.value.text)
        }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            assertEquals(INITIAL_INPUT, input.value.text)
            assertTrue(applied.isEmpty())
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, clipboard.completed)
            assertEquals(INITIAL_INPUT, input.value.text)
        }
    }

    @Test
    fun emptyClipboardLeavesTheInputUnchanged() {
        input.value = TextFieldValue(INITIAL_INPUT, TextRange(CURSOR))
        show(readsAllowed = true, readEntry = null)
        clickPaste()
        release()
        compose.runOnIdle {
            assertEquals(1, clipboard.reads)
            assertEquals(INITIAL_INPUT, input.value.text)
            assertTrue(applied.isEmpty())
        }
        clipboard.readEntry = plainTextClipEntry("")
        clickPaste()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, clipboard.reads)
            assertEquals(2, clipboard.completed)
            assertEquals(INITIAL_INPUT, input.value.text)
            assertTrue(applied.isEmpty())
        }
    }

    private fun show(readsAllowed: Boolean = false, readEntry: ClipEntry? = null) {
        clipboard = HoldingClipboard().also {
            it.readsAllowed = readsAllowed
            it.readEntry = readEntry
        }
        compose.setContent {
            pasteLabel = stringResource(Res.string.tools_cli_paste)
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                MaterialTheme {
                    if (visible.value) {
                        CliTerminalPane(
                            outputLines = listOf(CLIOutputLine(LINE_ID, LINE_TEXT, CLIOutputType.RESPONSE)),
                            promptText = PROMPT,
                            ghostText = "",
                            tabSuggestions = null,
                            tabSelectionIndex = null,
                            isWaitingForResponse = false,
                            input = input.value,
                            onInputChange = { input.value = it },
                            actions = CliTerminalActions(
                                onSubmit = {},
                                onTab = {},
                                onHistoryUp = {},
                                onHistoryDown = {},
                                onAcceptGhost = {},
                                onUpdateGhostText = { ghostAtEnd += it },
                                onSetInput = { applied += it },
                                onCancel = {},
                                onClear = {},
                                onCopyBlock = { copiedBlock },
                                onApplySuggestion = {},
                            ),
                            showSessionsButton = false,
                        )
                    }
                }
            }
        }
    }

    private fun longPressLine() {
        compose.onNodeWithText(LINE_TEXT).performTouchInput { longClick() }
    }

    private fun clickPaste() {
        compose.onNodeWithContentDescription(pasteLabel).performScrollTo().performClick()
    }

    private fun release() {
        compose.runOnIdle { clipboard.release.complete(Unit) }
        compose.waitForIdle()
    }

    private companion object {
        const val LINE_ID = 1L
        const val LINE_TEXT = "MeshCore"
        const val COPIED_BLOCK = "name\nMeshCore"
        const val CHANGED_BLOCK = "changed"
        const val INITIAL_INPUT = "ab"
        const val EDITED_INPUT = "abcd"
        const val PASTED = "XY"
        const val CURSOR = 1
        const val PROMPT = ">"
    }
}
