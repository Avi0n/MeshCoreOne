package one.meshcore.shared.tools.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.cli.ui.CliTerminalActions
import one.meshcore.shared.cli.ui.CliTerminalPane
import one.meshcore.shared.cli.ui.cliCursorAtEnd
import one.meshcore.shared.cli.ui.rewrittenCliField
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_node_kind_repeater
import one.meshcore.shared.resources.contacts_node_kind_room
import one.meshcore.shared.resources.contacts_route_direct
import one.meshcore.shared.resources.contacts_route_flood
import one.meshcore.shared.resources.contacts_route_hops
import one.meshcore.shared.resources.tools_cli_cancelled
import one.meshcore.shared.resources.tools_cli_channel_empty
import one.meshcore.shared.resources.tools_cli_channels_header
import one.meshcore.shared.resources.tools_cli_command_timeout
import one.meshcore.shared.resources.tools_cli_confirm_prompt
import one.meshcore.shared.resources.tools_cli_default_device
import one.meshcore.shared.resources.tools_cli_disconnected
import one.meshcore.shared.resources.tools_cli_help_channels
import one.meshcore.shared.resources.tools_cli_help_clear
import one.meshcore.shared.resources.tools_cli_help_header
import one.meshcore.shared.resources.tools_cli_help_help
import one.meshcore.shared.resources.tools_cli_help_local_header
import one.meshcore.shared.resources.tools_cli_help_local_list1
import one.meshcore.shared.resources.tools_cli_help_local_list2
import one.meshcore.shared.resources.tools_cli_help_local_list3
import one.meshcore.shared.resources.tools_cli_help_local_list4
import one.meshcore.shared.resources.tools_cli_help_local_list5
import one.meshcore.shared.resources.tools_cli_help_login
import one.meshcore.shared.resources.tools_cli_help_logout
import one.meshcore.shared.resources.tools_cli_help_nodes
import one.meshcore.shared.resources.tools_cli_help_repeater_header
import one.meshcore.shared.resources.tools_cli_help_repeater_list1
import one.meshcore.shared.resources.tools_cli_help_repeater_list2
import one.meshcore.shared.resources.tools_cli_help_repeater_list3
import one.meshcore.shared.resources.tools_cli_help_repeater_list4
import one.meshcore.shared.resources.tools_cli_help_session_list
import one.meshcore.shared.resources.tools_cli_help_session_local
import one.meshcore.shared.resources.tools_cli_help_session_name
import one.meshcore.shared.resources.tools_cli_help_session_shortcut
import one.meshcore.shared.resources.tools_cli_invalid_custom_var_token
import one.meshcore.shared.resources.tools_cli_invalid_value
import one.meshcore.shared.resources.tools_cli_logging_in
import one.meshcore.shared.resources.tools_cli_login_failed
import one.meshcore.shared.resources.tools_cli_login_failed_auth
import one.meshcore.shared.resources.tools_cli_login_from_local_only
import one.meshcore.shared.resources.tools_cli_login_success
import one.meshcore.shared.resources.tools_cli_login_usage
import one.meshcore.shared.resources.tools_cli_logout_success
import one.meshcore.shared.resources.tools_cli_no_channels
import one.meshcore.shared.resources.tools_cli_no_nodes
import one.meshcore.shared.resources.tools_cli_node_not_found
import one.meshcore.shared.resources.tools_cli_nodes_header
import one.meshcore.shared.resources.tools_cli_not_connected
import one.meshcore.shared.resources.tools_cli_not_logged_in
import one.meshcore.shared.resources.tools_cli_password_prompt
import one.meshcore.shared.resources.tools_cli_password_required
import one.meshcore.shared.resources.tools_cli_prompt_suffix
import one.meshcore.shared.resources.tools_cli_reboot_sent
import one.meshcore.shared.resources.tools_cli_session_list_header
import one.meshcore.shared.resources.tools_cli_session_local
import one.meshcore.shared.resources.tools_cli_session_not_found
import one.meshcore.shared.resources.tools_cli_session_switched
import one.meshcore.shared.resources.tools_cli_timeout
import one.meshcore.shared.resources.tools_cli_unknown_command
import one.meshcore.shared.resources.tools_cli_usage_get
import one.meshcore.shared.resources.tools_cli_usage_set
import one.meshcore.shared.resources.tools_cli_usage_set_radio
import one.meshcore.shared.resources.tools_cli_welcome_connected
import one.meshcore.shared.resources.tools_cli_welcome_hint
import one.meshcore.shared.resources.tools_cli_welcome_line1
import one.meshcore.shared.tools.ToolsCliLocalBridge
import one.meshcore.shared.tools.ToolsCliMessages
import one.meshcore.shared.tools.ToolsCliRemoteBridge
import one.meshcore.shared.tools.ToolsCliSession
import one.meshcore.shared.tools.ToolsCliTerminalHost
import one.meshcore.shared.tools.ToolsCliTerminalKey
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.rememberMeshBridgeErrorText
import org.jetbrains.compose.resources.stringResource

