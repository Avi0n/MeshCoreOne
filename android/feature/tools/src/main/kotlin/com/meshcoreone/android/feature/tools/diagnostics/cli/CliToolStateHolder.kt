// PortedFrom: MC1/Views/Tools/CLI/CLIToolViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/CLI/CLIToolViewModel+Completion.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.feature.tools.diagnostics.FormattedStringIds
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.feature.tools.diagnostics.DiagnosticsClock
import com.meshcoreone.android.feature.tools.diagnostics.DiagnosticsText
import com.meshcoreone.android.feature.tools.diagnostics.text.LocalizedCompare
import com.meshcoreone.android.feature.tools.diagnostics.text.SwiftNumbers
import com.meshcoreone.android.feature.tools.diagnostics.text.SwiftStrings
import java.lang.ref.WeakReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Immutable snapshot of the Tools CLI terminal. */
data class CliToolState(
    val terminal: CliTerminalState = CliTerminalState(),
    val activeSession: CliSession? = null,
    val remoteSessions: List<CliSession> = emptyList(),
    val isWaitingForResponse: Boolean = false,
    val remainingSeconds: Int? = null,
    val pendingLoginContact: ContactDTO? = null,
    /** A parsed dangerous local command awaiting a yes/no confirmation line. */
    val pendingConfirmation: CliLocalCommand? = null,
    val localDeviceName: String = "",
    val hasShownWelcome: Boolean = false,
)

/**
 * Plain state holder for the Tools-tab CLI (Swift `CLIToolViewModel`). Confined to [scope]'s
 * dispatcher, which must be single-threaded (the UI's main dispatcher), like the Swift
 * `@MainActor` class; an androidx `ViewModel` can wrap it and pass `viewModelScope`.
 */
