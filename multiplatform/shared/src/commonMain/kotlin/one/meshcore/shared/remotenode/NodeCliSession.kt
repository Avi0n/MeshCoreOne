package one.meshcore.shared.remotenode

import one.meshcore.shared.cli.CLICompletionEngine
import kotlin.time.Duration

/** The kind of a terminal line, mirroring the Swift `CLIOutputType`; drives the app-target styling. */
enum class CLIOutputType {
    COMMAND,
    RESPONSE,
    ERROR,
    SUCCESS,
}

/** One rendered terminal line. [id] is a stable within-session identity for block reconstruction. */
data class CLIOutputLine(val id: Long, val text: String, val type: CLIOutputType)

/**
 * The full consecutive response containing [line], stripping prompt and firmware `"> "` prefixes.
 * Shared by Node CLI and Tools CLI (iOS `getResponseBlock(containing:)`).
 */
fun List<CLIOutputLine>.responseBlock(line: CLIOutputLine): String {
    val index = indexOfFirst { it.id == line.id }
    if (index < 0) return line.text
    return if (line.type == CLIOutputType.COMMAND) {
        val marker = line.text.indexOf("> ")
        if (marker >= 0) line.text.substring(marker + 2) else line.text
    } else {
        var start = index
        while (start > 0 && this[start - 1].type != CLIOutputType.COMMAND) start--
        var end = index
        while (end < size - 1 && this[end + 1].type != CLIOutputType.COMMAND) end++
        subList(start, end + 1)
            .joinToString("\n") { if (it.text.startsWith("> ")) it.text.substring(2) else it.text }
    }
}

/**
 * Localized strings the terminal emits, injected so the shared session stays free of the app's `L10n`.
 * [bannerConnected] takes the node name; [help] is the ordered help block. The app target supplies
 * localized values; tests supply fixed ones.
 */
data class NodeCliMessages(
    val bannerConnected: (name: String) -> String,
    val bannerHint: String,
    val cancelled: String,
    val rebootSent: String,
    val promptSuffix: String,
    val help: List<String>,
)

/**
 * The output-buffer, history, prompt, and completion logic for a single admin node's CLI terminal,
 * mirroring the Swift `NodeCLIViewModel` minus the SwiftUI/`@Observable` shell. The app-target Compose
 * view model wraps this and observes its state. The terminal talks to the node only through the injected
 * [sendRawCommand] closure (no direct service reference); completion reuses the shared
 * [CLICompletionEngine] in remote mode (`isLocal = false`, session commands excluded — the node CLI is
 * always a single remote session and firmware has no app-only session commands).
 */
class NodeCliSession private constructor(private val parts: NodeCliParts) :
    NodeCliCommands by parts.commands,
    NodeCliOutput by parts.output,
    NodeCliCompletion by parts.completion {
    val outputLines: List<CLIOutputLine>
        get() = parts.state.outputLines
    val commandHistory: List<String>
        get() = parts.state.commandHistory
    val historyIndex: Int?
        get() = parts.state.historyIndex
    var currentInput: String
        get() = parts.state.currentInput
        set(value) {
            parts.state.currentInput = value
        }
    val isWaitingForResponse: Boolean
        get() = parts.state.isWaitingForResponse
    val ghostText: String
        get() = parts.state.ghostText
    val tabSuggestions: List<String>?
        get() = parts.state.tabSuggestions
    val tabSelectionIndex: Int?
        get() = parts.state.tabSelectionIndex

    internal fun updateSessionName(name: String) {
        parts.commands.sessionName = name
        parts.output.sessionName = name
    }

    /** The prompt shown before input, blank while awaiting a response, mirroring `promptText`. */
    val promptText: String
        get() = parts.commands.promptText

    constructor(
        sessionName: String,
        messages: () -> NodeCliMessages,
        completionEngine: CLICompletionEngine = CLICompletionEngine(),
        sendRawCommand: suspend (command: String, timeout: Duration) -> String,
    ) : this(NodeCliParts(sessionName, messages, completionEngine, sendRawCommand)) {
        parts.output.showBanner()
    }
}

private class NodeCliParts(
    sessionName: String,
    messages: () -> NodeCliMessages,
    completionEngine: CLICompletionEngine,
    sendRawCommand: suspend (command: String, timeout: Duration) -> String,
) {
    val state = NodeCliState()
    val output = NodeCliTranscript(state, sessionName, messages)
    val commands = NodeCliCommandRunner(state, sessionName, messages, output, sendRawCommand)
    val completion = NodeCliCompleter(state, completionEngine)
}
