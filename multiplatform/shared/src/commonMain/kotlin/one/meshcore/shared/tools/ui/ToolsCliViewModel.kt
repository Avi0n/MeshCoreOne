package one.meshcore.shared.tools.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.remotenode.CLIOutputLine
import one.meshcore.shared.remotenode.responseBlock
import one.meshcore.shared.tools.ToolsCliMessages
import one.meshcore.shared.tools.ToolsCliSession
import one.meshcore.shared.tools.applySelectedSuggestion
import one.meshcore.shared.tools.cancelCurrentCommand
import one.meshcore.shared.tools.executeCommand
import one.meshcore.shared.tools.refreshCompletionCatalog
import one.meshcore.shared.tools.stopCountdown
import one.meshcore.shared.tools.tickCountdown

/** Adapts [ToolsCliSession] to a Compose [StateFlow] with local and optional remote commands. */
class ToolsCliViewModel(
    private val scope: CoroutineScope,
    deviceName: String,
    messages: ToolsCliMessages,
    fetchContacts: suspend () -> List<ContactDto>?,
    fetchChannels: suspend () -> List<ChannelDto>?,
    options: ToolsCliSession.Options = ToolsCliSession.Options(),
) {
    private val session = ToolsCliSession(
        deviceName = deviceName,
        messages = messages,
        fetchContacts = fetchContacts,
        fetchChannels = fetchChannels,
        options = options,
    )
    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<ToolsCliState> = _state.asStateFlow()
    private var commandJob: Job? = null
    internal val input = ToolsCliInput(session, ::publish)

    /** Reloads node names and custom-var keys for completions shown after the terminal opens. */
    fun refreshCompletionCatalog() {
        scope.launch {
            session.refreshCompletionCatalog()
            publish()
        }
    }

    /** Refreshes localized copy and the display-only fallback name without resetting terminal state. */
    fun refreshLocalizedCopy(deviceName: String, messages: ToolsCliMessages) {
        scope.launch {
            session.deviceName = deviceName
            session.messages = messages
            publish()
        }
    }

    fun submit() {
        if (session.applySelectedSuggestion()) {
            publish()
            return
        }
        execute(session.currentInput)
    }

    fun clear() = execute("clear")

    /** Full consecutive response for copy. */
    fun getResponseBlock(line: CLIOutputLine): String = session.outputLines.responseBlock(line)

    /** Lists active sessions from the input accessory. */
    fun sessions() = execute("session list")

    /** Cancels an in-flight command job and appends the session cancellation line. */
    fun cancelCurrentCommand() {
        commandJob?.cancel()
        commandJob = null
        session.cancelCurrentCommand()
        publish()
    }

    private fun execute(command: String) {
        commandJob?.cancel()
        commandJob = scope.launch {
            val ticker = launch {
                while (isActive) {
                    delay(CLI_LOGIN_COUNTDOWN_MS)
                    if (session.remainingSeconds != null) {
                        session.tickCountdown()
                        publish()
                    }
                }
            }
            try {
                val runner = launch { session.executeCommand(command) }
                yield()
                publish()
                runner.join()
            } finally {
                ticker.cancel()
                session.stopCountdown()
                publish()
            }
        }
    }

    private fun snapshot(): ToolsCliState = ToolsCliState(
        outputLines = session.outputLines,
        promptText = session.promptText,
        currentInput = session.currentInput,
        ghostText = session.ghostText,
        tabSuggestions = session.tabSuggestions,
        tabSelectionIndex = session.tabSelectionIndex,
        isWaitingForResponse = session.isWaitingForResponse,
    )

    private fun publish() {
        _state.value = snapshot()
    }

    private companion object {
        const val CLI_LOGIN_COUNTDOWN_MS = 1_000L
    }
}
