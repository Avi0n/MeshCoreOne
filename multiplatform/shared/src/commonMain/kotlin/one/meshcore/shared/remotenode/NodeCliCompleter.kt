package one.meshcore.shared.remotenode

import one.meshcore.shared.cli.CLICompletionEngine

/** Ghost text and tab completion for the node CLI input. */
interface NodeCliCompletion {
    /** Recomputes the ghost-text suffix for the current input (cursor must be at the end). */
    fun updateGhostText(cursorAtEnd: Boolean)

    fun acceptGhostText()

    /** Tab cycles through / applies completions, mirroring `tabComplete`. Returns the suggestion list, if any. */
    fun tabComplete(): List<String>?

    fun applySelectedSuggestion(): Boolean

    fun applySuggestionAt(index: Int): Boolean

    fun clearTabState()
}

internal class NodeCliCompleter(private val state: NodeCliState, private val completionEngine: CLICompletionEngine) :
    NodeCliCompletion {
    override fun updateGhostText(cursorAtEnd: Boolean) {
        if (state.currentInput.isEmpty() || !cursorAtEnd) {
            state.ghostText = ""
            return
        }
        val first = remoteCompletions().firstOrNull()
        if (first == null) {
            state.ghostText = ""
            return
        }
        val lastPart = state.currentInput.split(" ").lastOrNull() ?: ""
        state.ghostText = if (first.lowercase().startsWith(lastPart.lowercase())) {
            first.substring(lastPart.length)
        } else {
            ""
        }
    }

    override fun acceptGhostText() {
        if (state.ghostText.isEmpty()) return
        state.currentInput += state.ghostText
        state.ghostText = ""
    }

    override fun tabComplete(): List<String>? {
        fun cycleExisting(): List<String>? {
            val suggestions = state.tabSuggestions?.takeIf { it.isNotEmpty() } ?: return null
            state.tabSelectionIndex = state.tabSelectionIndex?.let { (it + 1) % suggestions.size } ?: 0
            return suggestions
        }
        fun freshSuggestions(): List<String>? {
            val suggestions = remoteCompletions()
            if (suggestions.size > 1) {
                state.tabSuggestions = suggestions
                state.tabSelectionIndex = null
                return suggestions
            }
            if (suggestions.isEmpty()) clearTabState() else applyCompletion(suggestions[0])
            return null
        }
        return cycleExisting() ?: freshSuggestions()
    }

    override fun applySelectedSuggestion(): Boolean {
        val index = state.tabSelectionIndex ?: return false
        return applySuggestionAt(index)
    }

    override fun applySuggestionAt(index: Int): Boolean {
        val suggestions = state.tabSuggestions
        if (suggestions == null || index !in suggestions.indices) return false
        applyCompletion(suggestions[index])
        clearTabState()
        return true
    }

    override fun clearTabState() {
        state.tabSuggestions = null
        state.tabSelectionIndex = null
    }

    private fun remoteCompletions(): List<String> = completionEngine.completions(
        state.currentInput,
        isLocal = false,
        includeSessionCommands = false,
    )

    private fun applyCompletion(suggestion: String) {
        val parts = state.currentInput.split(" ")
        state.currentInput = if (parts.size <= 1) {
            "$suggestion "
        } else {
            (parts.dropLast(1) + suggestion).joinToString(" ") + " "
        }
        state.ghostText = ""
    }
}
