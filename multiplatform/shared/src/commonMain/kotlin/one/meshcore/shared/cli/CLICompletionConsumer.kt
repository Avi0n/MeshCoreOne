package one.meshcore.shared.cli

/**
 * Pure consumer helpers for tab / ghost-text completion, extracted from the Swift
 * `CLIToolViewModel` completion extension so the terminal view model can drive the UX without
 * re-deriving the string math.
 *
 * These are side-effect free. The stateful gating that stays in the view model — suppressing ghost
 * text during password entry, only when the cursor is at the end, and resetting the tab cycle when
 * the input changes — is intentionally NOT modeled here; only the token math and cycle arithmetic
 * are, so both platforms share one implementation.
 *
 * Cycle semantics: a single suggestion auto-applies (via [applyCompletion]); multiple suggestions
 * are listed and cycled on repeated Tab (via [nextTabIndex]); there is NO common-prefix expansion.
 */
object CLICompletionConsumer {
    /**
     * Ghost-text suffix to render after [input], given the engine's [suggestions] for it. Empty
     * when there is no suggestion or the first suggestion does not case-insensitively extend the
     * final whitespace-split token. Mirrors Swift `updateGhostText`.
     */
    fun ghostSuffix(input: String, suggestions: List<String>): String {
        val first = suggestions.firstOrNull() ?: return ""

        // Last whitespace-split token; a trailing space yields an empty token (whole suggestion).
        val lastPart = input.split(" ").last()

        return if (first.lowercase().startsWith(lastPart.lowercase())) {
            first.substring(lastPart.length)
        } else {
            ""
        }
    }

    /**
     * The input after applying [suggestion]: it replaces the final whitespace-split token and a
     * trailing space is appended so the next token can be typed. Mirrors Swift `applyCompletion`.
     */
    fun applyCompletion(input: String, suggestion: String): String {
        val parts = input.split(" ")
        return if (parts.size <= 1) {
            "$suggestion "
        } else {
            (parts.dropLast(1) + suggestion).joinToString(" ") + " "
        }
    }

    /**
     * Next highlighted index when Tab is pressed while [count] suggestions are shown: advance
     * `(current + 1) % count`, or start at `0` when nothing is highlighted yet. Mirrors the cycle
     * branch of Swift `tabComplete`. [count] must be positive.
     */
    fun nextTabIndex(currentIndex: Int?, count: Int): Int = if (currentIndex != null) (currentIndex + 1) % count else 0
}
