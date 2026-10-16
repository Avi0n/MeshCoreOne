package one.meshcore.shared.cli.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.ui.cliImePadding
import one.meshcore.shared.chat.ui.currentImeBottomPx
import one.meshcore.shared.chat.ui.navigationInsetOutsideIme
import one.meshcore.shared.remotenode.CLIOutputLine
import one.meshcore.shared.ui.plainText
import one.meshcore.shared.ui.plainTextClipEntry

/**
 * Last LazyColumn index when output is followed by the inline prompt and optional tab suggestions.
 * Follow-scroll and jump-to-bottom target this index so the caret stays in view, not a pinned composer.
 */
internal fun cliTerminalLastIndex(outputCount: Int, hasSuggestions: Boolean): Int {
    val promptIndex = outputCount
    return if (hasSuggestions) promptIndex + 1 else promptIndex
}

private fun cliTerminalShouldRequestFocus(
    previousWaiting: Boolean?,
    isWaiting: Boolean,
    autoFocusOnMount: Boolean,
): Boolean = !isWaiting && (previousWaiting == true || (previousWaiting == null && autoFocusOnMount))

/** Actions the Tools and node/room terminals share with [CliTerminalPane]. */
internal data class CliTerminalActions(
    val onSubmit: () -> Unit,
    val onTab: () -> Unit,
    val onHistoryUp: () -> Unit,
    val onHistoryDown: () -> Unit,
    val onAcceptGhost: () -> Unit,
    val onUpdateGhostText: (cursorAtEnd: Boolean) -> Unit,
    val onSetInput: (String) -> Unit,
    val onCancel: () -> Unit,
    val onClear: () -> Unit,
    val onCopyBlock: (CLIOutputLine) -> String,
    val onApplySuggestion: (Int) -> Unit,
    val onSessions: () -> Unit = {},
    val onPreviewKey: (KeyEvent) -> Boolean = { false },
)

/**
 * Shared terminal chrome: scrollback, then the prompt on the last line, then optional tab chips.
 * A 1 dp capture field stays composed so scrolling up does not dispose IME state. Extra-keys stay
 * a Material toolbar under the buffer (Termux-style), not a chat composer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CliTerminalPane(
    outputLines: List<CLIOutputLine>,
    promptText: String,
    ghostText: String,
    tabSuggestions: List<String>?,
    tabSelectionIndex: Int?,
    isWaitingForResponse: Boolean,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    actions: CliTerminalActions,
    modifier: Modifier = Modifier,
    showSessionsButton: Boolean = true,
    autoFocusOnMount: Boolean = true,
) {
    val clipboard = LocalClipboard.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val latestInput = rememberUpdatedState(input)
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var captureFocused by remember { mutableStateOf(false) }
    var previousWaiting by remember { mutableStateOf<Boolean?>(null) }
    val hasSuggestions = tabSuggestions != null
    val lastIndex = cliTerminalLastIndex(outputLines.size, hasSuggestions)
    val atBottom by remember {
        derivedStateOf { listState.cliOutputIsAtBottom() }
    }
    val imeBottom = currentImeBottomPx()
    val navigationBottom = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    val navigationInset = cliTerminalNavigationInset(imeBottom, navigationBottom)
    LaunchedEffect(outputLines.size, hasSuggestions, imeBottom, atBottom) {
        if (atBottom) listState.scrollToItem(lastIndex)
    }
    LaunchedEffect(isWaitingForResponse) {
        val requestFocus = cliTerminalShouldRequestFocus(previousWaiting, isWaitingForResponse, autoFocusOnMount)
        previousWaiting = isWaitingForResponse
        if (requestFocus) focusRequester.requestFocus()
    }

    val requestCapture: () -> Unit = {
        if (!isWaitingForResponse) focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(TERMINAL_PADDING)
            .padding(bottom = with(LocalDensity.current) { navigationInset.toDp() })
            .cliImePadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = requestCapture,
            ),
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(CLI_TERMINAL_LINE_SPACING),
            ) {
                items(outputLines, key = { it.id }) { line ->
                    CliOutputRow(
                        line = line,
                        onClick = requestCapture,
                        onCopy = {
                            val text = actions.onCopyBlock(line)
                            scope.launch {
                                clipboard.setClipEntry(plainTextClipEntry(text))
                            }
                        },
                    )
                }
                item(key = PROMPT_ITEM_KEY) {
                    CliInlinePrompt(
                        promptText = promptText,
                        input = input,
                        ghostText = ghostText,
                        isWaiting = isWaitingForResponse,
                        showCaret = captureFocused && !isWaitingForResponse,
                    )
                }
                if (tabSuggestions != null) {
                    item(key = SUGGESTIONS_ITEM_KEY) {
                        CliTabSuggestionStrip(
                            suggestions = tabSuggestions,
                            selectedIndex = tabSelectionIndex,
                            onSelect = actions.onApplySuggestion,
                        )
                    }
                }
            }
            if (listState.canScrollForward) {
                CliJumpToBottomButton(
                    onClick = { scope.launch { listState.scrollToItem(lastIndex) } },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            CliCaptureField(
                input = input,
                enabled = !isWaitingForResponse,
                focusRequester = focusRequester,
                onInputChange = onInputChange,
                onFocusChange = { captureFocused = it },
                onSubmit = actions.onSubmit,
                onPreviewKey = { event ->
                    if (actions.onPreviewKey(event)) {
                        true
                    } else {
                        handleCliCaptureKey(
                            event = event,
                            ghostText = ghostText,
                            actions = actions,
                        )
                    }
                },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
        CliInputAccessory(
            isWaiting = isWaitingForResponse,
            onTab = actions.onTab,
            onHistoryUp = actions.onHistoryUp,
            onHistoryDown = actions.onHistoryDown,
            onMoveLeft = {
                val pos = input.selection.start
                if (pos > 0) {
                    onInputChange(input.copy(selection = TextRange(pos - 1)))
                    actions.onUpdateGhostText(false)
                }
            },
            onMoveRight = {
                val pos = input.selection.start
                if (ghostText.isNotEmpty() && pos >= input.text.length) {
                    actions.onAcceptGhost()
                } else if (pos < input.text.length) {
                    val next = pos + 1
                    onInputChange(input.copy(selection = TextRange(next)))
                    actions.onUpdateGhostText(next == input.text.length)
                }
            },
            onPaste = {
                scope.launch {
                    val pasted = clipboard.getClipEntry()?.plainText()
                    if (pasted.isNullOrEmpty()) return@launch
                    val current = latestInput.value
                    val pos = current.selection.start.coerceIn(0, current.text.length)
                    val merged = current.text.substring(0, pos) + pasted + current.text.substring(pos)
                    val cursor = pos + pasted.length
                    onInputChange(TextFieldValue(merged, TextRange(cursor)))
                    actions.onSetInput(merged)
                    actions.onUpdateGhostText(cursor == merged.length)
                }
            },
            onCancel = actions.onCancel,
            onDismiss = { keyboard?.hide() },
            onClear = actions.onClear,
            showSessionsButton = showSessionsButton,
            onSessions = actions.onSessions,
        )
    }
}

/** The IME already includes the bottom system-navigation area, so do not reserve it twice. */
internal fun cliTerminalNavigationInset(imeBottom: Int, navigationBottom: Int): Int =
    navigationInsetOutsideIme(imeBottom, navigationBottom)

private const val PROMPT_ITEM_KEY = "prompt"
private const val SUGGESTIONS_ITEM_KEY = "suggestions"
private val TERMINAL_PADDING = 12.dp
