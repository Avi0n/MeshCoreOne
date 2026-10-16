package one.meshcore.shared.tools

fun ToolsCliSession.setInput(value: String) {
    currentInput = value
    clearTabState()
    updateGhostText()
}

fun ToolsCliSession.historyUp() {
    if (commandHistory.isEmpty()) return
    val index = historyIndex
    historyIndex = when {
        index == null -> commandHistory.size - 1
        index > 0 -> index - 1
        else -> index
    }
    historyIndex?.let { currentInput = commandHistory[it] }
    clearTabState()
    updateGhostText()
}

fun ToolsCliSession.historyDown() {
    val index = historyIndex ?: return
    if (index < commandHistory.size - 1) {
        historyIndex = index + 1
        currentInput = commandHistory[index + 1]
    } else {
        historyIndex = null
        currentInput = ""
    }
    clearTabState()
    updateGhostText()
}

internal fun ToolsCliSession.addToHistory(command: String) {
    commandHistory = (commandHistory + command).takeLast(ToolsCliSession.MAX_HISTORY)
    historyIndex = null
}