@Composable
fun rememberToolsCliMessages(): ToolsCliMessages {
    val welcomeConnectedTemplate = stringResource(Res.string.tools_cli_welcome_connected)
    val nodesHeaderTemplate = stringResource(Res.string.tools_cli_nodes_header)
    val channelsHeaderTemplate = stringResource(Res.string.tools_cli_channels_header)
    val hopsTemplate = stringResource(Res.string.contacts_route_hops)
    val confirmPromptTemplate = stringResource(Res.string.tools_cli_confirm_prompt)
    val loggingInTemplate = stringResource(Res.string.tools_cli_logging_in)
    val helpCommon = listOf(
        stringResource(Res.string.tools_cli_help_header),
        stringResource(Res.string.tools_cli_help_login),
        stringResource(Res.string.tools_cli_help_logout),
        stringResource(Res.string.tools_cli_help_session_list),
        stringResource(Res.string.tools_cli_help_session_local),
        stringResource(Res.string.tools_cli_help_session_name),
        stringResource(Res.string.tools_cli_help_session_shortcut),
        stringResource(Res.string.tools_cli_help_clear),
        stringResource(Res.string.tools_cli_help_help),
    )
    val helpLocal = listOf(
        stringResource(Res.string.tools_cli_help_nodes),
        stringResource(Res.string.tools_cli_help_channels),
        "",
        stringResource(Res.string.tools_cli_help_local_header),
        stringResource(Res.string.tools_cli_help_local_list1),
        stringResource(Res.string.tools_cli_help_local_list2),
        stringResource(Res.string.tools_cli_help_local_list3),
        stringResource(Res.string.tools_cli_help_local_list4),
        stringResource(Res.string.tools_cli_help_local_list5),
    )
    val helpRepeater = listOf(
        "",
        stringResource(Res.string.tools_cli_help_repeater_header),
        stringResource(Res.string.tools_cli_help_repeater_list1),
        stringResource(Res.string.tools_cli_help_repeater_list2),
        stringResource(Res.string.tools_cli_help_repeater_list3),
        stringResource(Res.string.tools_cli_help_repeater_list4),
    )
    return ToolsCliMessages(
        welcomeLine1 = stringResource(Res.string.tools_cli_welcome_line1),
        welcomeConnected = { name -> formatTemplate(welcomeConnectedTemplate, listOf(name)) },
        welcomeHint = stringResource(Res.string.tools_cli_welcome_hint),
        promptSuffix = stringResource(Res.string.tools_cli_prompt_suffix),
        unknownCommand = stringResource(Res.string.tools_cli_unknown_command),
        notConnected = stringResource(Res.string.tools_cli_not_connected),
        disconnected = stringResource(Res.string.tools_cli_disconnected),
        nodesHeader = { count -> formatTemplate(nodesHeaderTemplate, listOf(count)) },
        channelsHeader = { count -> formatTemplate(channelsHeaderTemplate, listOf(count)) },
        noNodes = stringResource(Res.string.tools_cli_no_nodes),
        noChannels = stringResource(Res.string.tools_cli_no_channels),
        channelEmpty = stringResource(Res.string.tools_cli_channel_empty),
        kindRepeater = stringResource(Res.string.contacts_node_kind_repeater),
        kindRoom = stringResource(Res.string.contacts_node_kind_room),
        routeFlood = stringResource(Res.string.contacts_route_flood),
        routeDirect = stringResource(Res.string.contacts_route_direct),
        routeHops = { hops -> formatTemplate(hopsTemplate, listOf(hops)) },
        helpCommonLines = helpCommon,
        helpLocalLines = helpLocal,
        helpRepeaterLines = helpRepeater,
        sessionListHeader = stringResource(Res.string.tools_cli_session_list_header),
        sessionLocal = stringResource(Res.string.tools_cli_session_local),
        sessionSwitched = stringResource(Res.string.tools_cli_session_switched),
        remoteLoginHint = stringResource(Res.string.tools_cli_login_from_local_only),
        loginUsage = stringResource(Res.string.tools_cli_login_usage),
        nodeNotFound = stringResource(Res.string.tools_cli_node_not_found),
        passwordRequired = stringResource(Res.string.tools_cli_password_required),
        passwordPrompt = stringResource(Res.string.tools_cli_password_prompt),
        loggingIn = { seconds -> formatTemplate(loggingInTemplate, listOf(seconds)) },
        loginSuccess = stringResource(Res.string.tools_cli_login_success),
        loginFailed = stringResource(Res.string.tools_cli_login_failed),
        loginFailedAuth = stringResource(Res.string.tools_cli_login_failed_auth),
        logoutSuccess = stringResource(Res.string.tools_cli_logout_success),
        notLoggedIn = stringResource(Res.string.tools_cli_not_logged_in),
        sessionNotFound = stringResource(Res.string.tools_cli_session_not_found),
        timeout = stringResource(Res.string.tools_cli_timeout),
        deviceErrorText = rememberMeshBridgeErrorText(),
        commandTimeout = stringResource(Res.string.tools_cli_command_timeout),
        rebootSent = stringResource(Res.string.tools_cli_reboot_sent),
        cancelled = stringResource(Res.string.tools_cli_cancelled),
        usageGet = stringResource(Res.string.tools_cli_usage_get),
        usageSet = stringResource(Res.string.tools_cli_usage_set),
        usageSetRadio = stringResource(Res.string.tools_cli_usage_set_radio),
        invalidValue = stringResource(Res.string.tools_cli_invalid_value),
        invalidCustomVarToken = stringResource(Res.string.tools_cli_invalid_custom_var_token),
        confirmPrompt = { displayName -> formatTemplate(confirmPromptTemplate, listOf(displayName)) },
    )
}

