// PortedFrom: MC1/Views/Tools/CLI/CLIToolViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/CLI/CLIToolViewModel+Completion.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/NodeCLIViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.feature.tools.diagnostics.text.SwiftStrings
import java.time.Instant

/**
 * The terminal state both CLI view models share in Swift (output, history, input, ghost text and
 * tab-completion selection), as one immutable value with copy-returning transitions. The Swift
 * node CLI keeps a twin of the app CLI's methods; here both holders reuse these transitions.
 */
data class CliTerminalState(
    val outputLines: List<CliOutputLine> = emptyList(),
    val commandHistory: List<String> = emptyList(),
    val historyIndex: Int? = null,
    val currentInput: String = "",
    val ghostText: String = "",
    val tabSuggestions: List<String>? = null,
    val tabSelectionIndex: Int? = null,
    private val nextLineId: Long = 0,
) {
    companion object {
        const val MAX_OUTPUT_LINES = 1000
        const val MAX_HISTORY_ENTRIES = 100
        private const val PROMPT_SEPARATOR = "> "
    }

    fun appendingOutput(text: String, type: CliOutputType, timestamp: Instant): CliTerminalState {
        val line = CliOutputLine(nextLineId, text, type, timestamp)
        return copy(outputLines = (outputLines + line).takeLast(MAX_OUTPUT_LINES), nextLineId = nextLineId + 1)
    }

    fun clearingOutput(): CliTerminalState = copy(outputLines = emptyList())

    fun addingToHistory(command: String): CliTerminalState =
        copy(commandHistory = (commandHistory + command).takeLast(MAX_HISTORY_ENTRIES), historyIndex = null)

    fun historyUp(): CliTerminalState {
        if (commandHistory.isEmpty()) return this
        val index = historyIndex?.let { if (it > 0) it - 1 else it } ?: (commandHistory.size - 1)
        return copy(historyIndex = index, currentInput = commandHistory[index])
    }

    fun historyDown(): CliTerminalState {
        val index = historyIndex ?: return this
        return if (index < commandHistory.size - 1) {
            copy(historyIndex = index + 1, currentInput = commandHistory[index + 1])
        } else {
            copy(historyIndex = null, currentInput = "")
        }
    }

    /**
     * The full response block containing [line]: all consecutive non-command lines with the
     * MeshCore `"> "` prefix stripped; a command line returns its text after the prompt.
     */
    fun responseBlock(line: CliOutputLine): String {
        val index = outputLines.indexOfFirst { it.id == line.id }
        if (index < 0) return line.text
        if (line.type == CliOutputType.COMMAND) {
            val separator = line.text.indexOf(PROMPT_SEPARATOR)
            return if (separator >= 0) line.text.substring(separator + PROMPT_SEPARATOR.length) else line.text
        }
        var start = index
        while (start > 0 && outputLines[start - 1].type != CliOutputType.COMMAND) start--
        var end = index
        while (end < outputLines.size - 1 && outputLines[end + 1].type != CliOutputType.COMMAND) end++
        return outputLines.subList(start, end + 1).joinToString("\n") { it.text.removePrefix(PROMPT_SEPARATOR) }
    }

    /** Ghost text is the unmatched suffix of the first suggestion for the last space-separated part. */
    fun withGhostText(firstSuggestion: String?): CliTerminalState {
        if (firstSuggestion == null) return copy(ghostText = "")
        val lastPart = SwiftStrings.split(currentInput, ' ', omittingEmptySubsequences = false).lastOrNull() ?: ""
        val matches = SwiftStrings.hasPrefix(SwiftStrings.lowercased(firstSuggestion), SwiftStrings.lowercased(lastPart))
        val ghost = if (matches) SwiftStrings.droppingFirstCharacters(firstSuggestion, SwiftStrings.characterCount(lastPart)) else ""
        return copy(ghostText = ghost)
    }

    fun clearingGhostText(): CliTerminalState = copy(ghostText = "")

    fun acceptingGhostText(): CliTerminalState {
        if (ghostText.isEmpty()) return this
        return copy(currentInput = currentInput + ghostText, ghostText = "")
    }

    fun clearingTabState(): CliTerminalState = copy(tabSuggestions = null, tabSelectionIndex = null)

    /**
     * Tab press: cycles an existing selection, otherwise applies a single match or shows the
     * suggestions without a selection. Returns the suggestions when more than one is shown.
     */
    fun tabCompleting(suggestionsFor: (String) -> List<String>): Pair<CliTerminalState, List<String>?> {
        tabSuggestions?.takeIf { it.isNotEmpty() }?.let { suggestions ->
            val next = tabSelectionIndex?.let { (it + 1) % suggestions.size } ?: 0
            return copy(tabSelectionIndex = next) to suggestions
        }
        val suggestions = suggestionsFor(currentInput)
        return when {
            suggestions.isEmpty() -> clearingTabState() to null
            suggestions.size == 1 -> applyingCompletion(suggestions[0]) to null
            else -> copy(tabSuggestions = suggestions, tabSelectionIndex = null) to suggestions
        }
    }

    /** Applies the selected suggestion when in selection mode; false otherwise. */
    fun applyingSelectedSuggestion(): Pair<CliTerminalState, Boolean> {
        val suggestions = tabSuggestions
        val index = tabSelectionIndex
        if (suggestions == null || index == null || index >= suggestions.size) return this to false
        return applyingCompletion(suggestions[index]).clearingTabState() to true
    }

    /** Inserts pasted text at a UTF-16 cursor offset, clamped to the input and kept off a surrogate split. */
    fun pasting(text: String, cursorPosition: Int): CliTerminalState {
        var position = cursorPosition.coerceIn(0, currentInput.length)
        if (position in 1 until currentInput.length && currentInput[position].isLowSurrogate() &&
            currentInput[position - 1].isHighSurrogate()
        ) {
            position++
        }
        return copy(currentInput = currentInput.substring(0, position) + text + currentInput.substring(position))
    }

    private fun applyingCompletion(suggestion: String): CliTerminalState {
        val parts = SwiftStrings.split(currentInput, ' ', omittingEmptySubsequences = false)
        val input = if (parts.size <= 1) "$suggestion " else (parts.dropLast(1) + suggestion).joinToString(" ") + " "
        return copy(currentInput = input, ghostText = "")
    }
}
