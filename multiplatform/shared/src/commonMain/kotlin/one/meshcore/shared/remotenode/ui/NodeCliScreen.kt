package one.meshcore.shared.remotenode.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.cli.ui.CliTerminalActions
import one.meshcore.shared.cli.ui.CliTerminalPane
import one.meshcore.shared.cli.ui.cliCursorAtEnd
import one.meshcore.shared.cli.ui.rewrittenCliField
import one.meshcore.shared.remotenode.NodeCliMessages
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_node_cli_banner_connected
import one.meshcore.shared.resources.remotenodes_remote_nodes_node_cli_banner_hint
import one.meshcore.shared.resources.remotenodes_remote_nodes_node_cli_cancelled
import one.meshcore.shared.resources.remotenodes_remote_nodes_node_cli_help_clear
import one.meshcore.shared.resources.remotenodes_remote_nodes_node_cli_help_clear_stats
import one.meshcore.shared.resources.remotenodes_remote_nodes_node_cli_help_header
import one.meshcore.shared.resources.remotenodes_remote_nodes_node_cli_help_help
import one.meshcore.shared.resources.remotenodes_remote_nodes_node_cli_help_passthrough
import one.meshcore.shared.resources.remotenodes_remote_nodes_node_cli_help_reboot
import one.meshcore.shared.resources.remotenodes_remote_nodes_node_cli_reboot_sent
import one.meshcore.shared.resources.tools_cli_prompt_suffix
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.stringResource

/**
 * Builds the localized [NodeCliMessages] the shared [one.meshcore.shared.remotenode.NodeCliSession] emits,
 * resolving the `remotenodes_*` CLI keys. Mirrors the iOS `NodeCLIViewModel` banner and `help` block so the
 * terminal output matches the shipping app.
 */
@Composable
fun rememberNodeCliMessages(): NodeCliMessages {
    val bannerConnectedTemplate = stringResource(Res.string.remotenodes_remote_nodes_node_cli_banner_connected)
    val bannerHint = stringResource(Res.string.remotenodes_remote_nodes_node_cli_banner_hint)
    val cancelled = stringResource(Res.string.remotenodes_remote_nodes_node_cli_cancelled)
    val rebootSent = stringResource(Res.string.remotenodes_remote_nodes_node_cli_reboot_sent)
    val promptSuffix = stringResource(Res.string.tools_cli_prompt_suffix)
    val help = listOf(
        stringResource(Res.string.remotenodes_remote_nodes_node_cli_help_header),
        stringResource(Res.string.remotenodes_remote_nodes_node_cli_help_help),
        stringResource(Res.string.remotenodes_remote_nodes_node_cli_help_clear),
        stringResource(Res.string.remotenodes_remote_nodes_node_cli_help_clear_stats),
        stringResource(Res.string.remotenodes_remote_nodes_node_cli_help_reboot),
        stringResource(Res.string.remotenodes_remote_nodes_node_cli_help_passthrough),
    )
    return remember(bannerConnectedTemplate, bannerHint, cancelled, rebootSent, promptSuffix, help) {
        NodeCliMessages(
            bannerConnected = { name -> formatTemplate(bannerConnectedTemplate, listOf(name)) },
            bannerHint = bannerHint,
            cancelled = cancelled,
            rebootSent = rebootSent,
            promptSuffix = promptSuffix,
            help = help,
        )
    }
}

/**
 * The node CLI terminal, ported from the iOS `NodeCLIView`. Scrollback, then the prompt on the last
 * line, with ghost completion, history, and tab cycling through [NodeCliViewModel]. Extra-keys hide
 * Sessions the same way iOS `NodeCLIView` does. Long-press copies the full response block.
 */
@Composable
fun NodeCliScreen(viewModel: NodeCliViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf(TextFieldValue()) }

    // Keep the local field in sync when the view model rewrites the input (history, completion, submit).
    LaunchedEffect(state.currentInput) {
        rewrittenCliField(state.currentInput, input.text)?.let { input = it }
    }

    CliTerminalPane(
        outputLines = state.outputLines,
        promptText = state.promptText,
        ghostText = state.ghostText,
        tabSuggestions = state.tabSuggestions,
        tabSelectionIndex = state.tabSelectionIndex,
        isWaitingForResponse = state.isWaitingForResponse,
        input = input,
        onInputChange = {
            input = it
            viewModel.setInput(it.text)
            viewModel.updateGhostText(
                cursorAtEnd = cliCursorAtEnd(it.selection.collapsed, it.selection.start, it.text.length),
            )
        },
        actions = CliTerminalActions(
            onSubmit = { viewModel.submit() },
            onTab = { viewModel.tabComplete() },
            onHistoryUp = { viewModel.historyUp() },
            onHistoryDown = { viewModel.historyDown() },
            onAcceptGhost = { viewModel.acceptGhostText() },
            onUpdateGhostText = { viewModel.updateGhostText(cursorAtEnd = it) },
            onSetInput = { viewModel.setInput(it) },
            onCancel = { viewModel.cancelCurrentCommand() },
            onClear = { viewModel.clear() },
            onCopyBlock = { viewModel.getResponseBlock(it) },
            onApplySuggestion = { viewModel.applySuggestionAt(it) },
        ),
        modifier = modifier,
        showSessionsButton = false,
    )
}
