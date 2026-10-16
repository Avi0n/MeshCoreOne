package one.meshcore.shared.tools.ui

import one.meshcore.shared.tools.ToolsCliSession
import one.meshcore.shared.tools.acceptGhostText
import one.meshcore.shared.tools.applySuggestionAt
import one.meshcore.shared.tools.clearTabSuggestions
import one.meshcore.shared.tools.historyDown
import one.meshcore.shared.tools.historyUp
import one.meshcore.shared.tools.setInput
import one.meshcore.shared.tools.tabComplete
import one.meshcore.shared.tools.updateGhostText

/** Keyboard editing for the tools CLI: the draft, history, tab completion, and ghost text. */
internal class ToolsCliInput(private val session: ToolsCliSession, private val publish: () -> Unit) {
    fun setInput(value: String) {
        session.setInput(value)
        publish()
    }

    fun historyUp() {
        session.historyUp()
        publish()
    }

    fun historyDown() {
        session.historyDown()
        publish()
    }

    fun tabComplete() {
        session.tabComplete()
        publish()
    }

    fun applySuggestionAt(index: Int) {
        session.applySuggestionAt(index)
        publish()
    }

    /** Clears the tab-suggestion cycle without touching the input (hardware Escape, first stage). */
    fun clearTabState() {
        session.clearTabSuggestions()
        publish()
    }

    fun acceptGhostText() {
        session.acceptGhostText()
        publish()
    }

    fun updateGhostText(cursorAtEnd: Boolean) {
        if (cursorAtEnd) session.updateGhostText() else session.setInput(session.currentInput)
        publish()
    }
}
