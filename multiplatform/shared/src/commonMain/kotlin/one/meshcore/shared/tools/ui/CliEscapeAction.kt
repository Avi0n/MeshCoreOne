package one.meshcore.shared.tools.ui

/** Which action hardware Escape performs for the current terminal state. */
internal enum class CliEscapeAction { CLEAR_TAB, CANCEL, DISMISS }

/** Clears suggestions first, then cancels a waiting command, then dismisses the keyboard. */
internal fun cliEscapeAction(state: ToolsCliState): CliEscapeAction = when {
    state.tabSuggestions != null -> CliEscapeAction.CLEAR_TAB
    state.isWaitingForResponse -> CliEscapeAction.CANCEL
    else -> CliEscapeAction.DISMISS
}
