package one.meshcore.shared.tools

/**
 * The three load-bearing outcomes of parsing a local-session line: a first word that isn't a local radio
 * command ([NotLocal] — the caller falls through to the existing dispatcher), a parsed-and-validated command
 * ([Command]), or a recognized command with bad input ([Invalid] — the caller renders usage/error).
 * Mirrors the Swift `CLILocalParseResult`.
 */
sealed interface CliLocalParseResult {
    data object NotLocal : CliLocalParseResult
    data class Command(val command: CliLocalCommand) : CliLocalParseResult
    data class Invalid(val error: CliLocalParseError) : CliLocalParseResult
}

/** A recognized-but-rejected local command, carrying only enough to pick a usage or error message. */
sealed interface CliLocalParseError {
    data class BadArguments(val usage: CliLocalUsage) : CliLocalParseError
    data object ValueOutOfRange : CliLocalParseError
    data object InvalidCustomVarToken : CliLocalParseError
}

/** Which usage line to render for a [CliLocalParseError.BadArguments] result. */
enum class CliLocalUsage { GET, SET, SET_RADIO }