/**
 * Tools → CLI terminal: local commands plus optional remote login/passthrough via [remote].
 */
@Composable
fun ToolsCliScreen(
    deviceName: String,
    fetchContacts: suspend () -> List<ContactDto>?,
    fetchChannels: suspend () -> List<ChannelDto>?,
    modifier: Modifier = Modifier,
    remote: ToolsCliRemoteBridge? = null,
    local: ToolsCliLocalBridge? = null,
    claimInitialKeyboardFocus: () -> Boolean = { true },
) {
    val messages = rememberToolsCliMessages()
    val defaultDevice = stringResource(Res.string.tools_cli_default_device)
    val name = deviceName.ifBlank { defaultDevice }
    // Held outside the composition so scrollback, history, and remote logins survive leaving the tool;
    // the key resets it on reconnect or a radio switch, as iOS resets its AppState-held view model.
    val viewModel = remember(deviceName, remote, local) {
        ToolsCliTerminalHost.terminal(ToolsCliTerminalKey(deviceName, remote, local)) { retainedScope ->
            ToolsCliViewModel(
                scope = retainedScope,
                deviceName = name,
                messages = messages,
                fetchContacts = fetchContacts,
                fetchChannels = fetchChannels,
                options = ToolsCliSession.Options(remote = remote, local = local),
            )
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val autoFocusOnMount = remember(viewModel) { claimInitialKeyboardFocus() }
    var input by remember { mutableStateOf(TextFieldValue()) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(viewModel) { viewModel.refreshCompletionCatalog() }
    LaunchedEffect(viewModel, name, messages) { viewModel.refreshLocalizedCopy(name, messages) }

    LaunchedEffect(state.currentInput) {
        rewrittenCliField(state.currentInput, input.text)?.let { input = it }
    }

    CliTerminalPane(
        autoFocusOnMount = autoFocusOnMount,
        outputLines = state.outputLines,
        promptText = state.promptText,
        ghostText = state.ghostText,
        tabSuggestions = state.tabSuggestions,
        tabSelectionIndex = state.tabSelectionIndex,
        isWaitingForResponse = state.isWaitingForResponse,
        input = input,
        onInputChange = {
            input = it
            viewModel.input.setInput(it.text)
            viewModel.input.updateGhostText(
                cursorAtEnd = cliCursorAtEnd(it.selection.collapsed, it.selection.start, it.text.length),
            )
        },
        actions = CliTerminalActions(
            onSubmit = { viewModel.submit() },
            onTab = { viewModel.input.tabComplete() },
            onHistoryUp = { viewModel.input.historyUp() },
            onHistoryDown = { viewModel.input.historyDown() },
            onAcceptGhost = { viewModel.input.acceptGhostText() },
            onUpdateGhostText = { viewModel.input.updateGhostText(cursorAtEnd = it) },
            onSetInput = { viewModel.input.setInput(it) },
            onCancel = { viewModel.cancelCurrentCommand() },
            onClear = { viewModel.clear() },
            onCopyBlock = { viewModel.getResponseBlock(it) },
            onApplySuggestion = { viewModel.input.applySuggestionAt(it) },
            onSessions = { viewModel.sessions() },
            onPreviewKey = { event ->
                handleToolsCliExtraKey(event, state, viewModel, onDismissKeyboard = { keyboard?.hide() })
            },
        ),
        modifier = modifier,
    )
}

/** Cmd/Ctrl-K and Escape; Enter/Tab/arrows are handled by the shared capture field. */
private fun handleToolsCliExtraKey(
    event: KeyEvent,
    state: ToolsCliState,
    viewModel: ToolsCliViewModel,
    onDismissKeyboard: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key == Key.K && (event.isMetaPressed || event.isCtrlPressed)) {
        viewModel.clear()
        return true
    }
    if (event.key != Key.Escape) return false
    when (cliEscapeAction(state)) {
        CliEscapeAction.CLEAR_TAB -> viewModel.input.clearTabState()
        CliEscapeAction.CANCEL -> viewModel.cancelCurrentCommand()
        CliEscapeAction.DISMISS -> onDismissKeyboard()
    }
    return true
}
