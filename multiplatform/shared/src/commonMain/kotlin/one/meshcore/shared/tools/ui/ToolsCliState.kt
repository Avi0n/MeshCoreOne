package one.meshcore.shared.tools.ui

import one.meshcore.shared.remotenode.CLIOutputLine

data class ToolsCliState(
    val outputLines: List<CLIOutputLine> = emptyList(),
    val promptText: String = "",
    val currentInput: String = "",
    val ghostText: String = "",
    val tabSuggestions: List<String>? = null,
    val tabSelectionIndex: Int? = null,
    val isWaitingForResponse: Boolean = false,
)