class CliToolStateHolder(
    internal val scope: CoroutineScope,
    internal val text: DiagnosticsText,
    internal val clock: DiagnosticsClock = DiagnosticsClock.SYSTEM,
    internal val errors: CliErrorPresentation = CliErrorPresentation(),
) : CliTerminalHost {
    val completionEngine = CliCompletionEngine()
    private val mutableState = MutableStateFlow(CliToolState())
    val state: StateFlow<CliToolState> = mutableState.asStateFlow()
    internal val localizedCompare = LocalizedCompare(text.locale)

    private var dependencies: CliToolFeatureDependencies? = null
    private var lastConfiguredAdminService: WeakReference<CliRepeaterAdminPort>? = null
    private var currentCommandJob: Job? = null
    private var nodeNamesJob: Job? = null

    /** Prefetches custom-var keys once per connection, separate from the per-configure node-name task. */
    private var customVarKeysJob: Job? = null
    private var countdownJob: Job? = null

    internal val repeaterAdminService: CliRepeaterAdminPort? get() = dependencies?.repeaterAdminService?.invoke()
    internal val remoteNodeService: CliRemoteNodePort? get() = dependencies?.remoteNodeService?.invoke()
    internal val settingsService: CliSettingsPort? get() = dependencies?.settingsService?.invoke()
    internal val dataStore: CliNodeDirectory? get() = dependencies?.dataStore?.invoke()
    internal val radioId get() = dependencies?.radioId?.invoke()
    internal val connectedDevice get() = dependencies?.connectedDevice?.invoke()

    internal suspend fun sendSelfAdvert(flood: Boolean) {
        dependencies?.sendSelfAdvert?.invoke(flood)
    }

    override val currentInput: String get() = state.value.terminal.currentInput
    override val ghostText: String get() = state.value.terminal.ghostText
    override val tabSelectionIndex: Int? get() = state.value.terminal.tabSelectionIndex
    override val isWaitingForResponse: Boolean get() = state.value.isWaitingForResponse

    val promptText: String get() = prompt(state.value)

    internal fun update(transform: (CliToolState) -> CliToolState) = mutableState.update(transform)

    private fun updateTerminal(transform: (CliTerminalState) -> CliTerminalState) =
        update { it.copy(terminal = transform(it.terminal)) }

    // MARK: - Setup

    fun configure(dependencies: CliToolFeatureDependencies, localDeviceName: String) {
        update { it.copy(localDeviceName = localDeviceName) }
        this.dependencies = dependencies

        val incomingService = dependencies.repeaterAdminService()
        if (lastConfiguredAdminService?.get() !== incomingService) {
            if (incomingService != null) {
                if (state.value.activeSession == null) {
                    update { it.copy(activeSession = CliSession.local(localDeviceName)) }
                    showWelcomeBanner()
                }
                customVarKeysJob?.cancel()
                customVarKeysJob = scope.launch { updateCustomVarKeysForCompletion() }
            } else {
                update { it.copy(activeSession = null, remoteSessions = emptyList()) }
                customVarKeysJob?.cancel()
                customVarKeysJob = null
                completionEngine.updateCustomVarKeys(emptyList())
            }
        }
        lastConfiguredAdminService = incomingService?.let(::WeakReference)

        nodeNamesJob?.cancel()
        nodeNamesJob = scope.launch { updateNodeNamesForCompletion() }
    }

    fun cleanup() {
        currentCommandJob?.cancel()
        currentCommandJob = null
        nodeNamesJob?.cancel()
        nodeNamesJob = null
        customVarKeysJob?.cancel()
        customVarKeysJob = null
        stopCountdown()
    }

    /** Resets state for a new connection while preserving command history. */
    fun reset() {
        cleanup()
        update {
            it.copy(
                terminal = it.terminal.clearingOutput().copy(currentInput = "", ghostText = "").clearingTabState(),
                activeSession = null,
                remoteSessions = emptyList(),
                isWaitingForResponse = false,
                pendingLoginContact = null,
                pendingConfirmation = null,
                hasShownWelcome = false,
            )
        }
    }

    fun stopCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        update { it.copy(remainingSeconds = null) }
    }

    fun startCountdown(seconds: Int) {
        update { it.copy(remainingSeconds = seconds) }
        countdownJob = scope.launch {
            var remaining = seconds
            while (remaining > 0 && currentCoroutineContext().isActive) {
                clock.sleep(1.seconds)
                remaining -= 1
                val value = remaining
                update { it.copy(remainingSeconds = value) }
            }
        }
    }

    fun setActiveSession(session: CliSession?) = update { it.copy(activeSession = session) }

    /** The input binding's setter (Swift `currentInput` is a plain `var`). */
    override fun updateInput(input: String) = updateTerminal { it.copy(currentInput = input) }

    private fun showWelcomeBanner() {
        if (state.value.hasShownWelcome) return
        update { it.copy(hasShownWelcome = true) }
        appendOutput(text.string(AppToolsStrings.toolsCliWelcomeLine1), CliOutputType.RESPONSE)
        appendOutput(text.format(FormattedStringIds.CLI_WELCOME_CONNECTED, state.value.localDeviceName), CliOutputType.RESPONSE)
        appendOutput(text.string(AppToolsStrings.toolsCliWelcomeHint), CliOutputType.RESPONSE)
        appendOutput("", CliOutputType.RESPONSE)
    }

    // MARK: - Prompt

    private fun prompt(state: CliToolState): String {
        state.remainingSeconds?.let { return text.format(FormattedStringIds.CLI_LOGGING_IN, it) }
        if (state.isWaitingForResponse) return ""
        state.pendingConfirmation?.let { return "${text.format(FormattedStringIds.CLI_CONFIRM_PROMPT, it.displayName)} " }
        if (state.pendingLoginContact != null) return "${text.string(AppToolsStrings.toolsCliPasswordPrompt)} "
        val suffix = text.string(AppToolsStrings.toolsCliPromptSuffix)
        val session = state.activeSession ?: return "${text.string(AppToolsStrings.toolsCliDisconnected)}$suffix "
        return if (session.isLocal) "${session.name}$suffix " else "@${session.name}$suffix "
    }

    // MARK: - Command Execution

    override fun executeCommand(command: String) {
        val trimmed = SwiftStrings.trimmingWhitespacesAndNewlines(command)
        val current = state.value
        if (current.isWaitingForResponse) return
        val promptPrefix = SwiftStrings.trimmingWhitespaces(prompt(current))

        current.pendingLoginContact?.let { contact ->
            appendOutput("$promptPrefix ****", CliOutputType.COMMAND)
            update { it.copy(pendingLoginContact = null, terminal = it.terminal.copy(currentInput = "")) }
            if (trimmed.isEmpty()) {
                appendOutput(text.string(AppToolsStrings.toolsCliCancelled), CliOutputType.ERROR)
                return
            }
            launchCommand { completeLogin(contact, trimmed) }
            return
        }

        current.pendingConfirmation?.let { pending ->
            appendOutput("$promptPrefix $trimmed", CliOutputType.COMMAND)
            update { it.copy(pendingConfirmation = null, terminal = it.terminal.copy(currentInput = "")) }
            val answer = SwiftStrings.lowercased(trimmed)
            if (answer != "yes" && answer != "y") {
                appendOutput(text.string(AppToolsStrings.toolsCliCancelled), CliOutputType.ERROR)
                return
            }
            launchCommand { executeLocal(pending) }
            return
        }

        // Echo the prompt when empty input is submitted, matching terminal behavior.
        if (trimmed.isEmpty()) {
            appendOutput(promptPrefix, CliOutputType.COMMAND)
            return
        }

        updateTerminal { it.addingToHistory(trimmed) }
        appendOutput("$promptPrefix $trimmed", CliOutputType.COMMAND)

        val parts = SwiftStrings.split(trimmed, ' ', maxSplits = 1)
        val cmd = SwiftStrings.lowercased(parts[0])
        val args = parts.getOrElse(1) { "" }
        launchCommand { handleCommand(cmd, args, trimmed) }
        updateTerminal { it.copy(currentInput = "") }
    }

    /**
     * Claims the busy flag synchronously so a second submit cannot pass the guard before the job
     * runs. The job is assigned before it starts, so an immediate dispatcher cannot finish it
     * before it becomes the current command.
     */
    private fun launchCommand(block: suspend () -> Unit) {
        update { it.copy(isWaitingForResponse = true) }
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                clearWaitingIfCurrent(job)
            }
        }
        currentCommandJob = job
        job.start()
    }

    override fun cancelCurrentCommand() {
        currentCommandJob?.cancel()
        currentCommandJob = null
        // A pending confirmation has no running job, so it must be cleared here.
        if (state.value.pendingConfirmation != null) {
            update { it.copy(pendingConfirmation = null) }
            appendOutput(text.string(AppToolsStrings.toolsCliCancelled), CliOutputType.ERROR)
            return
        }
        if (state.value.isWaitingForResponse) {
            update { it.copy(isWaitingForResponse = false) }
            appendOutput(text.string(AppToolsStrings.toolsCliCancelled), CliOutputType.ERROR)
        }
    }

    /** Only the job still owning the current-command slot may clear the busy flag. */
    private fun clearWaitingIfCurrent(job: Job) {
        if (currentCommandJob !== job) return
        currentCommandJob = null
        update { it.copy(isWaitingForResponse = false) }
    }

    private suspend fun handleCommand(cmd: String, args: String, raw: String) {
        parseSessionShortcut(cmd)?.let {
            handleSessionCommand(it.toString())
            return
        }

        val isLocal = state.value.activeSession?.isLocal == true
        if (isLocal) {
            when (val result = CliLocalCommandParser.parse(raw)) {
                is CliLocalParseResult.Command -> {
                    if (result.command.requiresConfirmation) {
                        update { it.copy(pendingConfirmation = result.command) }
                    } else {
                        executeLocal(result.command)
                    }
                    return
                }
                is CliLocalParseResult.Invalid -> {
                    renderParseError(result.error)
                    return
                }
                CliLocalParseResult.NotLocal -> Unit
            }
        }

        when {
            cmd == "help" -> showHelp()
            cmd == "clear" && (isLocal || args.isEmpty()) -> updateTerminal { it.clearingOutput() }
            cmd == "session" -> handleSessionCommand(args)
            cmd == "login" -> handleLogin(args)
            cmd == "logout" -> handleLogout()
            cmd == "nodes" && isLocal -> handleNodesCommand()
            cmd == "channels" && isLocal -> handleChannelsCommand()
            else -> handleUnknownCommand(cmd, raw)
        }
    }

    /** `s1`, `s2`, ... as shorthand for `session 1`, `session 2`. */
    private fun parseSessionShortcut(cmd: String): Long? {
        if (!cmd.startsWith("s") || cmd.length <= 1) return null
        return SwiftNumbers.parseInt(cmd.substring(1))
    }

    private suspend fun handleUnknownCommand(cmd: String, raw: String) {
        val session = state.value.activeSession
        if (session != null && !session.isLocal) {
            sendRemoteCommand(raw)
        } else {
            appendOutput("${text.string(AppToolsStrings.toolsCliUnknownCommand)} $cmd", CliOutputType.ERROR)
        }
    }

    private fun showHelp() {
        listOf(
            AppToolsStrings.toolsCliHelpHeader, AppToolsStrings.toolsCliHelpLogin, AppToolsStrings.toolsCliHelpLogout,
            AppToolsStrings.toolsCliHelpSessionList, AppToolsStrings.toolsCliHelpSessionLocal,
            AppToolsStrings.toolsCliHelpSessionName, AppToolsStrings.toolsCliHelpSessionShortcut,
            AppToolsStrings.toolsCliHelpClear, AppToolsStrings.toolsCliHelpHelp,
        ).forEach { appendOutput(text.string(it), CliOutputType.RESPONSE) }

        val session = state.value.activeSession ?: return
        val trailing = if (session.isLocal) {
            listOf(
                AppToolsStrings.toolsCliHelpNodes, AppToolsStrings.toolsCliHelpChannels, null,
                AppToolsStrings.toolsCliHelpLocalHeader, AppToolsStrings.toolsCliHelpLocalList1,
                AppToolsStrings.toolsCliHelpLocalList2, AppToolsStrings.toolsCliHelpLocalList3,
                AppToolsStrings.toolsCliHelpLocalList4, AppToolsStrings.toolsCliHelpLocalList5,
            )
        } else {
            listOf(
                null, AppToolsStrings.toolsCliHelpRepeaterHeader, AppToolsStrings.toolsCliHelpRepeaterList1,
                AppToolsStrings.toolsCliHelpRepeaterList2, AppToolsStrings.toolsCliHelpRepeaterList3,
                AppToolsStrings.toolsCliHelpRepeaterList4,
            )
        }
        trailing.forEach { appendOutput(it?.let(text::string) ?: "", CliOutputType.RESPONSE) }
    }

    // MARK: - History, output and clipboard

    override fun historyUp() = updateTerminal { it.historyUp() }

    override fun historyDown() = updateTerminal { it.historyDown() }

    fun appendOutput(text: String, type: CliOutputType) = updateTerminal { it.appendingOutput(text, type, clock.now()) }

    /** Returns the full response block containing [line] (copy action). */
    fun getResponseBlock(line: CliOutputLine): String = state.value.terminal.responseBlock(line)

    /** Inserts clipboard [text] (read by the UI layer) at a UTF-16 [cursorPosition]. */
    override fun paste(text: String, cursorPosition: Int) = updateTerminal { it.pasting(text, cursorPosition) }

    // MARK: - Ghost text and tab completion

    /** Ghost text only shows with the cursor at the end, and never during password or confirm entry. */
    override fun updateGhostText(cursorAtEnd: Boolean) {
        val current = state.value
        val input = current.terminal.currentInput
        if (current.pendingLoginContact != null || current.pendingConfirmation != null || input.isEmpty() || !cursorAtEnd) {
            updateTerminal { it.clearingGhostText() }
            return
        }
        val first = completionEngine.completions(input, isLocal(current)).firstOrNull()
        updateTerminal { it.withGhostText(first) }
    }

    override fun acceptGhostText() = updateTerminal { it.acceptingGhostText() }

    /** Tab press; returns the suggestions when several are shown, null otherwise. */
    override fun tabComplete(): List<String>? {
        val current = state.value
        if (current.pendingLoginContact != null || current.pendingConfirmation != null) return null
        val (next, suggestions) = current.terminal.tabCompleting { completionEngine.completions(it, isLocal(current)) }
        updateTerminal { next }
        return suggestions
    }

    /** Applies the selected suggestion if in selection mode; true when applied. */
    override fun applySelectedSuggestion(): Boolean {
        val (next, applied) = state.value.terminal.applyingSelectedSuggestion()
        updateTerminal { next }
        return applied
    }

    /** Applies the clicked entry, independently of keyboard selection; ignores a dismissed entry. */
    fun applySuggestion(suggestion: String): Boolean {
        val terminal = state.value.terminal
        val index = terminal.tabSuggestions?.indexOf(suggestion) ?: -1
        if (index < 0) return false
        val (next, applied) = terminal.copy(tabSelectionIndex = index).applyingSelectedSuggestion()
        updateTerminal { next }
        return applied
    }

    override fun clearTabState() = updateTerminal { it.clearingTabState() }

    /** Clears ghost text and tab state when switching sessions. */
    fun clearCompletionState() = updateTerminal { it.clearingGhostText().clearingTabState() }

    private fun isLocal(state: CliToolState): Boolean = state.activeSession?.isLocal ?: true

    suspend fun updateNodeNamesForCompletion() {
        val store = dataStore ?: return
        val radio = radioId ?: return
        try {
            val names = store.fetchContacts(radio).filter { it.isRepeater || it.isRoom }.map { it.name }
            completionEngine.updateNodeNames(names)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errors.log("Failed to fetch contacts for completion", error)
        }
    }

    /** Prefetches custom-var names; old firmware cannot enumerate vars, so failures leave keys empty. */
    suspend fun updateCustomVarKeysForCompletion() {
        val settings = settingsService ?: return
        val vars = try {
            settings.getCustomVars()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errors.log("Custom-var prefetch failed", error)
            return
        }
        completionEngine.updateCustomVarKeys(vars.keys.toList())
    }
}
