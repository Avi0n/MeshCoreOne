package one.meshcore.shared.remotenode

/** Output, history, and completion fields for one [NodeCliSession]. */
internal class NodeCliState {
    var outputLines: List<CLIOutputLine> = emptyList()
    var commandHistory: List<String> = emptyList()
    var historyIndex: Int? = null
    var currentInput: String = ""
    var isWaitingForResponse: Boolean = false
    var ghostText: String = ""
    var tabSuggestions: List<String>? = null
    var tabSelectionIndex: Int? = null
    var nextLineId = 0L
    var hasBannered = false
}
