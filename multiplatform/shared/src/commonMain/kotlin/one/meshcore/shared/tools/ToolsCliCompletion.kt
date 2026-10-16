package one.meshcore.shared.tools

import one.meshcore.shared.cli.CLICompletionConsumer

fun ToolsCliSession.tabComplete() {
    // No completion during password entry or a pending y/n confirmation (mirrors iOS tabComplete).
    if (pendingLoginContact != null || pendingConfirmation != null) return
    val suggestions = completionEngine.completions(currentInput, isLocal = isLocalSession)
    if (suggestions.isEmpty()) {
        clearTabState()
        return
    }
    val listed = tabSuggestions
    if (listed != null) {
        tabSelectionIndex = CLICompletionConsumer.nextTabIndex(tabSelectionIndex, listed.size)
        return
    }
    presentFreshSuggestions(suggestions)
}

// A lone match applies straight away; several are listed without touching what the user typed,
// and only the next Tab starts highlighting one (iOS tabComplete).
private fun ToolsCliSession.presentFreshSuggestions(suggestions: List<String>) {
    if (suggestions.size == 1) {
        applySuggestion(suggestions[0])
        updateGhostText()
    } else {
        tabSuggestions = suggestions
        tabSelectionIndex = null
    }
}

fun ToolsCliSession.acceptGhostText() {
    if (ghostText.isEmpty()) return
    currentInput += ghostText
    ghostText = ""
    clearTabState()
}

fun ToolsCliSession.applySelectedSuggestion(): Boolean {
    val index = tabSelectionIndex ?: return false
    return applySuggestionAt(index)
}

fun ToolsCliSession.applySuggestionAt(index: Int): Boolean {
    val list = tabSuggestions ?: return false
    if (index !in list.indices) return false
    applySuggestion(list[index])
    clearTabState()
    ghostText = ""
    return true
}

fun ToolsCliSession.updateGhostText() {
    // No ghost text during password entry or a pending y/n confirmation (mirrors iOS updateGhostText).
    if (pendingLoginContact != null || pendingConfirmation != null) {
        ghostText = ""
        return
    }
    if (currentInput.isEmpty()) {
        ghostText = ""
        return
    }
    // The suggestion extends the last token, not the whole line, so "session li" ghosts "st".
    val completions = completionEngine.completions(currentInput, isLocal = isLocalSession)
    ghostText = CLICompletionConsumer.ghostSuffix(currentInput, completions)
}

internal fun ToolsCliSession.applySuggestion(suggestion: String) {
    currentInput = CLICompletionConsumer.applyCompletion(currentInput, suggestion)
    ghostText = ""
}

internal fun ToolsCliSession.clearTabState() {
    tabSuggestions = null
    tabSelectionIndex = null
}

/** Clears the tab-suggestion cycle without altering the input (hardware Escape, first stage). */
fun ToolsCliSession.clearTabSuggestions() = clearTabState()
